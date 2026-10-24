@echo off
title Pi Mobile Gateway
echo Starting Pi Mobile Gateway...
cd /d %~dp0

if not exist node_modules (
    echo Installing dependencies...
    npm install
)

node gateway.js
pause
