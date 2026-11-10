//! 通用小工具：单位格式化、时间、文件名清洗。

use chrono::{DateTime, Datelike, FixedOffset, Local, TimeZone, Timelike};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn fmt_size(n: u64) -> String {
    if n == 0 {
        return "0 B".to_string();
    }
    let units = ["B", "KB", "MB", "GB", "TB"];
    let mut v = n as f64;
    let mut i = 0usize;
    while v >= 1024.0 && i < units.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{} B", n)
    } else {
        format!("{:.1} {}", v, units[i])
    }
}

pub fn fmt_speed(mb: f64) -> String {
    if mb <= 0.0 {
        return "0.0 MB/s".to_string();
    }
    if mb < 1.0 {
        format!("{:.0} KB/s", mb * 1024.0)
    } else {
        format!("{:.1} MB/s", mb)
    }
}

pub fn fmt_duration(secs: f64) -> String {
    if !secs.is_finite() || secs < 0.0 || secs > 86400.0 * 9.0 {
        return "--:--".to_string();
    }
    let s = secs as u64;
    let (h, m, sec) = (s / 3600, (s % 3600) / 60, s % 60);
    if h > 0 {
        format!("{:02}:{:02}:{:02}", h, m, sec)
    } else {
        format!("{:02}:{:02}", m, sec)
    }
}

pub fn fmt_ts(ms: i64) -> String {
    match Local.timestamp_millis_opt(ms).single() {
        Some(dt) => format!(
            "{:04}-{:02}-{:02} {:02}:{:02}",
            dt.year(),
            dt.month(),
            dt.day(),
            dt.hour(),
            dt.minute()
        ),
        None => "—".to_string(),
    }
}

pub fn fmt_ts_short(ms: i64) -> String {
    match Local.timestamp_millis_opt(ms).single() {
        Some(dt) => format!(
            "{:02}-{:02} {:02}:{:02}",
            dt.month(),
            dt.day(),
            dt.hour(),
            dt.minute()
        ),
        None => "—".to_string(),
    }
}

fn bj_offset() -> FixedOffset {
    FixedOffset::east_opt(8 * 3600).unwrap()
}

/// GitHub 的 ISO 时间 → 北京时间。
pub fn bj(iso: &str) -> Option<DateTime<FixedOffset>> {
    if iso.trim().is_empty() {
        return None;
    }
    let parsed = DateTime::parse_from_rfc3339(iso.trim())
        .ok()
        .or_else(|| {
            chrono::NaiveDateTime::parse_from_str(iso.trim(), "%Y-%m-%dT%H:%M:%S")
                .ok()
                .and_then(|n| bj_offset().from_local_datetime(&n).single())
        })?;
    Some(parsed.with_timezone(&bj_offset()))
}

pub fn bj_label(iso: &str, with_year: bool) -> String {
    match bj(iso) {
        Some(dt) => {
            if with_year {
                format!(
                    "{:04}-{:02}-{:02} {:02}:{:02}",
                    dt.year(),
                    dt.month(),
                    dt.day(),
                    dt.hour(),
                    dt.minute()
                )
            } else {
                format!(
                    "{:02}-{:02} {:02}:{:02}",
                    dt.month(),
                    dt.day(),
                    dt.hour(),
                    dt.minute()
                )
            }
        }
        None => "—".to_string(),
    }
}

pub fn bj_ms(iso: &str) -> i64 {
    bj(iso).map(|d| d.timestamp_millis()).unwrap_or(0)
}

const RESERVED: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// 文件名清洗：去掉 Windows 非法字符，避开保留名，限制长度。
pub fn safe_name(name: &str, fallback: &str) -> String {
    let mut out = String::new();
    for ch in name.replace(['\r', '\n'], "").trim().chars() {
        if matches!(ch, '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|') || (ch as u32) < 0x20 {
            out.push('_');
        } else {
            out.push(ch);
        }
    }
    let out = out.trim_matches(|c| c == ' ' || c == '.').to_string();
    let mut name = if out.is_empty() {
        fallback.to_string()
    } else {
        out
    };
    let stem_upper = name
        .split('.')
        .next()
        .unwrap_or("")
        .to_ascii_uppercase();
    if RESERVED.contains(&stem_upper.as_str()) {
        name = format!("_{}", name);
    }
    if name.chars().count() > 160 {
        let chars: Vec<char> = name.chars().collect();
        if let Some(pos) = name.rfind('.') {
            let stem_len = name[..pos].chars().count();
            if stem_len > 0 {
                let head: String = chars.iter().take(150).collect();
                let ext: String = name[pos + 1..].chars().take(8).collect();
                name = format!("{}.{}", head, ext);
            } else {
                name = chars.iter().take(160).collect();
            }
        } else {
            name = chars.iter().take(160).collect();
        }
    }
    name
}

pub fn ext_of(name: &str) -> String {
    let n = name.to_lowercase();
    for multi in [".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst"] {
        if n.ends_with(multi) {
            return multi[1..].to_string();
        }
    }
    match n.rfind('.') {
        Some(i) => n[i + 1..].to_string(),
        None => String::new(),
    }
}

/// 同名文件自动加 (1)(2) 后缀。
pub fn unique_path(path: &Path) -> PathBuf {
    if !path.exists() {
        return path.to_path_buf();
    }
    let stem = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    let ext = path
        .extension()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    for i in 1..1000 {
        let name = if ext.is_empty() {
            format!("{} ({})", stem, i)
        } else {
            format!("{} ({}).{}", stem, i, ext)
        };
        let p = path.with_file_name(name);
        if !p.exists() {
            return p;
        }
    }
    path.to_path_buf()
}

pub fn short_path(p: &str, limit: usize) -> String {
    let chars: Vec<char> = p.chars().collect();
    if chars.len() <= limit {
        return p.to_string();
    }
    let keep = ((limit.saturating_sub(1)) / 2).max(12);
    let head: String = chars.iter().take(keep).collect();
    let tail: String = chars.iter().skip(chars.len() - keep).collect();
    format!("{}…{}", head, tail)
}

/// 程序所在目录（便携版就是 exe 同级目录）。
pub fn program_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")))
}
