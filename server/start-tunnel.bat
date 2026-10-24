@echo off
title piDroid Companion Gateway (Auto Cloudflare Tunnel)
echo Starting piDroid Gateway with Cloudflare Auto Tunnel...
cd /d %~dp0

if not exist node_modules (
    echo Installing dependencies...
    npm install
)

node gateway.js --tunnel
pause
