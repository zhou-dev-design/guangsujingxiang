@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

REM 用法：build.bat [all^|apk^|exe^|py]  不带参数 = all
set "WHAT=%~1"
if "%WHAT%"=="" set "WHAT=all"

where bash >nul 2>nul
if errorlevel 1 goto :nobash

if /i "%WHAT%"=="all" (
  if "%ANDROID_HOME%"=="" (
    echo 未检测到 ANDROID_HOME，本次只构建桌面 exe。
    echo 需要 APK 时先装 Android SDK，并设置 ANDROID_HOME。
    bash build.sh exe
  ) else (
    bash build.sh all
  )
) else (
  bash build.sh %WHAT%
)
exit /b %errorlevel%

:nobash
if /i not "%WHAT%"=="all" if /i not "%WHAT%"=="exe" (
  echo 没有找到 bash（Git Bash），只能构建桌面 exe。
  echo 需要 APK 请安装 Git for Windows 后重试。
)
echo 直接用 npm 构建桌面 exe…
cd /d "%~dp0rust"
if not exist node_modules call npm install --no-audit --no-fund
call npx tauri build --no-bundle --ci
if errorlevel 1 exit /b 1
for /f "usebackq delims=" %%v in (`node -p "require('./src-tauri/tauri.conf.json').version"`) do set "V=%%v"
copy /y "src-tauri\target\release\lightmirror.exe" "..\release\LightMirror-Desktop-%V%-rust.exe" >nul
echo 完成：release\LightMirror-Desktop-%V%-rust.exe
exit /b 0
