@echo off
title piDroid Unified Companion (pi-web + Gateway)
echo ======================================================
echo    🚀 Starting piDroid Unified Companion (Root)
echo ======================================================
cd /d %~dp0server

if not exist node_modules (
    echo Installing server dependencies...
    npm install
)

node --no-deprecation launcher.js
pause
