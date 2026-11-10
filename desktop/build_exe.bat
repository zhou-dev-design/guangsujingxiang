@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo [1/3] 安装依赖...
python -m pip install --upgrade pip
python -m pip install -r requirements.txt pyinstaller
if errorlevel 1 goto :fail

echo [2/3] 打包单文件 EXE...
python -m PyInstaller --noconfirm --clean --onefile --windowed --noupx ^
  --name LightMirror ^
  --icon assets\lightmirror.ico ^
  --version-file version_info.txt ^
  --distpath dist --workpath build --specpath . ^
  lightmirror.py
if errorlevel 1 goto :fail

echo [3/3] 完成：dist\LightMirror.exe
echo.
pause
exit /b 0

:fail
echo.
echo 构建失败，请检查上面的报错。
pause
exit /b 1
