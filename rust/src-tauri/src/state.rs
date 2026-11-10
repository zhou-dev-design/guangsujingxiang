//! 配置 / 镜像 / 追踪 / 记录的持久化，以及运行日志。

use crate::model::*;
use crate::util::{now_ms, safe_name};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager};

pub struct Paths {
    pub config_dir: PathBuf,
    pub program_dir: PathBuf,
    pub default_root: String,
}

pub struct Core {
    pub data: Mutex<Data>,
    pub paths: Paths,
    pub state_path: PathBuf,
    pub log_path: Mutex<PathBuf>,
    pub tasks: Mutex<Vec<Arc<crate::download::Task>>>,
}

fn home_dir() -> PathBuf {
    std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."))
}

pub fn default_download_root(app: &AppHandle) -> String {
    if cfg!(target_os = "android") {
        let id = app.config().identifier.clone();
        let p = format!("/storage/emulated/0/Android/data/{}/files/Download", id);
        if fs::create_dir_all(&p).is_ok() {
            return p;
        }
        if let Ok(d) = app.path().app_data_dir() {
            let p = d.join("Download");
            let _ = fs::create_dir_all(&p);
            return p.to_string_lossy().to_string();
        }
        return p;
    }
    home_dir()
        .join("Downloads")
        .join("LightMirror")
        .to_string_lossy()
        .to_string()
}

impl Core {
    pub fn new(app: &AppHandle) -> Self {
        let config_dir = app
            .path()
            .app_config_dir()
            .unwrap_or_else(|_| home_dir().join(".lightmirror"));
        let _ = fs::create_dir_all(&config_dir);
        let state_path = config_dir.join("state.json");
        let program_dir = crate::util::program_dir();
        let default_root = default_download_root(app);

        let mut data = Data {
            config: Config::default(),
            mirrors: default_mirrors(),
            watch: vec![],
            history: vec![],
            version: 1,
        };
        data.config.download_root = default_root.clone();

        if let Ok(raw) = fs::read_to_string(&state_path) {
            if let Ok(loaded) = serde_json::from_str::<Data>(&raw) {
                data.config = loaded.config;
                data.mirrors = if loaded.mirrors.is_empty() {
                    default_mirrors()
                } else {
                    loaded.mirrors
                };
                data.watch = loaded.watch;
                data.history = loaded.history;
            }
        }
        // 老配置里没有下载根目录时补上
        if data.config.download_root.trim().is_empty() {
            data.config.download_root = default_root.clone();
        }
        // 老版本升级时补齐内置镜像（用户删掉的不会自动冒回来）
        let missing: Vec<Mirror> = default_mirrors()
            .into_iter()
            .filter(|d| !data.mirrors.iter().any(|m| m.name == d.name))
            .filter(|d| {
                d.name == "xget.xi-xu.me/gh"
                    || d.name == "ghfast.top"
                    || d.name == "GitHub 直连"
            })
            .collect();
        for m in missing {
            data.mirrors.push(m);
        }
        data.config.clamp();

        let core = Self {
            data: Mutex::new(data),
            paths: Paths {
                config_dir: config_dir.clone(),
                program_dir: program_dir.clone(),
                default_root,
            },
            state_path,
            log_path: Mutex::new(config_dir.join("lightmirror.log")),
            tasks: Mutex::new(vec![]),
        };
        core.pick_log_path();
        core.save();
        core
    }

    /// 优先程序目录（便携版），不可写时回落到配置目录。
    fn pick_log_path(&self) {
        let mut chosen = self.paths.config_dir.join("lightmirror.log");
        let cand = self.paths.program_dir.join("lightmirror.log");
        if fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&cand)
            .is_ok()
        {
            chosen = cand;
        }
        if let Ok(mut g) = self.log_path.lock() {
            *g = chosen;
        }
    }

    pub fn log(&self, tag: &str, text: &str) {
        let path = match self.log_path.lock() {
            Ok(g) => g.clone(),
            Err(_) => return,
        };
        let line = format!("[{}] [{}] {}\n", crate::util::fmt_ts(now_ms()), tag, text);
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        use std::io::Write;
        if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(&path) {
            let _ = f.write_all(line.as_bytes());
        }
    }

    pub fn save(&self) {
        let (json, limit) = {
            let data = match self.data.lock() {
                Ok(d) => d,
                Err(_) => return,
            };
            let mut clone = data.clone();
            let limit = clone.config.history_max as usize;
            if clone.history.len() > limit {
                clone.history.truncate(limit);
            }
            (serde_json::to_string_pretty(&clone).unwrap_or_default(), limit)
        };
        let _ = limit;
        let tmp = self.state_path.with_extension("json.tmp");
        if fs::write(&tmp, json.as_bytes()).is_ok() {
            let _ = fs::rename(&tmp, &self.state_path);
        }
    }

    pub fn config(&self) -> Config {
        self.data
            .lock()
            .map(|d| d.config.clone())
            .unwrap_or_default()
    }

    pub fn client(&self) -> Result<reqwest::Client, String> {
        let c = self.config();
        crate::net::build_client(&c.proxy, c.timeout as u64)
    }

    /// 下载用的客户端：没有总超时，避免大文件被拦腰切断。
    pub fn dl_client(&self) -> Result<reqwest::Client, String> {
        let c = self.config();
        crate::net::build_download_client(&c.proxy)
    }

    // ---- 追踪 ----

    pub fn watch_entry(&self, repo: &str) -> Option<Watch> {
        let data = self.data.lock().ok()?;
        data.watch
            .iter()
            .find(|w| w.repo.eq_ignore_ascii_case(repo))
            .cloned()
    }

    pub fn ensure_watch(&self, repo: &str) -> Watch {
        if let Some(w) = self.watch_entry(repo) {
            return w;
        }
        let w = Watch {
            repo: repo.to_string(),
            added: now_ms(),
            ..Default::default()
        };
        if let Ok(mut d) = self.data.lock() {
            d.watch.insert(0, w.clone());
        }
        self.save();
        w
    }

    pub fn update_watch<F: FnOnce(&mut Watch)>(&self, repo: &str, f: F) {
        if let Ok(mut d) = self.data.lock() {
            if let Some(w) = d.watch.iter_mut().find(|w| w.repo == repo) {
                f(w);
            }
        }
        self.save();
    }

    pub fn project_dir(&self, repo: &str) -> PathBuf {
        let (dir, root) = {
            let data = match self.data.lock() {
                Ok(d) => d,
                Err(_) => return PathBuf::from("."),
            };
            let w = data.watch.iter().find(|w| w.repo == repo);
            (
                w.map(|w| w.dir.clone()).unwrap_or_default(),
                data.config.download_root.clone(),
            )
        };
        if !dir.trim().is_empty() {
            return PathBuf::from(dir);
        }
        let folder = repo.replace('/', "__");
        Path::new(&root).join(safe_name(&folder, "github"))
    }

    pub fn project_rule(&self, repo: &str) -> String {
        let data = match self.data.lock() {
            Ok(d) => d,
            Err(_) => return "replace".into(),
        };
        let w = data.watch.iter().find(|w| w.repo == repo);
        let rule = w
            .and_then(|w| {
                if w.rule.is_empty() {
                    None
                } else {
                    Some(w.rule.clone())
                }
            })
            .unwrap_or_else(|| data.config.replace_rule.clone());
        if REPLACE_RULES.iter().any(|(k, _)| *k == rule) {
            rule
        } else {
            "replace".into()
        }
    }

    pub fn global_formats(&self) -> Vec<String> {
        let data = match self.data.lock() {
            Ok(d) => d,
            Err(_) => return default_formats(),
        };
        let f: Vec<String> = data
            .config
            .formats
            .iter()
            .map(|x| x.to_lowercase())
            .filter(|x| !x.trim().is_empty())
            .collect();
        if f.is_empty() {
            default_formats()
        } else {
            f
        }
    }

    pub fn format_menu(&self) -> Vec<(String, String)> {
        let mut out: Vec<(String, String)> = BUILTIN_FORMATS
            .iter()
            .map(|(k, l)| (k.to_string(), l.to_string()))
            .collect();
        let mut seen: Vec<String> = out.iter().map(|(k, _)| k.clone()).collect();
        let extra = self
            .data
            .lock()
            .map(|d| d.config.extra_formats.clone())
            .unwrap_or_default();
        for e in extra.into_iter().chain(self.global_formats()) {
            let e = e.to_lowercase().trim_start_matches('.').to_string();
            if !e.is_empty() && !seen.contains(&e) {
                out.push((e.clone(), e.to_uppercase()));
                seen.push(e);
            }
        }
        out
    }

    pub fn project_formats(&self, repo: &str) -> Vec<String> {
        let g = self.global_formats();
        let own = match self.watch_entry(repo) {
            Some(w) => w.formats,
            None => vec![],
        };
        if own.is_empty() {
            return g;
        }
        let keep: Vec<String> = own
            .into_iter()
            .map(|f| f.to_lowercase())
            .filter(|f| g.contains(f))
            .collect();
        if keep.is_empty() {
            g
        } else {
            keep
        }
    }

    /// 这个项目最新版里真实存在的格式（按设置里的顺序）。
    pub fn project_available_formats(&self, repo: &str) -> Vec<String> {
        let latest = self.watch_entry(repo).and_then(|w| w.latest);
        let mut have: Vec<String> = vec![];
        if let Some(rel) = latest {
            for a in rel.assets {
                if a.name.is_empty() || crate::textvariants::is_noise(&a.name) {
                    continue;
                }
                let e = crate::util::ext_of(&a.name);
                if !e.is_empty() && !have.contains(&e) {
                    have.push(e);
                }
            }
        }
        self.global_formats()
            .into_iter()
            .filter(|f| have.contains(f))
            .collect()
    }

    // ---- 记录 ----

    pub fn add_history(&self, rec: HistoryRec) {
        if let Ok(mut d) = self.data.lock() {
            d.history.insert(0, rec);
            let max = d.config.history_max as usize;
            if d.history.len() > max {
                d.history.truncate(max);
            }
        }
        self.save();
    }

    pub fn delete_history(&self, ids: &[String], remove_files: bool) {
        let mut gone: Vec<HistoryRec> = vec![];
        if let Ok(mut d) = self.data.lock() {
            let mut keep = Vec::with_capacity(d.history.len());
            for h in d.history.drain(..) {
                if ids.contains(&h.id) {
                    gone.push(h);
                } else {
                    keep.push(h);
                }
            }
            d.history = keep;
        }
        if remove_files {
            for h in gone {
                if !h.path.is_empty() {
                    let _ = fs::remove_file(&h.path);
                }
            }
        }
        self.save();
    }
}
