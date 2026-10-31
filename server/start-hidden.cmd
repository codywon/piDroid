@echo off
rem piDroid companion gateway - console-less entry point.
rem Called by start-hidden.vbs (which is what the Scheduled Task runs).
rem
rem Why a batch file instead of putting node.exe straight into the task:
rem   1) the task's working directory is unreliable, so cd here first;
rem   2) stdout/stderr must land in a log, because nobody can see a hidden window;
rem   3) a missing node_modules (fresh clone, cleaned cache) is repaired once;
rem   4) the launcher is supervised here, in a loop. Task Scheduler's own
rem      "restart on failure" does not fire for manually started instances and
rem      its granularity is one minute; a 5s loop is faster and testable.
rem
rem Safeguards in this file (each one learned the hard way):
rem   * single instance - if something already listens on the gateway port the
rem     script exits instead of spawning a second launcher that can only fail;
rem   * backoff with ping -n, never "timeout /t" - timeout needs an interactive
rem     console and returns immediately when stdin is redirected (a scheduled
rem     task always redirects it), which turns the loop into a hot spin.
rem
rem Stop with stop-gateway.cmd. "schtasks /End" only kills wscript, leaving the
rem node/frpc tree orphaned (Task Scheduler does not kill grandchildren).
rem
rem NOTE: this file is deliberately ASCII-only. cmd.exe reads .cmd in the OEM
rem code page, so UTF-8 Chinese comments get garbled and can even break parsing.
rem
rem Arguments are passed through to launcher.js, e.g. --local or --tunnel.

setlocal enableextensions
cd /d "%~dp0"

if not exist "logs" mkdir "logs"

rem Keep the log bounded: one 5 MB generation plus the rolling file is enough.
if exist "logs\gateway.log" for %%A in ("logs\gateway.log") do if %%~zA GTR 5242880 move /y "logs\gateway.log" "logs\gateway.log.1" >nul

if not exist "node_modules" (
    echo [start-hidden] node_modules missing, running npm install...
    call npm install --no-audit --no-fund >> "logs\gateway.log" 2>&1
)

rem Prefer the absolute node path: a scheduled task does not always inherit the
rem same PATH as an interactive shell.
set "NODE_EXE=%ProgramFiles%\nodejs\node.exe"
if not exist "%NODE_EXE%" set "NODE_EXE=node"

if not defined GATEWAY_PORT set "GATEWAY_PORT=4100"
set "RESTART_SECONDS=5"
set /a PING_COUNT=%RESTART_SECONDS%+1

:run
netstat -ano | findstr /r /c:":%GATEWAY_PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [start-hidden] %DATE% %TIME% port %GATEWAY_PORT% already in use by another instance, exiting >> "logs\gateway.log"
    goto done
)

echo. >> "logs\gateway.log"
echo [start-hidden] %DATE% %TIME% starting launcher.js %* >> "logs\gateway.log"
"%NODE_EXE%" --no-deprecation launcher.js %* >> "logs\gateway.log" 2>&1
echo [start-hidden] %DATE% %TIME% launcher exited rc=%ERRORLEVEL%, retrying in %RESTART_SECONDS%s >> "logs\gateway.log"
ping -n %PING_COUNT% 127.0.0.1 > nul
goto run

:done
exit /b 0
