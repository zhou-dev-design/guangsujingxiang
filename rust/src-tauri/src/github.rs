//! GitHub 链接解析、Release 接口、附件归一化。

use crate::model::{Asset, Parsed, Release, IS_ANDROID, WIN_EXTS};
use crate::textvariants::{
    abi_of, arch_of, decode_url_component, is_noise, name_fingerprint, platform_ok, variant_labels,
};
use crate::util::{bj_ms, ext_of};
use once_cell::sync::Lazy;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

static API_CACHE: Lazy<Mutex<HashMap<String, (Instant, Vec<Release>)>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

// ---------------------------------------------------------------------------
// 链接解析
// ---------------------------------------------------------------------------

fn host_and_parts(url: &str) -> (String, Vec<String>) {
    let after = url
        .split_once("://")
        .map(|(_, r)| r)
        .unwrap_or(url);
    let (host, path) = match after.find('/') {
        Some(i) => (&after[..i], &after[i + 1..]),
        None => (after, ""),
    };
    let path = path.split(['?', '#']).next().unwrap_or("");
    let parts: Vec<String> = path
        .split('/')
        .filter(|p| !p.is_empty())
        .map(|p| decode_url_component(p))
        .collect();
    (host.to_lowercase(), parts)
}

pub fn parse_github(text: &str) -> Option<Parsed> {
    let mut s = text.trim().to_string();
    if s.is_empty() {
        return None;
    }
    if s.starts_with("http://") {
        let low = s.to_lowercase();
        if low.contains("//github.com/")
            || low.contains("//www.github.com/")
            || low.contains("//raw.githubusercontent.com/")
            || low.contains("//codeload.github.com/")
        {
            s = format!("https://{}", &s[7..]);
        }
    }

    // 裸 owner/repo
    static RE_SLUG: Lazy<fancy_regex::Regex> =
        Lazy::new(|| fancy_regex::Regex::new(r"^[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-]+$").unwrap());
    if RE_SLUG.is_match(&s).unwrap_or(false) {
        let mut it = s.splitn(2, '/');
        let owner = it.next().unwrap_or("").to_string();
        let repo = it.next().unwrap_or("").to_string();
        if owner.eq_ignore_ascii_case("releases") {
            return None;
        }
        return Some(Parsed {
            kind: "repo".into(),
            url: format!("https://github.com/{}/{}", owner, repo),
            slug: format!("{}/{}", owner, repo),
            label: "仓库地址".into(),
            owner,
            repo,
            filename: String::new(),
            tag: String::new(),
        });
    }

    if !s.starts_with("https://") && !s.starts_with("http://") {
        return None;
    }
    let (host, parts) = host_and_parts(&s);

    if host == "github.com" || host == "www.github.com" {
        if parts.len() < 2 {
            return None;
        }
        let owner = parts[0].clone();
        let repo = parts[1].trim_end_matches(".git").to_string();
        let slug = format!("{}/{}", owner, repo);
        if parts.len() >= 3 && parts[2] == "releases" {
            if parts.len() >= 6 && parts[3] == "download" {
                return Some(Parsed {
                    kind: "file".into(),
                    url: s.clone(),
                    slug,
                    label: "Release 附件".into(),
                    filename: parts[5].clone(),
                    tag: parts[4].clone(),
                    owner,
                    repo,
                });
            }
            return Some(Parsed {
                kind: "repo".into(),
                url: format!("https://github.com/{}", slug),
                slug,
                label: "仓库地址".into(),
                owner,
                repo,
                filename: String::new(),
                tag: String::new(),
            });
        }
        if parts.len() >= 4 && (parts[2] == "archive" || parts[2] == "releases") {
            return Some(Parsed {
                kind: "archive".into(),
                filename: parts.last().cloned().unwrap_or_default(),
                url: s.clone(),
                slug,
                label: "源码包".into(),
                owner,
                repo,
                tag: String::new(),
            });
        }
        if parts.len() >= 5 && parts[2] == "raw" {
            return Some(Parsed {
                kind: "raw".into(),
                filename: parts.last().cloned().unwrap_or_default(),
                url: s.clone(),
                slug,
                label: "Raw 文件".into(),
                owner,
                repo,
                tag: String::new(),
            });
        }
        return Some(Parsed {
            kind: "repo".into(),
            url: format!("https://github.com/{}", slug),
            slug,
            label: "仓库地址".into(),
            owner,
            repo,
            filename: String::new(),
            tag: String::new(),
        });
    }
    if host == "raw.githubusercontent.com" && parts.len() >= 4 {
        return Some(Parsed {
            kind: "raw".into(),
            owner: parts[0].clone(),
            repo: parts[1].clone(),
            filename: parts.last().cloned().unwrap_or_default(),
            url: s.clone(),
            slug: format!("{}/{}", parts[0], parts[1]),
            label: "Raw 文件".into(),
            tag: String::new(),
        });
    }
    if host == "codeload.github.com" && parts.len() >= 3 {
        return Some(Parsed {
            kind: "archive".into(),
            owner: parts[0].clone(),
            repo: parts[1].clone(),
            filename: parts.last().cloned().unwrap_or_default(),
            url: s.clone(),
            slug: format!("{}/{}", parts[0], parts[1]),
            label: "源码包".into(),
            tag: String::new(),
        });
    }
    if host.ends_with("githubusercontent.com") {
        return Some(Parsed {
            kind: "direct".into(),
            filename: parts.last().cloned().unwrap_or_default(),
            url: s.clone(),
            slug: String::new(),
            label: "普通直链".into(),
            owner: String::new(),
            repo: String::new(),
            tag: String::new(),
        });
    }
    Some(Parsed {
        kind: "direct".into(),
        filename: parts.last().cloned().unwrap_or_default(),
        url: s.clone(),
        slug: String::new(),
        label: "普通直链".into(),
        owner: String::new(),
        repo: String::new(),
        tag: String::new(),
    })
}

// ---------------------------------------------------------------------------
// Release 接口
// ---------------------------------------------------------------------------

pub struct ApiError {
    pub code: u16,
    pub msg: String,
}

impl ApiError {
    fn new(code: u16, msg: impl Into<String>) -> Self {
        Self {
            code,
            msg: msg.into(),
        }
    }
}

fn normalize(json: &Value) -> Option<Release> {
    let obj = json.as_object()?;
    let mut assets: Vec<Asset> = Vec::new();
    if let Some(arr) = obj.get("assets").and_then(|v| v.as_array()) {
        for a in arr {
            let name = a.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if name.is_empty() {
                continue;
            }
            let updated = a
                .get("updated_at")
                .and_then(|v| v.as_str())
                .or_else(|| a.get("created_at").and_then(|v| v.as_str()))
                .unwrap_or("")
                .to_string();
            assets.push(Asset {
                name,
                size: a.get("size").and_then(|v| v.as_u64()).unwrap_or(0),
                updated,
                url: a
                    .get("browser_download_url")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                downloads: a.get("download_count").and_then(|v| v.as_u64()).unwrap_or(0),
                ..Default::default()
            });
        }
    }
    assets.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    let newest = assets
        .iter()
        .map(|a| a.updated.clone())
        .max()
        .unwrap_or_default();
    let published = obj
        .get("published_at")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let newest = if newest.is_empty() {
        published.clone()
    } else {
        newest
    };

    // 先挑出真正会显示的安装包，再据此算变体标签
    let pool: Vec<Asset> = assets
        .iter()
        .filter(|a| {
            if is_noise(&a.name) {
                return false;
            }
            if IS_ANDROID {
                matches!(ext_of(&a.name).as_str(), "apk" | "zip" | "rar" | "pdf" | "7z" | "apks")
            } else {
                WIN_EXTS.contains(&ext_of(&a.name).as_str()) && platform_ok(&a.name)
            }
        })
        .cloned()
        .collect();
    let labels = variant_labels(&pool);

    for a in assets.iter_mut() {
        a.arch = if IS_ANDROID {
            abi_of(&a.name)
        } else {
            arch_of(&a.name)
        };
        a.vkey = name_fingerprint(&a.name);
        a.variant = labels.get(&a.name).cloned().unwrap_or_default();
    }

    Some(Release {
        tag: obj.get("tag_name").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        name: obj
            .get("name")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| obj.get("tag_name").and_then(|v| v.as_str()).unwrap_or(""))
            .to_string(),
        body: obj
            .get("body")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .chars()
            .take(4000)
            .collect(),
        published,
        prerelease: obj.get("prerelease").and_then(|v| v.as_bool()).unwrap_or(false),
        draft: obj.get("draft").and_then(|v| v.as_bool()).unwrap_or(false),
        html_url: obj.get("html_url").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        newest_ms: bj_ms(&newest),
        newest,
        size: assets.iter().map(|a| a.size).sum(),
        assets,
    })
}

pub async fn fetch_releases(
    client: &reqwest::Client,
    repo: &str,
    page: u32,
    per_page: u32,
    token: &str,
    ttl_secs: u64,
) -> Result<Vec<Release>, ApiError> {
    let key = format!("{}|{}|{}", repo.to_lowercase(), page, per_page);
    if let Ok(cache) = API_CACHE.lock() {
        if let Some((at, data)) = cache.get(&key) {
            if at.elapsed().as_secs() < ttl_secs && !data.is_empty() {
                return Ok(data.clone());
            }
        }
    }
    let url = format!(
        "https://api.github.com/repos/{}/releases?per_page={}&page={}",
        repo, per_page, page
    );
    let mut req = client
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .timeout(std::time::Duration::from_secs(20));
    if !token.trim().is_empty() {
        req = req.header("Authorization", format!("Bearer {}", token.trim()));
    }
    let resp = req.send().await.map_err(|e| {
        ApiError::new(0, format!("网络错误：{}", friendly_net_error(&e)))
    })?;
    let status = resp.status();
    if !status.is_success() {
        let code = status.as_u16();
        let msg = if code == 404 {
            "项目不存在，或者这个仓库没有 Release".to_string()
        } else if code == 403 {
            "GitHub 接口额度用完了，等一小时或到设置里填一个 Token".to_string()
        } else {
            format!("GitHub 返回 {}", code)
        };
        return Err(ApiError::new(code, msg));
    }
    let text = resp
        .text()
        .await
        .map_err(|e| ApiError::new(0, format!("网络错误：{}", e)))?;
    let json: Value = serde_json::from_str(&text)
        .map_err(|e| ApiError::new(0, format!("接口返回不是合法 JSON：{}", e)))?;
    let arr = json.as_array().cloned().unwrap_or_default();
    let out: Vec<Release> = arr.iter().filter_map(normalize).collect();
    if let Ok(mut cache) = API_CACHE.lock() {
        cache.insert(key, (Instant::now(), out.clone()));
    }
    Ok(out)
}

fn friendly_net_error(e: &reqwest::Error) -> String {
    if e.is_timeout() {
        "请求超时".to_string()
    } else if e.is_connect() {
        "连不上 GitHub 接口（检查网络或代理）".to_string()
    } else {
        e.to_string()
    }
}

pub fn clear_cache() {
    if let Ok(mut c) = API_CACHE.lock() {
        c.clear();
    }
}

/// Release 排序：按发布时间倒序；撞车时用 tag 数字段兜底。
pub fn sort_releases(list: &mut [Release]) {
    list.sort_by(|a, b| {
        b.newest_ms
            .cmp(&a.newest_ms)
            .then_with(|| tag_numbers(&b.tag).cmp(&tag_numbers(&a.tag)))
    });
}

fn tag_numbers(tag: &str) -> Vec<u64> {
    tag.split(|c: char| !c.is_ascii_digit())
        .filter(|s| !s.is_empty())
        .filter_map(|s| s.parse::<u64>().ok())
        .collect()
}
