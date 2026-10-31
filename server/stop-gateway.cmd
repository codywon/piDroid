@echo off
rem piDroid companion gateway - stop the Scheduled Task's whole process tree.
rem
rem "schtasks /End" is NOT enough: it kills wscript only, leaving cmd -> node ->
rem frpc alive and holding the gateway port. The real work happens in
rem stop-gateway.ps1 (command-line matching + descendant walk), so frpc.exe used
rem by other tunnels and other node apps are left alone.
rem
rem NOTE: ASCII-only on purpose, see start-hidden.cmd.

setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-gateway.ps1"
exit /b %ERRORLEVEL%
