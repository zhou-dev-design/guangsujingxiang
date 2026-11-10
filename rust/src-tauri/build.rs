use std::process::Command;

fn main() {
    // 把 git 短哈希编进二进制，界面上能一眼看出用户在跑哪一版
    let sha = Command::new("git")
        .args(["rev-parse", "--short", "HEAD"])
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "local".to_string());
    println!("cargo:rustc-env=LM_BUILD={}", sha);
    tauri_build::build()
}
