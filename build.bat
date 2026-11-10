@echo off
REM LightMirror build script (Windows)
REM Usage: build.bat [all^|apk^|exe^|py]   default = all
setlocal
cd /d "%~dp0"

set "WHAT=%~1"
if "%WHAT%"=="" set "WHAT=all"

REM Prefer Git for Windows bash. A bare "where bash" may hit WSL's bash,
REM which cannot resolve Windows paths and the build would fail.
set "GITBASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GITBASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "GITBASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not defined GITBASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "GITBASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined GITBASH (
  where bash >nul 2>nul
  if not errorlevel 1 set "GITBASH=bash"
)
if not defined GITBASH goto :nobash

if /i "%WHAT%"=="all" (
  if "%ANDROID_HOME%"=="" (
    echo [i] ANDROID_HOME not set - building desktop exe only.
    echo [i] Set ANDROID_HOME to your Android SDK if you also need the APK.
    "%GITBASH%" build.sh exe
  ) else (
    "%GITBASH%" build.sh all
  )
) else (
  "%GITBASH%" build.sh %WHAT%
)
exit /b %errorlevel%

:nobash
if /i not "%WHAT%"=="all" if /i not "%WHAT%"=="exe" (
  echo [!] Git Bash not found - can only build the desktop exe.
  echo [!] Install Git for Windows to build the APK as well.
)
echo [i] Building desktop exe with npm ...
cd /d "%~dp0rust"
if not exist node_modules call npm install --no-audit --no-fund
call npx tauri build --no-bundle --ci
if errorlevel 1 exit /b 1
for /f "usebackq delims=" %%v in (`node -p "require('./src-tauri/tauri.conf.json').version"`) do set "V=%%v"
copy /y "src-tauri\target\release\lightmirror.exe" "..\release\LightMirror-Desktop-%V%-rust.exe" >nul
echo [ok] release\LightMirror-Desktop-%V%-rust.exe
exit /b 0
