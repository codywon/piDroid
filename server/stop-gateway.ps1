# piDroid companion gateway - kill the whole tree the Scheduled Task started.
#
# Why: "schtasks /End" terminates only the action process (wscript). Task
# Scheduler does not walk the process tree, so cmd -> node -> frpc keep running
# and keep holding the gateway port; the task then looks stopped while the
# service is alive (and a later /Run silently adds a second instance).
#
# How the tree is found (in this order):
#   1) listeners on the gateway / pi-web ports (4100, 30141) -> their ancestors;
#   2) the legacy command-line match (start-hidden / frpc-pidroid.toml).
# (1) exists because the task can run with LogonType=Password, i.e. in session 0
# where Win32_Process.CommandLine is empty for our (non-elevated) session - the
# old match then finds nothing and this script used to say "nothing to stop"
# while the service was very much alive.
#
# Once the anchors are known we climb to the topmost allowed ancestor and kill
# it with `taskkill /T /F`, so children cannot be orphaned.
#
# Session 0 processes run with a full token: a non-elevated shell gets
# "Access denied". In that case this script exits with code 2 and
# stop-gateway.cmd re-runs it elevated (one UAC prompt).
#
# NOTE: never name a variable $pid here - it is a read-only automatic variable
# in PowerShell, and a silent failure there turns this into a random killer.
#
# ASCII-only on purpose; see start-hidden.cmd for the code page reasoning.

$ErrorActionPreference = 'SilentlyContinue'

$allowed = @('node.exe', 'cmd.exe', 'wscript.exe', 'frpc.exe')
$ports = @(4100, 30141)
$self = $PID

$procs = Get-CimInstance Win32_Process
$byId = @{}
foreach ($proc in $procs) { $byId[[int]$proc.ProcessId] = $proc }

$anchors = @{}
foreach ($port in $ports) {
    Get-NetTCPConnection -State Listen -LocalPort $port | ForEach-Object {
        $anchors[[int]$_.OwningProcess] = $true
    }
}
foreach ($proc in $procs) {
    if ($proc.CommandLine -and (
            $proc.CommandLine -match 'start-hidden' -or
            $proc.CommandLine -match 'frpc-pidroid\.toml')) {
        $anchors[[int]$proc.ProcessId] = $true
    }
}

if ($anchors.Count -eq 0) {
    Write-Output '[stop-gateway] nothing to stop'
    exit 0
}

# Climb from each anchor to the topmost process we are allowed to own.
# Only the top of the chain is recorded: `taskkill /T` already takes the children.
$roots = @{}
foreach ($anchorId in @($anchors.Keys)) {
    $current = [int]$anchorId
    $topId = $null
    $topName = $null
    $guard = 0
    while ($current -gt 4 -and $guard -lt 32) {
        $guard++
        $proc = $byId[$current]
        if (-not $proc) { break }
        if ($allowed -contains $proc.Name) {
            $topId = $current
            $topName = $proc.Name
            $current = [int]$proc.ParentProcessId
        } else {
            break
        }
    }
    if ($topId) { $roots[$topId] = $topName }
}

$denied = $false
foreach ($rootId in @($roots.Keys)) {
    if ($rootId -eq $self) { continue }
    Write-Output ('[stop-gateway] kill tree ' + $rootId + ' ' + $roots[$rootId])
    $out = & taskkill /PID $rootId /T /F 2>&1
    if ($LASTEXITCODE -ne 0) {
        $denied = $true
        Write-Output ('[stop-gateway] ' + ($out -join ' '))
    }
}

Start-Sleep -Milliseconds 800

$stillListening = @()
foreach ($port in $ports) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port) { $stillListening += $port }
}

if ($stillListening.Count -gt 0) {
    if ($denied) {
        Write-Output ('[stop-gateway] need administrator rights (ports still held: ' + ($stillListening -join ', ') + ')')
        exit 2
    }
    Write-Output ('[stop-gateway] WARNING: still listening on ' + ($stillListening -join ', '))
    exit 1
}

Write-Output '[stop-gateway] all piDroid gateway processes stopped'
exit 0
