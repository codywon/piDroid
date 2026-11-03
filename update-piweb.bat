@echo off
rem Upgrade @agegr/pi-web in place: stop the stack, install, start it again.
rem
rem Do NOT run this from a terminal that pi-web opened (an agent session): the
rem script stops the whole tree, so it would kill its own parent mid-run.
rem Double-click it from Explorer, or run it from a standalone console.
rem
rem Why a wrapper: the real work (process-tree stop, folder-unlock probe,
rem install retries, restart, port wait) lives in upgrade-piweb.ps1.
rem NOTE: ASCII-only on purpose, same rule as server\start-hidden.cmd.

setlocal
cd /d "%~dp0"
chcp 65001 >nul
title Upgrade @agegr/pi-web
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0upgrade-piweb.ps1"
exit /b %ERRORLEVEL%
