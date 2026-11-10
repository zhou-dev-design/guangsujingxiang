//! 下载引擎：多任务并发、单文件多段并行、断点续传、暂停/继续/取消、失败换镜像。

use crate::model::{Config, HistoryRec, Mirror, TaskSnapshot};
use crate::net::{mirror_url, ranked_mirrors};
use crate::state::Core;
use crate::textvariants::segment_count;
use crate::util::{ext_of, fmt_duration, fmt_size, now_ms};
use futures_util::StreamExt;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter};

pub struct Task {
    pub id: String,
    pub url: String,
    pub filename: String,
    pub target: PathBuf,
    pub part: PathBuf,
    pub repo: String,
    pub tag: String,
    pub total: AtomicU64,
    pub done: AtomicU64,
    pub status: std::sync::Mutex<String>,
    pub error: std::sync::Mutex<String>,
    pub mirror: std::sync::Mutex<String>,
    pub segments: AtomicU32,
    pub speed: AtomicU64,
    pub started_at: AtomicI64,
    pub finished_at: AtomicI64,
    pub pause: AtomicBool,
    pub cancel: AtomicBool,
    pub started: AtomicBool,
    pub attempts: AtomicU32,
}

fn f64_to_bits(v: f64) -> u64 {
    v.to_bits()
}
fn bits_to_f64(v: u64) -> f64 {
    f64::from_bits(v)
}

impl Task {
    pub fn new(
        id: String,
        url: String,
        filename: String,
        target: PathBuf,
        repo: String,
        tag: String,
        size_hint: u64,
    ) -> Arc<Self> {
        let part = target.with_file_name(format!(
            "{}.part",
            target
                .file_name()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "download.bin".into())
        ));
        Arc::new(Self {
            id,
            url,
            filename,
            target,
            part,
            repo,
            tag,
            total: AtomicU64::new(size_hint),
            done: AtomicU64::new(0),
            status: std::sync::Mutex::new("queued".into()),
            error: std::sync::Mutex::new(String::new()),
            mirror: std::sync::Mutex::new(String::new()),
            segments: AtomicU32::new(1),
            speed: AtomicU64::new(f64_to_bits(0.0)),
            started_at: AtomicI64::new(0),
            finished_at: AtomicI64::new(0),
            pause: AtomicBool::new(false),
            cancel: AtomicBool::new(false),
            started: AtomicBool::new(false),
            attempts: AtomicU32::new(0),
        })
    }

    pub fn status(&self) -> String {
        self.status
            .lock()
            .map(|s| s.clone())
            .unwrap_or_else(|_| "queued".into())
    }
    pub fn set_status(&self, s: &str) {
        if let Ok(mut g) = self.status.lock() {
            *g = s.to_string();
        }
    }
    pub fn speed(&self) -> f64 {
        bits_to_f64(self.speed.load(Ordering::Relaxed))
    }
    pub fn set_speed(&self, v: f64) {
        self.speed.store(f64_to_bits(v), Ordering::Relaxed);
    }
    pub fn elapsed(&self) -> f64 {
        let s = self.started_at.load(Ordering::Relaxed);
        if s == 0 {
            return 0.0;
        }
        let now = now_ms();
        let end = {
            let f = self.finished_at.load(Ordering::Relaxed);
            if f > 0 {
                f
            } else {
                now
            }
        };
        ((end - s) as f64 / 1000.0).max(0.0)
    }
    pub fn eta(&self) -> f64 {
        let sp = self.speed();
        let total = self.total.load(Ordering::Relaxed);
        if sp <= 0.01 || total == 0 {
            return -1.0;
        }
        let done = self.done.load(Ordering::Relaxed);
        (((total.saturating_sub(done)) as f64) / (sp * 1_048_576.0)).max(0.0)
    }

    pub fn snapshot(&self) -> TaskSnapshot {
        let total = self.total.load(Ordering::Relaxed);
        TaskSnapshot {
            id: self.id.clone(),
            filename: self.filename.clone(),
            path: self.target.to_string_lossy().to_string(),
            repo: self.repo.clone(),
            tag: self.tag.clone(),
            status: self.status(),
            done: self.done.load(Ordering::Relaxed),
            total,
            speed: self.speed(),
            error: self.error.lock().map(|s| s.clone()).unwrap_or_default(),
            mirror: self.mirror.lock().map(|s| s.clone()).unwrap_or_default(),
            segments: self.segments.load(Ordering::Relaxed),
            elapsed: self.elapsed(),
            eta: self.eta(),
            exists: self.target.exists(),
        }
    }
}

pub fn snapshots(core: &Core) -> Vec<TaskSnapshot> {
    match core.tasks.lock() {
        Ok(t) => t.iter().map(|t| t.snapshot()).collect(),
        Err(_) => vec![],
    }
}

pub fn emit_tasks(app: &AppHandle, core: &Core) {
    let list = snapshots(core);
    let _ = app.emit("task:update", list);
}

pub fn running_count(core: &Core) -> usize {
    match core.tasks.lock() {
        Ok(t) => t
            .iter()
            .filter(|t| matches!(t.status().as_str(), "probing" | "running"))
            .count(),
        Err(_) => 0,
    }
}

/// 有空位就启动排队中的任务。
pub fn pump(core: &Arc<Core>, app: &AppHandle) {
    let limit = core.config().concurrency.max(1) as usize;
    let mut to_start: Vec<Arc<Task>> = vec![];
    {
        let tasks = match core.tasks.lock() {
            Ok(t) => t,
            Err(_) => return,
        };
        let mut slots = limit.saturating_sub(
            tasks
                .iter()
                .filter(|t| matches!(t.status().as_str(), "probing" | "running"))
                .count(),
        );
        for t in tasks.iter() {
            if slots == 0 {
                break;
            }
            if t.status() == "queued" && !t.started.load(Ordering::Relaxed) {
                t.started.store(true, Ordering::Relaxed);
                t.set_status("probing");
                slots -= 1;
                to_start.push(t.clone());
            }
        }
    }
    for t in to_start {
        let core2 = core.clone();
        let app2 = app.clone();
        tauri::async_runtime::spawn(async move {
            run_task(core2, app2, t).await;
        });
    }
}

struct ProbeInfo {
    size: u64,
    supports_range: bool,
    mirror: Mirror,
}

/// 单块数据的空闲超时：这么久一个字节都没来，才认定连接死了。
/// 注意这是「两次收到数据之间」的间隔，不是整个下载的总时限。
const STALL_SECS: u64 = 45;

/// 分段请求被服务端当成整包返回（200 而不是 206）时用这个标记，
/// 让上层退回单流下载，而不是直接把任务判失败。
const RANGE_UNSUPPORTED: &str = "__range_unsupported__";

fn content_range_total(cr: &str) -> u64 {
    cr.rsplit('/')
        .next()
        .and_then(|s| s.trim().parse::<u64>().ok())
        .unwrap_or(0)
}

/// 用 Range: bytes=0-0 探一次总大小和是否支持分段。
async fn probe_source(
    core: &Arc<Core>,
    client: &reqwest::Client,
    task: &Task,
    mirrors: &[Mirror],
) -> Result<ProbeInfo, String> {
    let cfg = core.config();
    let mut last = String::from("没有启用的镜像源");
    for m in mirrors {
        if task.cancel.load(Ordering::Relaxed) {
            return Err("已取消".into());
        }
        if task.pause.load(Ordering::Relaxed) {
            return Err("已暂停".into());
        }
        let url = mirror_url(m, &task.url);
        let resp = client
            .get(&url)
            .header("Range", "bytes=0-0")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .timeout(Duration::from_secs(cfg.timeout.max(3) as u64))
            .send()
            .await;
        match resp {
            Ok(r) => {
                let ok = r.status().is_success();
                let status = r.status().as_u16();
                let cr = r
                    .headers()
                    .get("content-range")
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_string();
                let cl = r
                    .headers()
                    .get("content-length")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(0);
                let mut size = content_range_total(&cr);
                if size == 0 && status == 200 && cl > 0 {
                    size = cl;
                }
                let supports = !cr.is_empty() && size > 0;
                if !ok {
                    last = format!("HTTP {}", status);
                    bump_fail(core, &m.name);
                    continue;
                }
                if let Ok(mut g) = task.mirror.lock() {
                    *g = m.name.clone();
                }
                return Ok(ProbeInfo {
                    size,
                    supports_range: supports,
                    mirror: m.clone(),
                });
            }
            Err(e) => {
                last = e.to_string();
                bump_fail(core, &m.name);
            }
        }
    }
    Err(format!("所有镜像都连不上（{}）", last))
}

fn bump_fail(core: &Core, name: &str) {
    if let Ok(mut d) = core.data.lock() {
        if let Some(m) = d.mirrors.iter_mut().find(|m| m.name == name) {
            m.fail = m.fail.saturating_add(1);
        }
    }
}

fn reset_fail(core: &Core, name: &str) {
    if let Ok(mut d) = core.data.lock() {
        if let Some(m) = d.mirrors.iter_mut().find(|m| m.name == name) {
            m.fail = 0;
        }
    }
}

pub fn mirror_cycle(core: &Core) -> Vec<Mirror> {
    let mirrors = match core.data.lock() {
        Ok(d) => d.mirrors.clone(),
        Err(_) => vec![],
    };
    ranked_mirrors(&mirrors)
}

async fn run_task(core: Arc<Core>, app: AppHandle, task: Arc<Task>) {
    let config = core.config();
    task.started_at.store(now_ms(), Ordering::Relaxed);
    core.log(
        "下载",
        &format!("开始 · {} · {}", task.filename, task.url),
    );
    // 断线自动续传：用户没取消、没暂停、开了续传，就最多再试两轮
    let mut attempt = 0u32;
    let result = loop {
        attempt += 1;
        let r = download_inner(&core, &app, &task, &config).await;
        let retryable = r.is_err()
            && config.resume
            && attempt < 3
            && !task.cancel.load(Ordering::Relaxed)
            && !task.pause.load(Ordering::Relaxed);
        if retryable {
            if let Err(e) = &r {
                core.log(
                    "下载",
                    &format!("中断 · {} · 第 {} 次重试 · {}", task.filename, attempt, e),
                );
            }
            tokio::time::sleep(Duration::from_millis(700 * attempt as u64)).await;
            continue;
        }
        break r;
    };
    match result {
        Ok(()) => {
            let total = task.total.load(Ordering::Relaxed);
            task.done.store(total, Ordering::Relaxed);
            task.set_status("done");
            task.set_speed(0.0);
            task.finished_at.store(now_ms(), Ordering::Relaxed);
            core.add_history(HistoryRec {
                id: task.id.clone(),
                name: task.filename.clone(),
                path: task.target.to_string_lossy().to_string(),
                size: total,
                ts: now_ms(),
                mirror: task.mirror.lock().map(|s| s.clone()).unwrap_or_default(),
                repo: task.repo.clone(),
                status: "done".into(),
                error: String::new(),
            });
            core.log(
                "下载",
                &format!(
                    "完成 · {} · {} · 用时 {} · {} · {} 段",
                    task.filename,
                    fmt_size(total),
                    fmt_duration(task.elapsed()),
                    task.mirror.lock().map(|s| s.clone()).unwrap_or_default(),
                    task.segments.load(Ordering::Relaxed)
                ),
            );
        }
        Err(e) => {
            if task.cancel.load(Ordering::Relaxed) {
                task.set_status("canceled");
                cleanup_parts(&task);
            } else if task.pause.load(Ordering::Relaxed) {
                task.set_status("paused");
                task.set_speed(0.0);
            } else {
                task.set_status("error");
                if let Ok(mut g) = task.error.lock() {
                    *g = e.clone();
                }
                task.finished_at.store(now_ms(), Ordering::Relaxed);
                task.set_speed(0.0);
                if !config.resume {
                    cleanup_parts(&task);
                }
                core.add_history(HistoryRec {
                    id: task.id.clone(),
                    name: task.filename.clone(),
                    path: task.target.to_string_lossy().to_string(),
                    size: task.total.load(Ordering::Relaxed),
                    ts: now_ms(),
                    mirror: task.mirror.lock().map(|s| s.clone()).unwrap_or_default(),
                    repo: task.repo.clone(),
                    status: "error".into(),
                    error: e.clone(),
                });
                core.log("下载", &format!("失败 · {} · {}", task.filename, e));
            }
        }
    }
    task.started.store(false, Ordering::Relaxed);
    core.save();
    emit_tasks(&app, &core);
    pump(&core, &app);
}

async fn download_inner(
    core: &Arc<Core>,
    app: &AppHandle,
    task: &Arc<Task>,
    config: &Config,
) -> Result<(), String> {
    if let Some(parent) = task.target.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let mirrors = mirror_cycle(core);
    if mirrors.is_empty() {
        return Err("没有启用的镜像源".into());
    }
    // 下载用「没有总超时」的客户端，否则大文件会在固定秒数处被截断
    let client = core.dl_client()?;
    let info = probe_source(core, &client, task, &mirrors).await?;
    let total = if info.size > 0 {
        info.size
    } else {
        task.total.load(Ordering::Relaxed)
    };
    task.total.store(total, Ordering::Relaxed);
    if task.cancel.load(Ordering::Relaxed) {
        return Err("已取消".into());
    }
    if task.pause.load(Ordering::Relaxed) {
        return Err("已暂停".into());
    }

    let threads = if config.segments {
        config.threads.max(1)
    } else {
        1
    };
    let monitor = {
        let t = task.clone();
        tauri::async_runtime::spawn(async move {
            let mut last_done = t.done.load(Ordering::Relaxed);
            let mut last_at = std::time::Instant::now();
            loop {
                tokio::time::sleep(Duration::from_millis(350)).await;
                let st = t.status();
                if !matches!(st.as_str(), "running" | "probing") {
                    break;
                }
                let now = std::time::Instant::now();
                let dt = now.duration_since(last_at).as_secs_f64().max(0.001);
                let d = t.done.load(Ordering::Relaxed);
                let inst = ((d.saturating_sub(last_done)) as f64) / dt / 1_048_576.0;
                let cur = t.speed();
                t.set_speed(if inst > 0.01 {
                    inst
                } else {
                    cur * 0.55
                });
                last_done = d;
                last_at = now;
            }
        })
    };

    let outcome = if info.supports_range && total > 0 && threads > 1 {
        segmented(core, &client, task, &mirrors, config, total, threads).await
    } else {
        single(core, &client, task, &mirrors, config, total).await
    };
    monitor.abort();
    outcome?;

    if task.cancel.load(Ordering::Relaxed) {
        return Err("已取消".into());
    }
    if task.pause.load(Ordering::Relaxed) {
        return Err("已暂停".into());
    }
    let part_size = fs::metadata(&task.part).map(|m| m.len()).unwrap_or(0);
    if total > 0 && part_size < total {
        return Err("文件不完整，请重试".into());
    }
    if part_size > 0 {
        let _ = fs::rename(&task.part, &task.target);
        if total == 0 {
            task.total.store(part_size, Ordering::Relaxed);
        }
    } else if !task.target.exists() {
        return Err("下载完成但文件不存在".into());
    }
    let _ = app;
    Ok(())
}

fn cleanup_parts(task: &Task) {
    let _ = fs::remove_file(&task.part);
    let seg_dir = seg_dir_of(task);
    let _ = fs::remove_dir_all(seg_dir);
}

fn seg_dir_of(task: &Task) -> PathBuf {
    PathBuf::from(format!("{}.parts", task.part.to_string_lossy()))
}

async fn single(
    core: &Arc<Core>,
    client: &reqwest::Client,
    task: &Arc<Task>,
    mirrors: &[Mirror],
    config: &Config,
    total: u64,
) -> Result<(), String> {
    task.set_status("running");
    task.segments.store(1, Ordering::Relaxed);
    let mut pos: u64 = if config.resume && task.part.exists() {
        fs::metadata(&task.part).map(|m| m.len()).unwrap_or(0)
    } else {
        0
    };
    task.done.store(pos, Ordering::Relaxed);
    let max_fail = if config.failover {
        config.failover_max.max(1)
    } else {
        1
    };
    let pool: Vec<Mirror> = mirrors.to_vec();
    if pool.is_empty() {
        return Err("没有可用镜像".into());
    }
    // 连续失败次数：只要有进展就清零，避免长任务里几次抖动把重试额度耗尽
    let mut fails = 0u32;
    let mut mpos = 0usize;
    // 服务端明确不支持 Range 之后就别再带 Range 头了，免得每次都被整包顶回来
    let mut range_ok = true;
    // 不支持断点的镜像只能整包重下，给它 3 次机会，避免无限循环
    let mut restarts = 0u32;
    if total > 0 && pos >= total {
        return Ok(());
    }

    while total == 0 || pos < total {
        if task.cancel.load(Ordering::Relaxed) {
            return Err("已取消".into());
        }
        if task.pause.load(Ordering::Relaxed) {
            return Err("已暂停".into());
        }
        let m = pool[mpos % pool.len()].clone();
        let url = mirror_url(&m, &task.url);
        let mut req = client
            .get(&url)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity");
        if pos > 0 && range_ok {
            req = req.header("Range", format!("bytes={}-", pos));
        }

        let resp = match req.send().await {
            Ok(r) if r.status().is_success() => r,
            Ok(r) => {
                fails += 1;
                bump_fail(core, &m.name);
                mpos += 1;
                if fails > max_fail {
                    return Err(format!("镜像都试过了，还是没连上（HTTP {}）", r.status()));
                }
                tokio::time::sleep(Duration::from_millis(400)).await;
                continue;
            }
            Err(e) => {
                fails += 1;
                bump_fail(core, &m.name);
                mpos += 1;
                if fails > max_fail {
                    return Err(format!("镜像都试过了，还是没连上（{}）", short_err(&e)));
                }
                tokio::time::sleep(Duration::from_millis(400)).await;
                continue;
            }
        };

        // 带了 Range 却回 200：这个镜像不认断点，只能从头下
        if resp.status().as_u16() == 200 && pos > 0 {
            range_ok = false;
            pos = 0;
            task.done.store(0, Ordering::Relaxed);
            let _ = fs::remove_file(&task.part);
            core.log("下载", &format!("{} 不支持断点续传，从头开始", m.name));
        }

        if let Ok(mut g) = task.mirror.lock() {
            *g = m.name.clone();
        }
        let mut file = {
            let mut opts = tokio::fs::OpenOptions::new();
            opts.create(true).write(true);
            if pos > 0 {
                opts.append(true);
            } else {
                opts.truncate(true);
            }
            opts.open(&task.part)
                .await
                .map_err(|e| format!("无法写入文件：{}", e))?
        };

        let mut stream = resp.bytes_stream();
        let mut clean_end = false;
        loop {
            if task.cancel.load(Ordering::Relaxed) {
                use tokio::io::AsyncWriteExt;
                let _ = file.flush().await;
                return Err("已取消".into());
            }
            if task.pause.load(Ordering::Relaxed) {
                use tokio::io::AsyncWriteExt;
                let _ = file.flush().await;
                return Err("已暂停".into());
            }
            match tokio::time::timeout(Duration::from_secs(STALL_SECS), stream.next()).await {
                Ok(Some(Ok(chunk))) => {
                    use tokio::io::AsyncWriteExt;
                    if file.write_all(&chunk).await.is_err() {
                        break;
                    }
                    pos += chunk.len() as u64;
                    task.done.store(pos, Ordering::Relaxed);
                }
                Ok(Some(Err(_))) => break,
                Ok(None) => {
                    clean_end = true;
                    break;
                }
                Err(_) => break,
            }
        }
        use tokio::io::AsyncWriteExt;
        let _ = file.flush().await;
        reset_fail(core, &m.name);

        let reached_end = total == 0 && clean_end;
        if (total > 0 && pos >= total) || reached_end {
            return Ok(());
        }
        // 没下完：有进展就在原地接着下，不算失败
        if pos > 0 && range_ok {
            fails = 0;
            continue;
        }
        if pos > 0 {
            // 不支持断点的镜像：只能从 0 重来
            restarts += 1;
            if restarts > 3 {
                return Err("镜像不支持断点续传，重下多次仍中断".into());
            }
            pos = 0;
            task.done.store(0, Ordering::Relaxed);
            let _ = fs::remove_file(&task.part);
            fails = 0;
            continue;
        }
        fails += 1;
        bump_fail(core, &m.name);
        if fails > max_fail {
            return Err("下载中断，重试次数用完".into());
        }
        mpos += 1;
        tokio::time::sleep(Duration::from_millis(600)).await;
    }
    Ok(())
}

fn short_err(e: &reqwest::Error) -> String {
    if e.is_timeout() {
        "超时".into()
    } else if e.is_connect() {
        "连不上".into()
    } else if e.is_body() || e.is_decode() {
        "传输中断".into()
    } else {
        "网络错误".into()
    }
}

async fn segmented(
    core: &Arc<Core>,
    client: &reqwest::Client,
    task: &Arc<Task>,
    mirrors: &[Mirror],
    config: &Config,
    total: u64,
    threads: u32,
) -> Result<(), String> {
    let n = segment_count(total, threads);
    task.segments.store(n, Ordering::Relaxed);
    task.set_status("running");
    let seg_dir = seg_dir_of(task);
    let _ = fs::create_dir_all(&seg_dir);

    let seg = total / n as u64;
    let mut ranges: Vec<(u64, u64)> = Vec::with_capacity(n as usize);
    for i in 0..n {
        let a = i as u64 * seg;
        let b = if i == n - 1 { total - 1 } else { (i as u64 + 1) * seg - 1 };
        ranges.push((a, b));
    }
    let done: Arc<Vec<AtomicU64>> = Arc::new(
        (0..n)
            .map(|i| {
                let p = seg_dir.join(format!("{}.seg", i));
                let have = fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
                let len = ranges[i as usize].1 - ranges[i as usize].0 + 1;
                AtomicU64::new(have.min(len))
            })
            .collect(),
    );
    task.done.store(
        done.iter().map(|d| d.load(Ordering::Relaxed)).sum(),
        Ordering::Relaxed,
    );

    let mut set = tokio::task::JoinSet::new();
    for i in 0..n {
        let core2 = core.clone();
        let client2 = client.clone();
        let task2 = task.clone();
        let done2 = done.clone();
        let pool = mirrors.to_vec();
        let (start, end) = ranges[i as usize];
        let cfg = config.clone();
        let path = seg_dir.join(format!("{}.seg", i));
        set.spawn(async move {
            segment_worker(&core2, &client2, &task2, &pool, &cfg, i as usize, start, end, done2, path).await
        });
    }
    let mut err: Option<String> = None;
    while let Some(joined) = set.join_next().await {
        match joined {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                if err.is_none() {
                    err = Some(e);
                }
            }
            Err(e) => {
                if err.is_none() {
                    err = Some(format!("分片线程异常：{}", e));
                }
            }
        }
    }
    if task.cancel.load(Ordering::Relaxed) {
        return Err("已取消".into());
    }
    if task.pause.load(Ordering::Relaxed) {
        return Err("已暂停".into());
    }
    if let Some(e) = err {
        if e == RANGE_UNSUPPORTED {
            // 有镜像把 Range 请求当整包回：退回单流，别让整条任务失败
            core.log("下载", "镜像不支持分段下载，已自动切换为单线程续传");
            let _ = fs::remove_dir_all(&seg_dir);
            task.segments.store(1, Ordering::Relaxed);
            task.done.store(0, Ordering::Relaxed);
            return single(core, client, task, mirrors, config, total).await;
        }
        return Err(e);
    }
    for i in 0..n as usize {
        let len = ranges[i].1 - ranges[i].0 + 1;
        if done[i].load(Ordering::Relaxed) < len {
            return Err("部分分片下载失败".into());
        }
    }

    // 合并：按顺序拼成一个 .part（每个分片只取自己那段长度，防止服务端多吐字节）
    let mut out = fs::File::create(&task.part).map_err(|e| format!("无法写入文件：{}", e))?;
    for i in 0..n {
        let p = seg_dir.join(format!("{}.seg", i));
        let len = ranges[i as usize].1 - ranges[i as usize].0 + 1;
        let mut f = fs::File::open(&p).map_err(|e| format!("分片丢失：{}", e))?;
        std::io::copy(&mut std::io::Read::take(&mut f, len), &mut out)
            .map_err(|e| format!("合并分片失败：{}", e))?;
    }
    drop(out);
    let _ = fs::remove_dir_all(&seg_dir);
    let size = fs::metadata(&task.part).map(|m| m.len()).unwrap_or(0);
    if size < total {
        return Err("合并后的文件不完整".into());
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn segment_worker(
    core: &Arc<Core>,
    client: &reqwest::Client,
    task: &Arc<Task>,
    pool: &[Mirror],
    config: &Config,
    idx: usize,
    start: u64,
    end: u64,
    done: Arc<Vec<AtomicU64>>,
    path: PathBuf,
) -> Result<(), String> {
    let total_len = end - start + 1;
    let already = done[idx].load(Ordering::Relaxed).min(total_len);
    if already >= total_len {
        return Ok(());
    }
    let _ = fs::create_dir_all(path.parent().unwrap_or(Path::new(".")));
    let mut file = tokio::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .await
        .map_err(|e| format!("无法写入分片：{}", e))?;
    let max_fail = if config.failover {
        config.failover_max.max(1)
    } else {
        1
    };
    // 连续失败次数：分片只要有进展就清零
    let mut fails = 0u32;
    let mut mpos = idx % pool.len().max(1);

    while done[idx].load(Ordering::Relaxed) < total_len {
        if task.cancel.load(Ordering::Relaxed) {
            return Ok(());
        }
        if task.pause.load(Ordering::Relaxed) {
            return Ok(());
        }
        let m = match pool.get(mpos % pool.len().max(1)) {
            Some(m) => m.clone(),
            None => return Err("没有可用镜像".into()),
        };
        let offset = done[idx].load(Ordering::Relaxed);
        let pos = start + offset;
        let url = mirror_url(&m, &task.url);
        // 注意：这里不能再加 .timeout()，那是含响应体的总时限，大分片会被腰斩
        let resp = client
            .get(&url)
            .header("Range", format!("bytes={}-{}", pos, end))
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .send()
            .await;
        let resp = match resp {
            Ok(r) if r.status().as_u16() == 206 => r,
            Ok(r) if r.status().as_u16() == 200 && r.status().is_success() => {
                // 有的镜像会把 Range 当普通请求整包返回：标记出来，让上层改走单流
                return Err(RANGE_UNSUPPORTED.into());
            }
            Ok(r) => {
                fails += 1;
                bump_fail(core, &m.name);
                mpos += 1;
                if fails > max_fail {
                    return Err(format!("分片请求失败（HTTP {}）", r.status()));
                }
                tokio::time::sleep(Duration::from_millis(400)).await;
                continue;
            }
            Err(e) => {
                fails += 1;
                bump_fail(core, &m.name);
                mpos += 1;
                if fails > max_fail {
                    return Err(format!("分片连接失败（{}）", short_err(&e)));
                }
                tokio::time::sleep(Duration::from_millis(400)).await;
                continue;
            }
        };
        if let Ok(mut g) = task.mirror.lock() {
            *g = m.name.clone();
        }
        let mut stream = resp.bytes_stream();
        loop {
            if task.cancel.load(Ordering::Relaxed) || task.pause.load(Ordering::Relaxed) {
                use tokio::io::AsyncWriteExt;
                let _ = file.flush().await;
                return Ok(());
            }
            match tokio::time::timeout(Duration::from_secs(STALL_SECS), stream.next()).await {
                Ok(Some(Ok(chunk))) => {
                    use tokio::io::AsyncWriteExt;
                    if file.write_all(&chunk).await.is_err() {
                        break;
                    }
                    let add = chunk.len() as u64;
                    let d = done[idx].fetch_add(add, Ordering::Relaxed) + add;
                    task.done.fetch_add(add, Ordering::Relaxed);
                    if d >= total_len {
                        break;
                    }
                }
                Ok(Some(Err(_))) => break,
                Ok(None) => break,
                Err(_) => break,
            }
        }
        use tokio::io::AsyncWriteExt;
        let _ = file.flush().await;
        reset_fail(core, &m.name);
        task.attempts.fetch_add(1, Ordering::Relaxed);
        let after = done[idx].load(Ordering::Relaxed);
        if after >= total_len {
            return Ok(());
        }
        if after > offset {
            fails = 0;      // 有进展，继续同一条连接重连
            continue;
        }
        fails += 1;
        bump_fail(core, &m.name);
        if fails > max_fail {
            return Err("分片重试次数用完".into());
        }
        mpos += 1;
        tokio::time::sleep(Duration::from_millis(600)).await;
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// 对外操作
// ---------------------------------------------------------------------------

pub fn pause_task(task: &Task) {
    if matches!(task.status().as_str(), "running" | "probing" | "queued") {
        task.pause.store(true, Ordering::Relaxed);
        task.set_status("paused");
        task.set_speed(0.0);
    }
}

pub fn resume_task(core: &Arc<Core>, app: &AppHandle, task: &Arc<Task>) {
    if task.status() == "paused" || task.status() == "error" {
        task.pause.store(false, Ordering::Relaxed);
        task.cancel.store(false, Ordering::Relaxed);
        task.started.store(false, Ordering::Relaxed);
        task.set_status("queued");
        if let Ok(mut g) = task.error.lock() {
            g.clear();
        }
        pump(core, app);
    }
}

pub fn cancel_task(core: &Arc<Core>, task: &Arc<Task>) {
    task.cancel.store(true, Ordering::Relaxed);
    task.pause.store(false, Ordering::Relaxed);
    task.set_status("canceled");
    task.set_speed(0.0);
    task.finished_at.store(now_ms(), Ordering::Relaxed);
    if !task.started.load(Ordering::Relaxed) {
        cleanup_parts(task);
    }
    core.save();
}

pub fn clear_finished(core: &Core) {
    if let Ok(mut t) = core.tasks.lock() {
        t.retain(|x| {
            matches!(
                x.status().as_str(),
                "queued" | "probing" | "running" | "paused"
            )
        });
    }
}

pub fn cancel_all(core: &Arc<Core>, app: &AppHandle) {
    let list: Vec<Arc<Task>> = match core.tasks.lock() {
        Ok(t) => t.clone(),
        Err(_) => vec![],
    };
    for t in list {
        if matches!(t.status().as_str(), "queued" | "probing" | "running" | "paused") {
            cancel_task(core, &t);
        }
    }
    emit_tasks(app, core);
}

/// 按同名规则决定最终落点。
pub fn resolve_target(dir: &Path, filename: &str, rule: &str) -> (PathBuf, bool) {
    let name = crate::util::safe_name(filename, "download.bin");
    let base = dir.join(name);
    match rule {
        "skip" => {
            if base.exists() {
                (base, true)
            } else {
                (base, false)
            }
        }
        "rename" => (crate::util::unique_path(&base), false),
        _ => (base, false),
    }
}

pub fn make_task_id() -> String {
    format!("t{}{}", now_ms(), (now_ms() % 97))
}

pub fn ext_guess(name: &str) -> String {
    ext_of(name)
}
