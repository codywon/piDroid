@echo off
title piDroid Gateway
echo Starting piDroid Companion Gateway...
cd /d %~dp0

if not exist node_modules (
    echo Installing dependencies...
    npm install
)

node --no-deprecation gateway.js
pause
