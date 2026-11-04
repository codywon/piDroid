# piDroid companion gateway - upgrade @agegr/pi-web in place.
#
# Why this exists: `npm i -g @agegr/pi-web@latest` fails with EBUSY when the
# package folder is still in use. On Windows npm upgrades a global package by
# RENAMING its directory, and a rename needs exclusive access to every file
# inside. "No agent task is running" is not the question: the pi-web SERVER
# (node bin/pi-web.js -> next start) keeps that folder open, and so does every
# agent session it hosts. So the tree has to be stopped first.
#
# Order matters: stop the whole scheduled-task tree (not just `schtasks /End`,
# which leaks cmd -> node -> next), prove the folder is unlocked by renaming it,
# install, then start the same tree again.
#
# NOTE: ASCII-only on purpose. Windows PowerShell 5.1 decodes a .ps1 without a
# BOM as ANSI, so non-ASCII text here would be garbled (same rule as
# start-hidden.cmd / stop-gateway.ps1 in this folder).

$ErrorActionPreference = 'Continue'

# This script lives in server/: the sibling lifecycle scripts are right here,
# the repo root is one level up ($PSScriptRoot\..).
$pkg = Join-Path $env:APPDATA 'npm\node_modules\@agegr\pi-web'
$taskName = 'piDroid Gateway (frp)'

function Write-Step([int]$n, [string]$text) {
    Write-Host ''
    Write-Host ("[{0}/7] {1}" -f $n, $text) -ForegroundColor Cyan
}

function Get-PiWebHolders {
    # Only node.exe, and only when its command line points at this stack.
    Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and ($_.CommandLine -match 'pi-web' -or $_.CommandLine -match 'launcher\.js') }
}

function Test-PackageUnlocked {
    # A directory rename is exactly what npm does, so it is the honest test:
    # if we can rename it and back, npm can replace it.
    $probe = "$pkg.upgrade-probe"
    try {
        Rename-Item -LiteralPath $pkg -NewName (Split-Path -Leaf $probe) -ErrorAction Stop
    } catch {
        return $false
    }
    try {
        Rename-Item -LiteralPath $probe -NewName (Split-Path -Leaf $pkg) -ErrorAction Stop
    } catch {
        Write-Host "  WARNING: could not rename the probe back. Rename '$probe' to '$pkg' manually." -ForegroundColor Yellow
    }
    return $true
}

Write-Host '======================================================'
Write-Host '   piDroid: upgrade @agegr/pi-web'
Write-Host '======================================================'
Write-Host 'The gateway, pi-web and the phone connection go down for a minute.'

# A previous run killed between the rename probe and its rename-back would leave
# the package folder under the probe name. Put it back before doing anything.
$staleProbe = "$pkg.upgrade-probe"
if ((Test-Path -LiteralPath $staleProbe) -and -not (Test-Path -LiteralPath $pkg)) {
    try {
        Rename-Item -LiteralPath $staleProbe -NewName (Split-Path -Leaf $pkg) -ErrorAction Stop
        Write-Host '  recovered a leftover rename probe from an earlier run' -ForegroundColor Yellow
    } catch {
        Write-Host "  leftover '$staleProbe' exists and could not be renamed back" -ForegroundColor Red
    }
}

# ---------------------------------------------------------------- 1. preflight
Write-Step 1 'Checking the current install'
if (-not (Test-Path -LiteralPath $pkg)) {
    Write-Host "  package not found at $pkg" -ForegroundColor Red
    Write-Host '  Is @agegr/pi-web installed globally for this user?' -ForegroundColor Red
    Read-Host 'Press Enter to exit'
    exit 1
}
$before = (npm ls -g '@agegr/pi-web' --depth=0 2>$null | Select-String '@agegr/pi-web@')
Write-Host ("  current: {0}" -f ($before -replace '.*@agegr/pi-web@', '@'))

# ---------------------------------------------------------------- 2. stop tree
Write-Step 2 'Stopping the piDroid stack (gateway + pi-web)'
# End the task FIRST: while it is still "Running", Task Scheduler's
# restart-on-failure may resurrect the tree (and relock the folder) mid-install.
# /End only kills wscript, which is why stop-gateway.cmd follows it.
schtasks /Query /TN $taskName > $null 2>&1
if ($LASTEXITCODE -eq 0) {
    schtasks /End /TN $taskName > $null 2>&1
    Write-Host ("  ended scheduled task: {0}" -f $taskName)
}
$holders = Get-PiWebHolders
if ($holders) {
    $holders | ForEach-Object { Write-Host ("  running: PID {0}" -f $_.ProcessId) }
} else {
    Write-Host '  no pi-web process reported by the command line scan'
}
$stopScript = Join-Path $PSScriptRoot 'stop-gateway.cmd'
if (Test-Path -LiteralPath $stopScript) {
    & $stopScript | Out-Host
} else {
    Write-Host '  server\stop-gateway.cmd not found; falling back to a PID kill' -ForegroundColor Yellow
    $holders | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# ---------------------------------------------------------------- 3. unlock
Write-Step 3 'Waiting for the package folder to be released'
$unlocked = $false
for ($i = 1; $i -le 25; $i++) {
    $left = Get-PiWebHolders
    if ($left) {
        foreach ($p in $left) {
            Write-Host ("  still holding: PID {0} -> killing" -f $p.ProcessId)
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 600
        continue
    }
    if (Test-PackageUnlocked) { $unlocked = $true; break }
    Start-Sleep -Milliseconds 800
}
if (-not $unlocked) {
    Write-Host ''
    Write-Host '  FAILED: the folder is still locked.' -ForegroundColor Red
    Write-Host '  Close every window that started pi-web (including a terminal opened from' -ForegroundColor Red
    Write-Host '  the pi-web UI), then run this script again.' -ForegroundColor Red
    Read-Host 'Press Enter to exit'
    exit 1
}
Write-Host '  folder is free'

# ---------------------------------------------------------------- 4. install
Write-Step 4 'Installing @agegr/pi-web@latest'
$installed = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    Write-Host ("  attempt {0}..." -f $attempt)
    npm install -g '@agegr/pi-web@latest' | Out-Host
    if ($LASTEXITCODE -eq 0) { $installed = $true; break }
    Write-Host ("  npm exited with {0}; retrying in 3s" -f $LASTEXITCODE) -ForegroundColor Yellow
    Get-PiWebHolders | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
}
if (-not $installed) {
    Write-Host ''
    Write-Host '  FAILED: npm could not replace the package.' -ForegroundColor Red
    Write-Host '  See the EBUSY hint printed by npm above, then try again.' -ForegroundColor Red
    Read-Host 'Press Enter to exit'
    exit 1
}

# ---------------------------------------------------------------- 5. verify version
Write-Step 5 'Verifying the installed version'
$after = (npm ls -g '@agegr/pi-web' --depth=0 2>$null | Select-String '@agegr/pi-web@')
if ($after) {
    Write-Host ("  now: {0}" -f ($after -replace '.*@agegr/pi-web@', '@')) -ForegroundColor Green
} else {
    Write-Host '  could not read the version back (npm ls failed); continuing' -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 6. restart
Write-Step 6 'Starting the stack again'
schtasks /Query /TN $taskName > $null 2>&1
if ($LASTEXITCODE -eq 0) {
    schtasks /Run /TN $taskName | Out-Host
    Write-Host ("  started scheduled task: {0}" -f $taskName)
} else {
    $bat = Join-Path $PSScriptRoot 'start-server-tunnel.bat'
    if (Test-Path -LiteralPath $bat) {
        Start-Process -FilePath $bat -WorkingDirectory $PSScriptRoot
        Write-Host '  started start-server-tunnel.bat in a new window'
    } else {
        Write-Host '  nothing to start automatically; run start-server-tunnel.bat yourself' -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- 7. wait for the port
Write-Step 7 'Waiting for pi-web on 127.0.0.1:30141 (up to 60s)'
$up = $false
for ($i = 1; $i -le 60; $i++) {
    try {
        $client = New-Object Net.Sockets.TcpClient
        $client.Connect('127.0.0.1', 30141)
        if ($client.Connected) { $up = $true; $client.Close(); break }
    } catch { }
    Start-Sleep -Seconds 1
}

Write-Host ''
if ($up) {
    Write-Host 'DONE - pi-web is listening again.' -ForegroundColor Green
    Write-Host 'Open http://127.0.0.1:30141 in the browser and check the phone app.' -ForegroundColor Green
} else {
    Write-Host 'pi-web did not answer within 60s.' -ForegroundColor Yellow
    Write-Host 'Check the task output: server\logs\gateway.log (start-hidden.cmd writes there)' -ForegroundColor Yellow
}
Write-Host ''
Read-Host 'Press Enter to close'
