@echo off
chcp 65001 >nul
title 升级 pi-web 到最新版
echo ======================================================
echo    正在准备升级 @agegr/pi-web 到最新版本...
echo ======================================================
cd /d %~dp0

echo 1. 正在停止当前运行中的 pi-web...
call pi-web stop
timeout /t 2 /nobreak >nul

echo 2. 正在执行 npm 全局升级安装...
call npm install -g @agegr/pi-web@latest

if %errorlevel%==0 (
    echo.
    echo ======================================================
    echo [OK] pi-web 升级成功！当前版本：
    call pi-web --version
    echo ======================================================
    echo.
    echo 正在重新启动 pi-web...
    timeout /t 1 /nobreak >nul
    call "D:\myscript\pi-app\start-piweb.bat"
) else (
    echo.
    echo [ERROR] 升级失败，错误代码：%errorlevel%
    echo 请检查是否有其他进程占用，或稍后重试。
    pause
)
