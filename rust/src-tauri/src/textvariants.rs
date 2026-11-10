//! 文件名解析：架构识别、平台过滤、差异标签（「安装版 · EXE」）计算。
//!
//! 这一部分是原版最讲究的文本算法，这里逐条 1:1 移植，保证标签结果一致。

use crate::model::{Asset, BUILTIN_FORMATS, IS_ANDROID};
use crate::util::ext_of;
use fancy_regex::Regex;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::path::Path;

static RE_VER_TOKEN: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"^v?\d+(?:\.\d+)+[a-z]?\d*$|^\d{4,}$").unwrap());
static RE_VER_IN_NAME: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)(?<![A-Za-z0-9])v?\d+(?:[._-]\d+)+(?:[a-z]\d*)?").unwrap()
});
static RE_BUILD_NO: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"(?i)(?<![A-Za-z0-9])r\d{2,}(?![0-9])").unwrap());
static RE_YEAR: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?<![0-9])\d{4,}(?![0-9])").unwrap());
static RE_NAME_SPLIT: Lazy<Regex> = Lazy::new(|| Regex::new(r"[^A-Za-z0-9]+").unwrap());
static RE_CAMEL1: Lazy<Regex> = Lazy::new(|| Regex::new(r"([a-z0-9])([A-Z])").unwrap());
static RE_CAMEL2: Lazy<Regex> = Lazy::new(|| Regex::new(r"([A-Z]+)([A-Z][a-z])").unwrap());
static RE_X86_64: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)x86[_-]64").unwrap());
static RE_NORM_X86: Lazy<Regex> = Lazy::new(|| Regex::new(r"x86[-_]64").unwrap());
static RE_NORM_SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"[^a-z0-9]+").unwrap());
static RE_MULTI_DASH: Lazy<Regex> = Lazy::new(|| Regex::new(r"-{2,}").unwrap());

// ---- 架构 / 平台 ----
static ARCH_ARM: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"(arm64|aarch64|armv[5-8]|win[_-]?arm|windows[_-]?arm)").unwrap());
static ARCH_X64: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"(x64|x86[_-]64|amd64|win64|64[_-]?bit|_64(?![0-9]))").unwrap());
static ARCH_X86: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?:(?<![0-9a-z])x86(?![0-9a-z])|i[3-6]86|win32|32[_-]?bit)").unwrap()
});
static NON_WIN: Lazy<Regex> = Lazy::new(|| {
    Regex::new(
        r"(macos|mac[_-]?os|osx|darwin|(?<![a-z])mac(?![a-z])|apple|linux|ubuntu|debian|fedora|appimage|freebsd|android|ios[_-])",
    )
    .unwrap()
});
static RE_NOISE_SRC: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(^|[^a-z])(source ?code|sources?|src)([^a-z]|$)").unwrap()
});
static RE_SCORE_X86: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"(^|[^0-9])x86([^0-9]|$)|win32|32bit").unwrap());

const NOISE_EXT: [&str; 8] = ["sig", "asc", "pdb", "sha1", "sha256", "sha512", "md5", "sha256sum"];
const NOISE_WORDS: [&str; 11] = [
    "symbols",
    "symbol",
    "debugsymbols",
    "dbg",
    "pdb",
    "checksums",
    "checksum",
    "shasums",
    "sha256sums",
    "md5sums",
    "sha256sum",
];
const NON_WIN_EXT: [&str; 9] = ["dmg", "pkg", "deb", "rpm", "apk", "aab", "ipa", "appimage", "snap"];

fn stem_of(name: &str) -> String {
    let p = Path::new(name);
    let stem = p
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| name.to_string());
    if stem.to_lowercase().ends_with(".tar") {
        stem[..stem.len() - 4].to_string()
    } else {
        stem
    }
}

/// 拆词：CamelCase 与各种分隔符都拆开，版本号先整段抹掉。
pub fn tokens(name: &str) -> Vec<String> {
    let mut s = stem_of(name);
    s = RE_X86_64.replace_all(&s, "x64").to_string();
    s = RE_VER_IN_NAME.replace_all(&s, " ").to_string();
    s = RE_BUILD_NO.replace_all(&s, " ").to_string();
    s = RE_YEAR.replace_all(&s, " ").to_string();
    s = RE_CAMEL1.replace_all(&s, "$1 $2").to_string();
    s = RE_CAMEL2.replace_all(&s, "$1 $2").to_string();
    RE_NAME_SPLIT
        .split(&s)
        .filter_map(|t| t.ok())
        .filter(|t| !t.is_empty())
        .filter(|t| !RE_VER_TOKEN.is_match(t).unwrap_or(false))
        .map(|t| t.to_string())
        .collect()
}

/// 把版本号抹成 *，得到跨版本稳定的指纹，用来记住用户选的变体。
pub fn name_fingerprint(name: &str) -> String {
    static RE_V1: Lazy<Regex> =
        Lazy::new(|| Regex::new(r"\d+(?:\.\d+)+[a-z]?\d*").unwrap());
    static RE_V2: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?<!\d)\d{4,}(?!\d)").unwrap());
    static RE_V3: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?<=-)\d{3,}(?=-|$)").unwrap());
    static RE_SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"[\s_]+").unwrap());
    let mut s = stem_of(name).to_lowercase();
    s = RE_V1.replace_all(&s, "*").to_string();
    s = RE_V2.replace_all(&s, "*").to_string();
    s = RE_V3.replace_all(&s, "*").to_string();
    s = RE_SEP.replace_all(&s, "-").to_string();
    let s = s.trim_matches('-');
    format!("{}.{}", s, ext_of(name))
}

pub fn arch_of(name: &str) -> String {
    let n = name.to_lowercase();
    if ARCH_ARM.is_match(&n).unwrap_or(false) {
        return "arm".into();
    }
    if ARCH_X64.is_match(&n).unwrap_or(false) {
        return "x64".into();
    }
    if ARCH_X86.is_match(&n).unwrap_or(false) {
        return "x86".into();
    }
    String::new()
}

/// 安卓端的 ABI 识别（给版本页的架构筛选与默认只看本机架构用）。
pub fn abi_of(name: &str) -> String {
    let n = name.to_lowercase();
    if n.contains("arm64-v8a") || n.contains("arm64_v8a") || n.contains("aarch64") {
        return "arm64-v8a".into();
    }
    if n.contains("armeabi-v7a") || n.contains("armeabi_v7a") || n.contains("armv7") {
        return "armeabi-v7a".into();
    }
    if n.contains("x86_64") || n.contains("x86-64") || n.contains("x64") {
        return "x86_64".into();
    }
    if RE_SCORE_X86.is_match(&n).unwrap_or(false) || n.contains("x86") {
        return "x86".into();
    }
    if n.contains("universal") || n.contains("all") {
        return "universal".into();
    }
    String::new()
}

pub fn preferred_abi() -> String {
    match std::env::consts::ARCH {
        "aarch64" => "arm64-v8a".into(),
        "arm" => "armeabi-v7a".into(),
        "x86_64" => "x86_64".into(),
        "x86" => "x86".into(),
        _ => "universal".into(),
    }
}

pub fn platform_ok(name: &str) -> bool {
    let n = name.to_lowercase();
    if NON_WIN_EXT.contains(&ext_of(&n).as_str()) {
        return false;
    }
    if NON_WIN.is_match(&n).unwrap_or(false) {
        return false;
    }
    let a = arch_of(&n);
    a.is_empty() || a == "x64"
}

pub fn is_noise(name: &str) -> bool {
    let n = name.to_lowercase();
    if NOISE_EXT.contains(&ext_of(&n).as_str()) {
        return true;
    }
    let toks: Vec<String> = tokens(&n).iter().map(|t| t.to_lowercase()).collect();
    if toks.iter().any(|t| NOISE_WORDS.contains(&t.as_str())) {
        return true;
    }
    RE_NOISE_SRC.is_match(&n).unwrap_or(false)
        && matches!(ext_of(&n).as_str(), "zip" | "tar" | "gz" | "xz" | "7z")
}

// ---------------------------------------------------------------------------
// 差异标签
// ---------------------------------------------------------------------------

const ABBR: [&str; 20] = [
    "msvc", "gnu", "sse", "avx", "arm", "x86", "x64", "cpu", "gpu", "sdk", "cli", "gui", "wpf",
    "uwp", "api", "ide", "dll", "app", "bin", "exe",
];
const ARCH_WORDS: [(&str, &str); 8] = [
    ("x64", "x64"),
    ("amd64", "x64"),
    ("win64", "x64"),
    ("64bit", "x64"),
    ("x86", "32 位"),
    ("win32", "32 位"),
    ("32bit", "32 位"),
    ("arm64", "ARM64"),
];

fn word_zh(w: &str) -> Option<&'static str> {
    Some(match w {
        "setup" | "installer" | "install" => "安装版",
        "portable" | "noinstall" => "便携版",
        "unsigned" => "未签名",
        "signed" => "已签名",
        "universal" => "通用",
        "full" => "完整版",
        "minimal" => "精简版",
        "lite" => "轻量版",
        "debug" => "调试版",
        "release" => "正式版",
        "beta" => "Beta",
        "user" => "用户版",
        "machine" | "system" => "全局版",
        "selfcontained" => "自带运行时",
        "standalone" => "独立版",
        "x86_64" | "x86-64" => "x86_64",
        "docs" | "doc" => "文档",
        "manual" => "手册",
        "offline" => "离线",
        _ => return None,
    })
}

const PHRASE_ZH: [(&str, &str); 16] = [
    ("user setup", "用户安装"),
    ("user installer", "用户安装"),
    ("machine setup", "全局安装"),
    ("system setup", "全局安装"),
    ("self contained", "自带运行时"),
    ("framework dependent", "需运行时"),
    ("no install", "免安装"),
    ("arm64 v8a", "ARM64"),
    ("armeabi v7a", "ARMv7"),
    ("group policy object files", "组策略文件"),
    ("full offline bundle", "离线完整包"),
    ("offline bundle", "离线包"),
    ("bundle", "整合包"),
    ("full offline", "离线完整版"),
    ("group policy objects", "组策略"),
    ("universal apk", "通用包"),
];

fn is_cjk(ch: char) -> bool {
    ('\u{4e00}'..='\u{9fff}').contains(&ch)
}

fn join_words(parts: &[String]) -> String {
    let mut out = String::new();
    for p in parts {
        if p.is_empty() {
            continue;
        }
        if !out.is_empty() {
            let last = out.chars().last().unwrap_or(' ');
            let first = p.chars().next().unwrap_or(' ');
            if !(is_cjk(last) && is_cjk(first)) {
                out.push(' ');
            }
        }
        out.push_str(p);
    }
    out
}

fn friendly(tokens: &[String]) -> String {
    let words: Vec<&String> = tokens.iter().filter(|t| !t.is_empty()).collect();
    if words.is_empty() {
        return String::new();
    }
    let whole = words
        .iter()
        .map(|w| w.to_lowercase())
        .collect::<Vec<_>>()
        .join(" ");
    if let Some((_, v)) = PHRASE_ZH.iter().find(|(k, _)| *k == whole) {
        return v.to_string();
    }
    let mut out: Vec<String> = Vec::new();
    let mut i = 0usize;
    while i < words.len() {
        let mut advanced = 0usize;
        for span in [3usize, 2] {
            if i + span <= words.len() {
                let key = words[i..i + span]
                    .iter()
                    .map(|w| w.to_lowercase())
                    .collect::<Vec<_>>()
                    .join(" ");
                if let Some((_, v)) = PHRASE_ZH.iter().find(|(k, _)| *k == key) {
                    out.push((*v).to_string());
                    advanced = span;
                    break;
                }
            }
        }
        if advanced > 0 {
            i += advanced;
            continue;
        }
        let w = words[i];
        let low = w.to_lowercase();
        if let Some((_, label)) = ARCH_WORDS.iter().find(|(k, _)| *k == low) {
            out.push((*label).to_string());
        } else if let Some(zh) = word_zh(&low) {
            out.push(zh.to_string());
        } else if ABBR.contains(&low.as_str()) {
            out.push(low.to_uppercase());
        } else if low.chars().count() <= 4 && low.chars().all(|c| c.is_ascii_alphabetic()) {
            out.push(low.to_uppercase());
        } else {
            out.push(match w.chars().next() {
                Some(c) => {
                    let mut s = c.to_uppercase().to_string();
                    s.push_str(&w[c.len_utf8()..]);
                    s
                }
                None => String::new(),
            });
        }
        i += 1;
    }
    join_words(&out)
}

fn norm_stem(name: &str) -> String {
    let mut stem = stem_of(name);
    stem = RE_CAMEL1.replace_all(&stem, "$1-$2").to_string();
    stem = RE_CAMEL2.replace_all(&stem, "$1-$2").to_string();
    stem = stem.to_lowercase();
    stem = RE_NORM_SEP.replace_all(&stem, "-").to_string();
    let stem = RE_MULTI_DASH.replace_all(&stem, "-").to_string();
    stem.trim_matches('-').to_string()
}

/// 所有名字共有的前缀，且必须停在词边界（- 之后）。
fn common_prefix(strings: &[String]) -> String {
    let ss: Vec<&String> = strings.iter().filter(|s| !s.is_empty()).collect();
    if ss.len() < 2 {
        return String::new();
    }
    let first: Vec<char> = ss[0].chars().collect();
    let mut len = first.len();
    for s in &ss[1..] {
        let cs: Vec<char> = s.chars().collect();
        let mut k = 0;
        while k < len && k < cs.len() && first[k] == cs[k] {
            k += 1;
        }
        len = k;
    }
    let p: String = first[..len].iter().collect();
    if ss.iter().any(|s| s.as_str() == p.as_str()) {
        return if p.ends_with('-') {
            p
        } else {
            format!("{}-", p)
        };
    }
    match p.rfind('-') {
        Some(cut) => p[..cut + 1].to_string(),
        None => String::new(),
    }
}

/// 所有名字共有的后缀（同样停在词边界），跳过已剥掉的前缀部分。
fn common_suffix(strings: &[String], prefix: &str) -> String {
    let ss: Vec<String> = strings
        .iter()
        .filter(|s| !s.is_empty())
        .map(|s| {
            if !prefix.is_empty() && s.starts_with(prefix) {
                s[prefix.len()..].to_string()
            } else {
                s.clone()
            }
        })
        .collect();
    if ss.len() < 2 || ss.iter().any(|s| s.is_empty()) {
        return String::new();
    }
    let rev: Vec<Vec<char>> = ss.iter().map(|s| s.chars().rev().collect()).collect();
    let first = &rev[0];
    let mut len = first.len();
    for r in &rev[1..] {
        let mut k = 0;
        while k < len && k < r.len() && first[k] == r[k] {
            k += 1;
        }
        len = k;
    }
    let p: Vec<char> = first[..len].to_vec();
    let idx = p.iter().position(|c| *c == '-');
    match idx {
        Some(i) => p[..=i].iter().rev().collect(),
        None => String::new(),
    }
}

fn label_of(core: &str) -> String {
    let mut stem = RE_NORM_X86.replace_all(core, "x86_64").to_string();
    stem = RE_VER_IN_NAME.replace_all(&stem, "-").to_string();
    stem = RE_BUILD_NO.replace_all(&stem, "-").to_string();
    stem = RE_YEAR.replace_all(&stem, "-").to_string();
    let toks: Vec<String> = stem
        .split('-')
        .filter(|t| !t.is_empty())
        .filter(|t| !t.chars().all(|c| c.is_ascii_digit()))
        .filter(|t| !RE_VER_TOKEN.is_match(t).unwrap_or(false))
        .map(|t| t.to_string())
        .collect();
    friendly(&toks)
}

/// 按格式分组，删掉「所有文件共有的部分」和版本号，只留差异词。
pub fn variant_labels(assets: &[Asset]) -> HashMap<String, String> {
    let mut out: HashMap<String, String> = HashMap::new();
    let items: Vec<&Asset> = assets.iter().filter(|a| !a.name.is_empty()).collect();
    if items.is_empty() {
        return out;
    }
    if items.len() == 1 {
        out.insert(items[0].name.clone(), "标准".to_string());
        return out;
    }
    let stems: HashMap<String, String> = items
        .iter()
        .map(|a| (a.name.clone(), norm_stem(&a.name)))
        .collect();
    let all_names: Vec<String> = stems.values().cloned().collect();
    let g_prefix = common_prefix(&all_names);
    let g_suffix = common_suffix(&all_names, &g_prefix);

    let mut groups: Vec<(String, Vec<&Asset>)> = Vec::new();
    for a in &items {
        let e = ext_of(&a.name);
        match groups.iter_mut().find(|(k, _)| *k == e) {
            Some((_, v)) => v.push(a),
            None => groups.push((e, vec![a])),
        }
    }

    for (_, group) in groups {
        let names: Vec<String> = group
            .iter()
            .map(|a| stems.get(&a.name).cloned().unwrap_or_default())
            .collect();
        let (mut prefix, mut suffix) = (g_prefix.clone(), g_suffix.clone());
        if names.len() >= 2 {
            let p = common_prefix(&names);
            let s = common_suffix(&names, &p);
            if p.chars().count() >= prefix.chars().count() {
                prefix = p;
                suffix = s;
            }
        }
        for a in group {
            let stem = stems.get(&a.name).cloned().unwrap_or_default();
            let mut core = if !prefix.is_empty() && stem.starts_with(&prefix) {
                stem[prefix.len()..].to_string()
            } else if prefix.ends_with('-') && stem == prefix[..prefix.len() - 1] {
                String::new()
            } else {
                stem.clone()
            };
            if !suffix.is_empty() && core.ends_with(&suffix) {
                core.truncate(core.len() - suffix.len());
            }
            let core = core.trim_matches('-').to_string();
            let lab = label_of(&core);
            out.insert(
                a.name.clone(),
                if lab.is_empty() { "标准".to_string() } else { lab },
            );
        }
    }

    // 同格式内标签撞车时补序号
    let mut seen: HashMap<(String, String), u32> = HashMap::new();
    for a in &items {
        let key = (
            out.get(&a.name).cloned().unwrap_or_default(),
            ext_of(&a.name),
        );
        let counter = seen.entry(key).or_insert(0);
        *counter += 1;
        if *counter > 1 {
            let cur = out.get(&a.name).cloned().unwrap_or_default();
            out.insert(a.name.clone(), format!("{} #{}", cur, *counter));
        }
    }
    out
}

pub fn variant_text(asset: &Asset) -> String {
    let ext = ext_of(&asset.name).to_uppercase();
    if asset.variant.trim().is_empty() {
        ext
    } else {
        format!("{} · {}", asset.variant.trim(), ext)
    }
}

// ---------------------------------------------------------------------------
// 过滤 / 打分
// ---------------------------------------------------------------------------

pub fn filter_assets<'a>(assets: &'a [Asset], formats: &[String], include_other_arch: bool) -> Vec<&'a Asset> {
    let known: Vec<&str> = BUILTIN_FORMATS.iter().map(|(k, _)| *k).collect();
    let fmt: Vec<String> = formats.iter().map(|f| f.to_lowercase()).collect();
    let every = fmt.is_empty() || fmt.iter().any(|f| f == "all");
    let mut pool: Vec<&Asset> = Vec::new();
    for a in assets {
        let e = ext_of(&a.name);
        if every
            || fmt.iter().any(|f| *f == e)
            || (fmt.iter().any(|f| f == "other") && !known.contains(&e.as_str()))
        {
            pool.push(a);
        }
    }
    let clean: Vec<&Asset> = pool.iter().copied().filter(|a| !is_noise(&a.name)).collect();
    let pool = if clean.is_empty() { pool } else { clean };
    if include_other_arch {
        return pool;
    }
    if IS_ANDROID {
        let pref = preferred_abi();
        let exact: Vec<&Asset> = pool
            .iter()
            .copied()
            .filter(|a| {
                let abi = abi_of(&a.name);
                abi.is_empty() || abi == "universal" || abi == pref
            })
            .collect();
        return if exact.is_empty() { pool } else { exact };
    }
    let out: Vec<&Asset> = pool
        .iter()
        .copied()
        .filter(|a| platform_ok(&a.name))
        .collect();
    if out.is_empty() {
        pool
    } else {
        out
    }
}

pub fn asset_score(a: &Asset) -> i32 {
    let n = a.name.to_lowercase();
    let e = ext_of(&n);
    let order = ["exe", "msi", "msix", "zip", "7z", "rar", "apk"];
    let mut s = match order.iter().position(|x| *x == e) {
        Some(i) => (10 - i as i32) * 10,
        None => 0,
    };
    if ["x64", "x86_64", "amd64", "win64", "64bit"].iter().any(|k| n.contains(k)) {
        s += 30;
    }
    if n.contains("arm64") || n.contains("aarch64") {
        s -= 20;
    }
    if RE_SCORE_X86.is_match(&n).unwrap_or(false) {
        s -= 10;
    }
    if ["setup", "installer", "install"].iter().any(|k| n.contains(k)) {
        s += 8;
    }
    if n.contains("msvc") {
        s += 6;
    }
    if n.contains("gnu") {
        s += 2;
    }
    if n.contains("portable") || n.contains("noinstall") {
        s += 4;
    }
    if n.contains("debug") || n.contains("symbols") {
        s -= 50;
    }
    s
}

pub fn preferred_asset<'a>(assets: &'a [Asset], formats: &[String]) -> Option<&'a Asset> {
    let pool = filter_assets(assets, formats, false);
    pool.into_iter().max_by_key(|a| asset_score(a))
}

/// 按文件大小决定实际切几段：上限 50，且每段至少 256 KB。
pub fn segment_count(total: u64, threads: u32) -> u32 {
    let n = threads.clamp(1, 50);
    if total == 0 {
        return 1;
    }
    let n = n.min(((total / (256 * 1024)).max(1)) as u32);
    if total < 512 * 1024 {
        1
    } else {
        n
    }
}

pub fn decode_url_component(s: &str) -> String {
    percent_encoding::percent_decode_str(s)
        .decode_utf8_lossy()
        .to_string()
}
