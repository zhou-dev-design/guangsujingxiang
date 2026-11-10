//! 光速镜像 · Rust + Tauri 桌面版（Windows 便携 exe）。

mod commands;
mod download;
mod github;
mod model;
mod net;
mod state;
mod textvariants;
mod util;

use std::sync::Arc;
use tauri::{Emitter, Manager};

fn signature(list: &[model::TaskSnapshot]) -> String {
    let mut s = String::new();
    for t in list {
        s.push_str(&t.id);
        s.push('|');
        s.push_str(&t.status);
        s.push('|');
        s.push_str(&(t.done / 4096).to_string());
        s.push('|');
        s.push_str(&((t.speed * 10.0) as i64).to_string());
        s.push(';');
    }
    s
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let core = Arc::new(state::Core::new(app.handle()));
            app.manage(core.clone());

            let handle = app.handle().clone();
            std::thread::spawn(move || {
                let mut last = String::new();
                loop {
                    std::thread::sleep(std::time::Duration::from_millis(400));
                    let core = handle.state::<Arc<state::Core>>();
                    let list = download::snapshots(&core);
                    let sig = signature(&list);
                    if !list.is_empty() && sig != last {
                        last = sig;
                        let _ = handle.emit("task:update", list);
                    }
                }
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![commands::call])
        .run(tauri::generate_context!())
        .expect("LightMirror 启动失败");
}
