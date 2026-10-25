@echo off
title piDroid Unified Companion (Auto Cloudflare Tunnel)
echo ======================================================
echo    🌐 Starting piDroid Unified Companion with Tunnel
echo ======================================================
cd /d %~dp0server

if not exist node_modules (
    echo Installing server dependencies...
    npm install
)

node --no-deprecation launcher.js --tunnel
pause
