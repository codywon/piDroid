@echo off
title piDroid Unified Companion (pi-web + Gateway)
echo ======================================================
echo    Starting piDroid Unified Companion (frp + LAN, foreground)
echo ======================================================
rem This file lives in server/; %~dp0 is already the server folder.
cd /d "%~dp0"

if not exist node_modules (
    echo Installing server dependencies...
    npm install
)

node --no-deprecation launcher.js %*
pause
