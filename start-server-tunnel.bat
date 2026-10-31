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

rem If you use a custom Cloudflare Named Tunnel, configure environment variables:
rem set "PI_TUNNEL_NAME=pi"
rem set "PI_TUNNEL_HOSTNAME=your.domain.com"

node --no-deprecation launcher.js --tunnel
pause
