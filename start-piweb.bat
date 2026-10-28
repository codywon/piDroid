@echo off
title piDroid pi-web (Native Web UI)
echo ======================================================
echo    pi-web Native Web UI (port 30141)
echo ======================================================
cd /d %~dp0

rem Reuse an already running instance on 30141 to avoid a port conflict
netstat -ano | findstr /C:":30141 " | findstr /C:"LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [OK] pi-web is already running on port 30141, opening the browser.
    start "" http://127.0.0.1:30141
    exit /b 0
)

rem Prefer the globally installed pi-web, fall back to npx
where pi-web >nul 2>&1
if %errorlevel%==0 (
    echo Using globally installed pi-web...
    set "PIWEB_CMD=pi-web"
) else (
    echo Global pi-web not found, falling back to npx @agegr/pi-web@latest ...
    set "PIWEB_CMD=npx -y @agegr/pi-web@latest"
)

rem pi-web default port 30141, matching the companion gateway scripts
set "PORT=30141"

echo Launching: %PIWEB_CMD%
echo Local URL:  http://127.0.0.1:30141
echo (pi-web opens the browser automatically; use --no-open to disable)
echo.
call %PIWEB_CMD%
pause
