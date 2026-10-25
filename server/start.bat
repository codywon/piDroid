@echo off
title piDroid Unified Companion (pi-web + Gateway)
echo Starting piDroid Unified Companion...
cd /d %~dp0

if not exist node_modules (
    echo Installing dependencies...
    npm install
)

node --no-deprecation launcher.js
pause
