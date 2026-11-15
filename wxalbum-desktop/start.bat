@echo off
chcp 65001 >nul
title WeChat Album to EPUB
cd /d "%~dp0"
where python >nul 2>nul
if %errorlevel%==0 (
    python app.py
) else (
    where py >nul 2>nul
    if %errorlevel%==0 (
        py -3 app.py
    ) else (
        echo Python 3 not found. Please install Python 3.9+ first.
        pause
        exit /b 1
    )
)
if errorlevel 1 pause
