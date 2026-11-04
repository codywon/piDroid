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
set RC=%ERRORLEVEL%

rem Session 0 (task with LogonType=Password) runs with a full token, so a normal
rem shell gets "Access denied" -> exit 2. Re-run elevated: one UAC prompt beats
rem telling the user to open a second window.
if "%RC%"=="2" (
    echo [stop-gateway] requesting administrator rights...
    powershell -NoProfile -Command ^
        "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','%~dp0stop-gateway.ps1'"
    if errorlevel 1 (
        echo [stop-gateway] elevated run failed or was cancelled
        exit /b 1
    )
)

exit /b %RC%
