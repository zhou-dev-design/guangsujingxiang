//! 前端唯一的 IPC 入口：`call(action, args)`。
//!
//! 用单入口派发而不是几十个 command，前端只需要记住动作名，
//! 出错信息也统一成字符串返回。

use crate::download as dl;
use crate::github;
use crate::model::*;
use crate::net;
use crate::state::Core;
use crate::textvariants;
use crate::util::{ext_of, now_ms, safe_name};
use serde_json::{json, Value};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, State};

static SPEED_GEN: AtomicU64 = AtomicU64::new(0);

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string()
}
fn b(v: &Value, k: &str) -> bool {
    v.get(k).and_then(|x| x.as_bool()).unwrap_or(false)
}
fn n(v: &Value, k: &str) -> i64 {
    v.get(k)
        .and_then(|x| x.as_i64())
        .or_else(|| v.get(k).and_then(|x| x.as_f64()).map(|f| f as i64))
        .unwrap_or(0)
}

fn err<T>(msg: impl Into<String>) -> Result<T, String> {
    Err(msg.into())
}

#[tauri::command]
pub async fn call(
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    action: String,
    args: Value,
) -> Result<Value, String> {
    let core = core.inner().clone();
    match action.as_str() {
        "bootstrap" => bootstrap(&app, &core, &args),
        "config.save" => config_save(&app, &core, args),
        "config.reset_formats" => config_reset_formats(&core),
        "link.parse" => link_parse(args),
        "speed.start" => speed_start(&app, &core, args).await,
        "speed.cancel" => {
            SPEED_GEN.fetch_add(1, Ordering::Relaxed);
            Ok(json!({"ok": true}))
        }
        "download.start" => download_start(&app, &core, args),
        "task.pause" => task_action(&app, &core, args, "pause"),
        "task.resume" => task_action(&app, &core, args, "resume"),
        "task.cancel" => task_action(&app, &core, args, "cancel"),
        "task.clear" => {
            dl::clear_finished(&core);
            dl::emit_tasks(&app, &core);
            Ok(json!({"ok": true}))
        }
        "task.cancel_all" => {
            dl::cancel_all(&core, &app);
            Ok(json!({"ok": true}))
        }
        "task.snapshot" => Ok(json!(dl::snapshots(&core))),
        "open.path" => open_path(&app, args),
        "reveal.path" => reveal_path(&app, args),
        "dir.pick" => dir_pick(&app).await,
        "watch.add" => watch_add(&app, &core, args).await,
        "watch.check" => watch_check(&app, &core, args).await,
        "watch.check_all" => watch_check_all(&app, &core, args).await,
        "watch.remove" => watch_remove(&core, args),
        "watch.update" => watch_update(&core, args),
        "releases.list" => releases_list(&core, args).await,
        "history.delete" => history_delete(&core, args),
        "history.clear" => history_clear(&core, args),
        "mirror.toggle" => mirror_toggle(&core, args),
        "mirror.move" => mirror_move(&core, args),
        "mirror.delete" => mirror_delete(&core, args),
        "mirror.restore" => mirror_restore(&core),
        "mirror.add" => mirror_add(&core, args),
        "log.text" => log_text(&core, args),
        "log.clear" => log_clear(&core),
        "app.info" => Ok(json!({
            "version": APP_VERSION,
            "platform": if IS_ANDROID { "android" } else { "windows" },
            "abi": textvariants::preferred_abi(),
            "config_dir": core.paths.config_dir.to_string_lossy(),
        })),
        other => err(format!("未知操作：{}", other)),
    }
}

fn bootstrap(app: &AppHandle, core: &Arc<Core>, args: &Value) -> Result<Value, String> {
    let _ = app;
    if let Some(g) = args.get("globals") {
        core.log(
            "启动",
            &format!(
                "界面已连接 · __TAURI__={} · __TAURI_INTERNALS__={} · {}",
                g.get("tauri").and_then(|v| v.as_bool()).unwrap_or(false),
                g.get("internals").and_then(|v| v.as_bool()).unwrap_or(false),
                APP_VERSION
            ),
        );
    } else {
        core.log("启动", &format!("界面已连接 · {}", APP_VERSION));
    }
    let data = core
        .data
        .lock()
        .map_err(|_| "状态锁异常".to_string())?
        .clone();
    let boot = Bootstrap {
        version: APP_VERSION.to_string(),
        build: BUILD_TAG.to_string(),
        platform: if IS_ANDROID { "android" } else { "windows" }.to_string(),
        download_root: data.config.download_root.clone(),
        config_path: core.state_path.to_string_lossy().to_string(),
        log_path: core
            .log_path
            .lock()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default(),
        format_menu: core.format_menu(),
        rules: REPLACE_RULES
            .iter()
            .map(|(k, l)| (k.to_string(), l.to_string()))
            .collect(),
        default_formats: default_formats(),
        builtin: BUILTIN_FORMATS
            .iter()
            .map(|(k, l)| (k.to_string(), l.to_string()))
            .collect(),
        config: data.config,
        mirrors: data.mirrors,
        watch: data.watch,
        history: data.history,
    };
    serde_json::to_value(boot).map_err(|e| e.to_string())
}

fn config_save(app: &AppHandle, core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let mut cfg: Config = serde_json::from_value(args.get("config").cloned().unwrap_or(json!({})))
        .map_err(|e| format!("配置格式不对：{}", e))?;
    cfg.clamp();
    {
        let mut data = core.data.lock().map_err(|_| "状态锁异常".to_string())?;
        data.config = cfg.clone();
    }
    core.save();
    let _ = app.emit("config:update", serde_json::to_value(&cfg).unwrap_or(json!({})));
    Ok(json!(cfg))
}

fn config_reset_formats(core: &Arc<Core>) -> Result<Value, String> {
    {
        let mut data = core.data.lock().map_err(|_| "状态锁异常".to_string())?;
        data.config.formats = default_formats();
        data.config.extra_formats = vec![];
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn link_parse(args: Value) -> Result<Value, String> {
    Ok(match github::parse_github(&s(&args, "text")) {
        Some(p) => serde_json::to_value(p).unwrap_or(json!(null)),
        None => json!(null),
    })
}

async fn speed_start(app: &AppHandle, core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let url = s(&args, "url");
    if url.is_empty() {
        return err("缺少链接");
    }
    let gen = SPEED_GEN.fetch_add(1, Ordering::Relaxed) + 1;
    let cfg = core.config();
    let mirrors: Vec<Mirror> = {
        let data = core.data.lock().map_err(|_| "状态锁异常".to_string())?;
        data.mirrors.iter().filter(|m| m.enabled).cloned().collect()
    };
    let client = core.client()?;
    core.log(
        "测速",
        &format!(
            "开始 · {} · {} 个镜像 · 分片 {} KB · 超时 {}s",
            url,
            mirrors.len(),
            cfg.probe_kb,
            cfg.timeout
        ),
    );
    let mut ok_n = 0usize;
    let mut best: Option<(String, f64)> = None;
    for m in mirrors.iter() {
        if SPEED_GEN.load(Ordering::Relaxed) != gen {
            core.log("测速", "已取消");
            break;
        }
        let r = net::probe_mirror(&client, m, &url, cfg.probe_kb, cfg.timeout as u64).await;
        if let Ok(mut data) = core.data.lock() {
            if let Some(t) = data.mirrors.iter_mut().find(|x| x.name == m.name) {
                net::apply_probe(t, &r);
            }
        }
        if r.ok {
            ok_n += 1;
            if best.as_ref().map(|(_, sp)| r.speed > *sp).unwrap_or(true) {
                best = Some((m.name.clone(), r.speed));
            }
            core.log(
                "测速",
                &format!("成功 · {} · {} ms · {:.2} MB/s", m.name, r.latency, r.speed),
            );
        } else {
            core.log("测速", &format!("失败 · {}", m.name));
        }
        let _ = app.emit(
            "speed:result",
            json!({
                "name": m.name,
                "ok": r.ok,
                "latency": r.latency,
                "speed": r.speed,
            }),
        );
    }
    core.save();
    let tail = match &best {
        Some((name, sp)) => format!(" · 最快 {} {:.2} MB/s", name, sp),
        None => " · 全部失败".to_string(),
    };
    core.log(
        "测速",
        &format!("结束 · 成功 {}/{}{}", ok_n, mirrors.len(), tail),
    );
    let payload = json!({
        "ok": ok_n,
        "total": mirrors.len(),
        "best": best.as_ref().map(|(a, _)| a.clone()),
        "bestSpeed": best.map(|(_, s)| s).unwrap_or(0.0),
    });
    let _ = app.emit("speed:done", payload.clone());
    Ok(payload)
}

fn download_start(app: &AppHandle, core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let items = args
        .get("items")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let mut started: Vec<String> = vec![];
    let mut skipped: Vec<String> = vec![];
    for it in items {
        let url = s(&it, "url");
        if url.is_empty() {
            continue;
        }
        let repo = s(&it, "repo");
        let tag = s(&it, "tag");
        let mut filename = s(&it, "filename");
        if filename.trim().is_empty() {
            filename = url
                .split(['?', '#'])
                .next()
                .unwrap_or("")
                .rsplit('/')
                .next()
                .map(|x| crate::textvariants::decode_url_component(x))
                .unwrap_or_default();
        }
        let filename = safe_name(&filename, "download.bin");
        let dir = if repo.is_empty() {
            PathBuf::from(core.config().download_root)
        } else {
            core.project_dir(&repo)
        };
        let _ = fs::create_dir_all(&dir);
        let rule = if repo.is_empty() {
            core.config().replace_rule
        } else {
            core.project_rule(&repo)
        };
        let (target, skip) = dl::resolve_target(&dir, &filename, &rule);
        if skip {
            skipped.push(filename);
            continue;
        }
        let size = it.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
        let task = dl::Task::new(
            dl::make_task_id(),
            url,
            filename,
            target,
            repo,
            tag,
            size,
        );
        if let Ok(mut t) = core.tasks.lock() {
            t.insert(0, task.clone());
        }
        started.push(task.id.clone());
    }
    dl::emit_tasks(app, core);
    dl::pump(core, app);
    Ok(json!({"started": started, "skipped": skipped}))
}

fn task_action(app: &AppHandle, core: &Arc<Core>, args: Value, action: &str) -> Result<Value, String> {
    let id = s(&args, "id");
    let task = core
        .tasks
        .lock()
        .map_err(|_| "任务锁异常".to_string())?
        .iter()
        .find(|t| t.id == id)
        .cloned()
        .ok_or_else(|| "任务不存在".to_string())?;
    match action {
        "pause" => dl::pause_task(&task),
        "resume" => dl::resume_task(core, app, &task),
        "cancel" => dl::cancel_task(core, &task),
        _ => {}
    }
    dl::emit_tasks(app, core);
    Ok(json!({"ok": true}))
}

fn open_path(app: &AppHandle, args: Value) -> Result<Value, String> {
    use tauri_plugin_opener::OpenerExt;
    let path = s(&args, "path");
    if !Path::new(&path).exists() {
        return err("文件不存在，可能已被移动或删除");
    }
    app.opener()
        .open_path(path.clone(), None::<&str>)
        .map_err(|e| format!("打不开：{}", e))?;
    Ok(json!({"ok": true}))
}

fn reveal_path(app: &AppHandle, args: Value) -> Result<Value, String> {
    use tauri_plugin_opener::OpenerExt;
    let path = s(&args, "path");
    if path.is_empty() {
        return err("路径为空");
    }
    let target = PathBuf::from(&path);
    if target.is_dir() {
        return app
            .opener()
            .open_path(path, None::<&str>)
            .map_err(|e| format!("打不开：{}", e))
            .map(|_| json!({"ok": true}));
    }
    if !target.exists() {
        if let Some(parent) = target.parent() {
            return app
                .opener()
                .open_path(parent.to_string_lossy().to_string(), None::<&str>)
                .map_err(|e| format!("打不开：{}", e))
                .map(|_| json!({"ok": true}));
        }
        return err("文件不存在");
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app.opener().reveal_item_in_dir(&path);
    }
    #[cfg(target_os = "android")]
    {
        // 安卓没有「在文件夹中显示」，直接交给系统打开这个文件
        let _ = app
            .opener()
            .open_path(path.clone(), None::<&str>)
            .map_err(|e| format!("打不开：{}", e));
    }
    Ok(json!({"ok": true}))
}

async fn dir_pick(app: &AppHandle) -> Result<Value, String> {
    #[cfg(target_os = "android")]
    {
        // 安卓没有目录选择器，交给前端手动填路径
        let _ = app;
        Ok(json!({"path": null, "unsupported": true}))
    }
    #[cfg(not(target_os = "android"))]
    {
        use tauri_plugin_dialog::DialogExt;
        let (tx, rx) = tokio::sync::oneshot::channel();
        app.dialog().file().pick_folder(move |p| {
            let _ = tx.send(p);
        });
        match tokio::time::timeout(std::time::Duration::from_secs(600), rx).await {
            Ok(Ok(Some(p))) => Ok(json!({"path": p.to_string()})),
            _ => Ok(json!({"path": null})),
        }
    }
}

async fn watch_add(app: &AppHandle, core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let repo = s(&args, "repo");
    if repo.is_empty() {
        return err("缺少仓库");
    }
    core.ensure_watch(&repo);
    let w = watch_check(app, core, json!({"repo": repo, "force": true})).await?;
    Ok(w)
}

async fn watch_check(app: &AppHandle, core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let repo = s(&args, "repo");
    if repo.is_empty() {
        return err("缺少仓库");
    }
    let force = b(&args, "force");
    let cfg0 = core.config();
    let ttl = if cfg0.cache_on {
        (cfg0.cache_ttl as u64) * 60
    } else {
        0
    };
    if !force {
        if let Some(w) = core.watch_entry(&repo) {
            let cfg = core.config();
            let fresh = now_ms() - w.checked < (cfg.cache_ttl as i64) * 60_000;
            if fresh && w.latest.is_some() && cfg.cache_on {
                return Ok(json!(w));
            }
        }
    }
    let cfg = core.config();
    let client = core.client()?;
    let list = github::fetch_releases(&client, &repo, 1, 1, &cfg.github_token, ttl)
        .await
        .map_err(|e| e.msg)?;
    let latest = list.into_iter().next();
    core.ensure_watch(&repo);
    core.update_watch(&repo, |w| {
        w.latest = latest.clone();
        w.checked = now_ms();
        if let Some(r) = &latest {
            w.name = r.name.clone();
        }
    });
    let w = core.watch_entry(&repo).unwrap_or_default();
    let _ = app.emit("watch:update", json!({"repo": repo}));
    Ok(json!(w))
}

async fn watch_check_all(app: &AppHandle, core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let repos: Vec<String> = {
        let data = core.data.lock().map_err(|_| "状态锁异常".to_string())?;
        data.watch.iter().map(|w| w.repo.clone()).collect()
    };
    let force = args.get("force").and_then(|v| v.as_bool()).unwrap_or(true);
    for repo in repos {
        let _ = watch_check(app, core, json!({"repo": repo, "force": force})).await;
    }
    Ok(json!({"ok": true}))
}

fn watch_remove(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let repo = s(&args, "repo");
    if let Ok(mut d) = core.data.lock() {
        d.watch.retain(|w| w.repo != repo);
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn watch_update(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let repo = s(&args, "repo");
    let patch = args.get("patch").cloned().unwrap_or(json!({}));
    core.update_watch(&repo, |w| {
        if let Some(v) = patch.get("note").and_then(|v| v.as_str()) {
            w.note = v.to_string();
        }
        if let Some(v) = patch.get("dir").and_then(|v| v.as_str()) {
            w.dir = v.to_string();
        }
        if let Some(v) = patch.get("rule").and_then(|v| v.as_str()) {
            w.rule = v.to_string();
        }
        if let Some(v) = patch.get("tag").and_then(|v| v.as_str()) {
            w.tag = v.to_string();
        }
        if let Some(v) = patch.get("formats").and_then(|v| v.as_array()) {
            w.formats = v
                .iter()
                .filter_map(|x| x.as_str().map(|s| s.to_lowercase()))
                .collect();
        }
        if let Some(v) = patch.get("variants").and_then(|v| v.as_array()) {
            w.variants = v
                .iter()
                .filter_map(|x| x.as_str().map(|s| s.to_string()))
                .collect();
        }
        if let Some(v) = patch.get("tags_open").and_then(|v| v.as_bool()) {
            w.tags_open = v;
        }
        if let Some(v) = patch.get("last_vkey").and_then(|v| v.as_str()) {
            w.last_vkey = v.to_string();
        }
    });
    Ok(json!({"ok": true}))
}

async fn releases_list(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let repo = s(&args, "repo");
    let page = n(&args, "page").max(1) as u32;
    let per_page = args
        .get("per_page")
        .and_then(|v| v.as_u64())
        .unwrap_or(20) as u32;
    let cfg = core.config();
    let client = core.client()?;
    let ttl = if cfg.cache_on {
        (cfg.cache_ttl as u64) * 60
    } else {
        0
    };
    let mut list = github::fetch_releases(&client, &repo, page, per_page, &cfg.github_token, ttl)
        .await
        .map_err(|e| e.msg)?;
    github::sort_releases(&mut list);
    Ok(json!(list))
}

fn history_delete(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let ids: Vec<String> = args
        .get("ids")
        .and_then(|v| v.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default();
    let remove_files = b(&args, "remove_files");
    core.delete_history(&ids, remove_files);
    Ok(json!({"ok": true}))
}

fn history_clear(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let ids: Vec<String> = {
        let data = core.data.lock().map_err(|_| "状态锁异常".to_string())?;
        data.history.iter().map(|h| h.id.clone()).collect()
    };
    let remove_files = b(&args, "remove_files");
    core.delete_history(&ids, remove_files);
    Ok(json!({"ok": true}))
}

fn mirror_toggle(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let idx = n(&args, "index") as usize;
    let on = b(&args, "on");
    if let Ok(mut d) = core.data.lock() {
        if let Some(m) = d.mirrors.get_mut(idx) {
            m.enabled = on;
        }
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn mirror_move(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let idx = n(&args, "index") as usize;
    let delta = n(&args, "delta");
    if let Ok(mut d) = core.data.lock() {
        let len = d.mirrors.len();
        if idx < len {
            let target = (idx as i64 + delta).clamp(0, len as i64 - 1) as usize;
            if target != idx {
                let item = d.mirrors.remove(idx);
                d.mirrors.insert(target, item);
            }
        }
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn mirror_delete(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let idx = n(&args, "index") as usize;
    if let Ok(mut d) = core.data.lock() {
        if idx < d.mirrors.len() {
            d.mirrors.remove(idx);
        }
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn mirror_restore(core: &Arc<Core>) -> Result<Value, String> {
    if let Ok(mut d) = core.data.lock() {
        for m in default_mirrors() {
            if !d.mirrors.iter().any(|x| x.name == m.name) {
                d.mirrors.push(m);
            }
        }
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn mirror_add(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let name = s(&args, "name").trim().to_string();
    let base = s(&args, "base").trim().to_string();
    let mode = s(&args, "mode");
    if name.is_empty() || base.is_empty() {
        return err("名字和地址都要填");
    }
    if !base.starts_with("http://") && !base.starts_with("https://") {
        return err("地址要以 http:// 或 https:// 开头");
    }
    let mode = if mode == "path" { "path" } else { "prefix" };
    if let Ok(mut d) = core.data.lock() {
        if d.mirrors.iter().any(|m| m.name == name) {
            return err("同名镜像已经存在");
        }
        d.mirrors.push(Mirror {
            name,
            base,
            mode: mode.into(),
            ..Default::default()
        });
    }
    core.save();
    Ok(json!({"ok": true}))
}

fn log_text(core: &Arc<Core>, args: Value) -> Result<Value, String> {
    let limit = args.get("limit").and_then(|v| v.as_u64()).unwrap_or(400) as usize;
    let path = core
        .log_path
        .lock()
        .map(|p| p.clone())
        .unwrap_or_else(|_| core.paths.config_dir.join("lightmirror.log"));
    let text = fs::read_to_string(&path).unwrap_or_default();
    let lines: Vec<&str> = text.lines().collect();
    let start = lines.len().saturating_sub(limit);
    Ok(json!({
        "path": path.to_string_lossy(),
        "text": lines[start..].join("\n"),
    }))
}

fn log_clear(core: &Arc<Core>) -> Result<Value, String> {
    let path = core
        .log_path
        .lock()
        .map(|p| p.clone())
        .unwrap_or_else(|_| core.paths.config_dir.join("lightmirror.log"));
    let _ = fs::write(&path, "");
    Ok(json!({"ok": true}))
}

/// 给前端用的附件下载请求构造（保持和原版「下载最新」一致的选择逻辑）。
#[allow(dead_code)]
pub fn pick_asset(assets: &[Asset], formats: &[String]) -> Option<Asset> {
    textvariants::preferred_asset(assets, formats).cloned()
}

#[allow(dead_code)]
pub fn guess_ext(name: &str) -> String {
    ext_of(name)
}
