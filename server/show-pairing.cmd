@echo off
rem piDroid - print the pairing QR for the gateway that is already running.
rem
rem The gateway autostarts hidden (Scheduled Task -> wscript -> cmd -> node), so
rem its banner and QR only exist inside logs\gateway.log. This gives the user a
rem window with a scannable code without touching the running service.
rem
rem chcp 65001 is required: the QR is drawn with UTF-8 block characters, and a
rem legacy console defaults to the OEM code page (936 here) which garbles it.
rem
rem NOTE: ASCII-only on purpose, see start-hidden.cmd for why.

chcp 65001 >nul
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
    echo [show-pairing] node.exe not found in PATH
    if /i not "%~1"=="--no-pause" pause
    exit /b 1
)

node "%~dp0show-pairing.js" %*
set "RC=%ERRORLEVEL%"

if /i not "%~1"=="--no-pause" pause
exit /b %RC%
