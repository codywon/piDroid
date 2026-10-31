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

rem Cloudflare Named Tunnel (stable URL, recommended):
rem   gateway.js auto-detects it from %USERPROFILE%\.cloudflared\config.yml
rem   (tunnel + ingress hostname pointing at the gateway port), so no env vars
rem   are needed once `cloudflared tunnel create / route dns` has been done.
rem   Without it the launcher falls back to a Quick Tunnel whose random URL
rem   changes on every restart -- the phone then shows "reconnecting" forever.
rem To pin a different one manually, set these before running:
rem set "PI_TUNNEL_NAME=pi"
rem set "PI_TUNNEL_HOSTNAME=your.domain.com"

node --no-deprecation launcher.js --tunnel
pause
