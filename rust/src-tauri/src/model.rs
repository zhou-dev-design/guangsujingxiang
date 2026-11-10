//! 数据模型：配置、镜像、追踪项目、Release、下载记录。

use serde::{Deserialize, Serialize};

pub const APP_NAME: &str = "光速镜像";
pub const APP_NAME_EN: &str = "LightMirror";
pub const APP_VERSION: &str = "3.0.0";
pub const BUILD_TAG: &str = env!("LM_BUILD");
pub const UA: &str = "LightMirror/3.0 (+https://github.com)";

pub const IS_ANDROID: bool = cfg!(target_os = "android");

pub const BUILTIN_FORMATS: [(&str, &str); 6] = [
    ("exe", "EXE"),
    ("msi", "MSI"),
    ("zip", "ZIP"),
    ("rar", "RAR"),
    ("pdf", "PDF"),
    ("7z", "7Z"),
];

pub const REPLACE_RULES: [(&str, &str); 4] = [
    ("replace", "直接替换"),
    ("rename", "自动改名"),
    ("skip", "跳过不下载"),
    ("ask", "每次询问"),
];

/// Windows 桌面端认的扩展名（安卓端另有一套）。
pub const WIN_EXTS: [&str; 8] = ["exe", "msi", "msix", "appx", "zip", "rar", "7z", "pdf"];

pub fn default_formats() -> Vec<String> {
    if IS_ANDROID {
        vec!["apk".into(), "zip".into(), "rar".into(), "pdf".into()]
    } else {
        vec!["exe".into(), "msi".into(), "zip".into(), "rar".into(), "pdf".into()]
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub download_root: String,
    pub concurrency: u32,
    pub threads: u32,
    pub segments: bool,
    pub failover: bool,
    pub failover_max: u32,
    pub resume: bool,
    pub formats: Vec<String>,
    pub extra_formats: Vec<String>,
    pub replace_rule: String,
    /// 0 = 单个下载，1 = 批量勾选
    pub version_mode: u32,
    pub watch_sort: String,
    pub theme: String,
    pub probe_kb: u32,
    pub timeout: u32,
    pub history_max: u32,
    pub github_token: String,
    pub proxy: String,
    pub cache_ttl: u32,
    /// 是否复用测速 / 接口结果（关掉就每次都重查）
    pub cache_on: bool,
    pub last_repo: String,
    /// 在手机上额外存一份「显示主题」选择；auto 表示跟随屏幕
    pub theme_mode: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            download_root: String::new(),
            concurrency: if IS_ANDROID { 3 } else { 6 },
            threads: if IS_ANDROID { 10 } else { 16 },
            segments: true,
            failover: true,
            failover_max: if IS_ANDROID { 3 } else { 5 },
            resume: true,
            formats: default_formats(),
            extra_formats: vec![],
            replace_rule: "replace".into(),
            version_mode: 0,
            watch_sort: "updated".into(),
            theme: "dark".into(),
            probe_kb: 256,
            timeout: 8,
            history_max: 200,
            github_token: String::new(),
            proxy: String::new(),
            cache_ttl: 10,
            cache_on: true,
            last_repo: String::new(),
            theme_mode: "auto".into(),
        }
    }
}

impl Config {
    pub fn clamp(&mut self) {
        self.concurrency = self.concurrency.clamp(1, 50);
        self.threads = self.threads.clamp(1, 50);
        self.failover_max = self.failover_max.clamp(1, 10);
        self.probe_kb = self.probe_kb.clamp(32, 4096);
        self.timeout = self.timeout.clamp(3, 60);
        self.history_max = self.history_max.clamp(5, 200);
        self.cache_ttl = self.cache_ttl.clamp(1, 720);
        if !matches!(self.watch_sort.as_str(), "updated" | "note" | "name") {
            self.watch_sort = "updated".into();
        }
        if !matches!(self.theme.as_str(), "dark" | "light" | "mono") {
            self.theme = "dark".into();
        }
        if !matches!(self.replace_rule.as_str(), "replace" | "rename" | "skip" | "ask") {
            self.replace_rule = "replace".into();
        }
        if self.formats.is_empty() {
            self.formats = default_formats();
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Mirror {
    pub name: String,
    pub base: String,
    /// prefix = 拼完整链接；path = 拼 GitHub 路径；direct = 直连
    pub mode: String,
    pub enabled: bool,
    pub latency: i64,
    pub speed: f64,
    pub fail: u32,
    pub checked: i64,
}

impl Default for Mirror {
    fn default() -> Self {
        Self {
            name: String::new(),
            base: String::new(),
            mode: "prefix".into(),
            enabled: true,
            latency: 0,
            speed: 0.0,
            fail: 0,
            checked: 0,
        }
    }
}

pub fn default_mirrors() -> Vec<Mirror> {
    let raw: [(&str, &str, &str); 10] = [
        ("ghfast.top", "https://ghfast.top/", "prefix"),
        ("xget.xi-xu.me/gh", "https://xget.xi-xu.me/gh/", "path"),
        ("gh-proxy.com", "https://gh-proxy.com/", "prefix"),
        ("ghproxy.net", "https://ghproxy.net/", "prefix"),
        ("hub.gitmirror.com", "https://hub.gitmirror.com/", "prefix"),
        ("gh.llkk.cc", "https://gh.llkk.cc/", "prefix"),
        ("ghproxy.cn", "https://ghproxy.cn/", "prefix"),
        ("ghps.cc", "https://ghps.cc/", "prefix"),
        ("github.moeyy.xyz", "https://github.moeyy.xyz/", "prefix"),
        ("GitHub 直连", "", "direct"),
    ];
    raw.iter()
        .map(|(n, b, m)| Mirror {
            name: n.to_string(),
            base: b.to_string(),
            mode: m.to_string(),
            ..Default::default()
        })
        .collect()
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Asset {
    pub name: String,
    pub size: u64,
    pub updated: String,
    pub url: String,
    pub downloads: u64,
    pub arch: String,
    pub vkey: String,
    pub variant: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Release {
    pub tag: String,
    pub name: String,
    pub body: String,
    pub published: String,
    pub prerelease: bool,
    pub draft: bool,
    pub html_url: String,
    pub assets: Vec<Asset>,
    pub newest: String,
    pub newest_ms: i64,
    pub size: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Watch {
    pub repo: String,
    pub tag: String,
    pub name: String,
    pub note: String,
    pub dir: String,
    pub rule: String,
    /// 2.4 起是多选标签
    pub variants: Vec<String>,
    pub last_vkey: String,
    pub formats: Vec<String>,
    pub tags_open: bool,
    pub added: i64,
    pub latest: Option<Release>,
    pub checked: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct HistoryRec {
    pub id: String,
    pub name: String,
    pub path: String,
    pub size: u64,
    pub ts: i64,
    pub mirror: String,
    pub repo: String,
    pub status: String,
    pub error: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Data {
    pub config: Config,
    pub mirrors: Vec<Mirror>,
    pub watch: Vec<Watch>,
    pub history: Vec<HistoryRec>,
    pub version: u32,
}

#[derive(Debug, Clone, Serialize)]
pub struct Parsed {
    /// file / repo / archive / raw / direct
    pub kind: String,
    pub owner: String,
    pub repo: String,
    pub filename: String,
    pub url: String,
    pub tag: String,
    pub slug: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct TaskSnapshot {
    pub id: String,
    pub filename: String,
    pub path: String,
    pub repo: String,
    pub tag: String,
    pub status: String,
    pub done: u64,
    pub total: u64,
    pub speed: f64,
    pub error: String,
    pub mirror: String,
    pub segments: u32,
    pub elapsed: f64,
    pub eta: f64,
    pub exists: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct Bootstrap {
    pub version: String,
    pub build: String,
    pub platform: String,
    pub config: Config,
    pub mirrors: Vec<Mirror>,
    pub watch: Vec<Watch>,
    pub history: Vec<HistoryRec>,
    pub download_root: String,
    pub config_path: String,
    pub log_path: String,
    pub format_menu: Vec<(String, String)>,
    pub rules: Vec<(String, String)>,
    pub default_formats: Vec<String>,
    pub builtin: Vec<(String, String)>,
}
