//! HTTP 客户端与镜像拼接 / 排序 / 测速评分。

use crate::model::{Mirror, UA};
use crate::util::now_ms;
use std::time::{Duration, Instant};

pub fn build_client(proxy: &str, timeout_secs: u64) -> Result<reqwest::Client, String> {
    let mut b = reqwest::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(timeout_secs.max(3)))
        .timeout(Duration::from_secs(timeout_secs.max(3) * 4))
        .danger_accept_invalid_certs(false);
    if !proxy.trim().is_empty() {
        let p = reqwest::Proxy::all(proxy.trim()).map_err(|e| format!("代理地址无效：{}", e))?;
        b = b.proxy(p);
    } else {
        b = b.no_proxy();
    }
    b.build().map_err(|e| format!("HTTP 客户端初始化失败：{}", e))
}

/// 下载专用客户端：**不设总超时**。
///
/// reqwest 的 `.timeout()` 是「从发起请求到响应体读完」的总时限，不是空闲超时。
/// 用它下大文件，传输超过这个秒数就会被强行切断 —— 表现就是“下到一部分自动停止”，
/// 而且停在的百分比正好等于 速度 × 超时秒数（实测 69MB 的包停在 29%、24.8MB 的停在 52%）。
/// 这里只保留连接超时；传输中途卡住由下载循环里的单块读取超时兜底。
pub fn build_download_client(proxy: &str) -> Result<reqwest::Client, String> {
    let mut b = reqwest::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(20))
        .tcp_keepalive(Duration::from_secs(30))
        .tcp_nodelay(true)
        .pool_idle_timeout(Duration::from_secs(90))
        .danger_accept_invalid_certs(false);
    if !proxy.trim().is_empty() {
        let p = reqwest::Proxy::all(proxy.trim()).map_err(|e| format!("代理地址无效：{}", e))?;
        b = b.proxy(p);
    } else {
        b = b.no_proxy();
    }
    b.build()
        .map_err(|e| format!("HTTP 客户端初始化失败：{}", e))
}

/// 镜像拼接：prefix = 拼完整链接，path = 拼 GitHub 路径，direct = 直连。
pub fn mirror_url(m: &Mirror, gh_url: &str) -> String {
    let base = m.base.trim_end_matches('/');
    if base.is_empty() {
        return gh_url.to_string();
    }
    if m.mode == "path" {
        let mut tail = gh_url;
        for marker in ["https://github.com/", "http://github.com/"] {
            if let Some(rest) = tail.strip_prefix(marker) {
                tail = rest;
                break;
            }
        }
        return format!("{}/{}", base, tail.trim_start_matches('/'));
    }
    format!("{}/{}", base, gh_url)
}

pub fn mirror_score(m: &Mirror) -> f64 {
    let sp = (m.speed.max(0.0) / 10.0).min(1.0);
    let lat = m.latency.max(0) as f64;
    let ls = if lat > 0.0 {
        (1.0 - lat / 2000.0).max(0.0)
    } else {
        0.0
    };
    let mut score = 0.7 * sp + 0.3 * ls;
    if m.fail >= 3 && m.speed <= 0.0 {
        score -= 0.2;
    }
    score
}

/// 排名：得分降序，直连永远垫底（除非它是唯一选择）。
pub fn ranked_mirrors(mirrors: &[Mirror]) -> Vec<Mirror> {
    let enabled: Vec<Mirror> = mirrors.iter().filter(|m| m.enabled).cloned().collect();
    let mut normal: Vec<Mirror> = enabled.iter().filter(|m| !m.base.is_empty()).cloned().collect();
    let direct: Vec<Mirror> = enabled.iter().filter(|m| m.base.is_empty()).cloned().collect();
    normal.sort_by(|a, b| {
        mirror_score(b)
            .partial_cmp(&mirror_score(a))
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut out = normal;
    out.extend(direct);
    if out.is_empty() {
        enabled
    } else {
        out
    }
}

pub struct ProbeResult {
    pub ok: bool,
    pub latency: i64,
    pub speed: f64,
}

/// 只测一小段：Range 0..chunk，最多 3.5 秒，速度 = 字节/耗时，延迟 = 首字节。
pub async fn probe_mirror(
    client: &reqwest::Client,
    m: &Mirror,
    url: &str,
    chunk_kb: u32,
    timeout: u64,
) -> ProbeResult {
    use futures_util::StreamExt;
    let chunk = (chunk_kb.max(64) as usize) * 1024;
    let target = mirror_url(m, url);
    let t0 = Instant::now();
    let req = client
        .get(&target)
        .header("Range", format!("bytes=0-{}", chunk - 1))
        .header("Accept", "*/*")
        .header("Connection", "close")
        .timeout(Duration::from_secs(timeout.max(3)));
    let resp = match req.send().await {
        Ok(r) => r,
        Err(_) => {
            return ProbeResult {
                ok: false,
                latency: 0,
                speed: 0.0,
            }
        }
    };
    if !resp.status().is_success() {
        return ProbeResult {
            ok: false,
            latency: 0,
            speed: 0.0,
        };
    }
    let ttfb = t0.elapsed().as_secs_f64();
    let mut got = 0usize;
    let cap = t0 + Duration::from_millis(3500);
    let mut stream = resp.bytes_stream();
    while got < chunk && Instant::now() < cap {
        match tokio::time::timeout(Duration::from_secs(timeout.max(3)), stream.next()).await {
            Ok(Some(Ok(b))) => {
                got += b.len();
                if got >= 8192 && got >= chunk.min(65536) {
                    // 已经拿到足够样本，继续读满分片或到 3.5 秒
                    if got >= chunk {
                        break;
                    }
                }
            }
            _ => break,
        }
    }
    let dt = t0.elapsed().as_secs_f64().max(0.001);
    if got == 0 {
        return ProbeResult {
            ok: false,
            latency: 0,
            speed: 0.0,
        };
    }
    ProbeResult {
        ok: true,
        latency: (ttfb * 1000.0) as i64,
        speed: (got as f64 / dt / 1_048_576.0 * 100.0).round() / 100.0,
    }
}

/// 把一次测速结果写回镜像对象。
pub fn apply_probe(m: &mut Mirror, r: &ProbeResult) {
    m.checked = now_ms();
    m.latency = if r.ok { r.latency } else { 0 };
    m.speed = if r.ok { r.speed } else { 0.0 };
    m.fail = if r.ok { 0 } else { m.fail + 1 };
}
