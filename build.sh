#!/usr/bin/env bash
# 一键构建：改完代码直接出成品
#
#   ./build.sh         安卓 APK + Rust 桌面 exe 都构建
#   ./build.sh apk     只构建安卓 APK（原版 Java 工程，不需要 Gradle）
#   ./build.sh exe     只构建 Rust + Tauri 桌面 exe
#   ./build.sh py      只构建 Python 桌面 exe（需要 python + pyinstaller）
#
# 产物统一落到 release/：
#   LightMirror-<安卓 versionName>.apk
#   LightMirror-Desktop-<tauri version>-rust.exe
#   LightMirror-Desktop-<py APP_VERSION>.exe
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WHAT="${1:-all}"

build_apk() {
  echo "===== [1/2] 安卓 APK（原版 Java 工程）====="
  bash "$ROOT/app-source/build-apk.sh"
}

build_exe() {
  echo "===== [2/2] 桌面 EXE（Rust + Tauri）====="
  cd "$ROOT/rust"
  if [ ! -d node_modules ]; then
    echo "安装前端依赖…"
    npm install --no-audit --no-fund
  fi
  # --no-bundle：只要一个便携 exe，不打安装包
  npx tauri build --no-bundle --ci
  local v out
  v="$(node -p "require('./src-tauri/tauri.conf.json').version")"
  out="$ROOT/release/LightMirror-Desktop-$v-rust.exe"
  cp "src-tauri/target/release/lightmirror.exe" "$out"
  ls -lh "$out"
}

build_py() {
  echo "===== 桌面 EXE（Python + PySide6）====="
  cd "$ROOT/desktop"
  python -m pip install -q --upgrade pip
  python -m pip install -q -r requirements.txt pyinstaller
  python -m PyInstaller --noconfirm --clean --onefile --windowed --noupx \
    --name LightMirror \
    --icon assets/lightmirror.ico \
    --version-file version_info.txt \
    --distpath dist --workpath build --specpath . \
    lightmirror.py
  local v out
  v="$(grep -o 'APP_VERSION *= *"[^"]*"' lightmirror.py | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
  out="$ROOT/release/LightMirror-Desktop-$v.exe"
  cp "dist/LightMirror.exe" "$out"
  ls -lh "$out"
}

case "$WHAT" in
  apk) build_apk ;;
  exe|rust) build_exe ;;
  py|python) build_py ;;
  all) build_apk; build_exe ;;
  *)
    echo "用法: $0 [all|apk|exe|py]" >&2
    exit 1
    ;;
esac

echo
echo "===== 完成，产物在 release/ ====="
ls -lh "$ROOT/release"
