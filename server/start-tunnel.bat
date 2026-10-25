@echo off
title piDroid Unified Companion (Auto Cloudflare Tunnel)
echo Starting piDroid Unified Companion with Cloudflare Auto Tunnel...
cd /d %~dp0

if not exist node_modules (
    echo Installing dependencies...
    npm install
)

node --no-deprecation launcher.js --tunnel
pause
