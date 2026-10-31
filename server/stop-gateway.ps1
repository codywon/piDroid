# piDroid companion gateway - kill the whole tree the Scheduled Task started.
#
# Why: "schtasks /End" terminates only the action process (wscript). Task
# Scheduler does not walk the process tree, so cmd -> node -> frpc keep running
# and keep holding the gateway port; the task then looks stopped while the
# service is alive (and a later /Run silently adds a second instance).
#
# What counts as "ours": the start-hidden wrapper (command line), our frpc config
# path, plus every descendant of those. Three guards keep this from being a
# trigger-happy kill-everything script:
#   * only node.exe / cmd.exe / wscript.exe / frpc.exe are ever candidates
#     (frpc.exe is shared with other tunnels, other node apps are untouched)
#   * the current PowerShell process is never a candidate
#   * PIDs <= 4 (Idle / System) are never touched
#
# NOTE: never name a variable $pid here - it is a read-only automatic variable
# in PowerShell, and a silent failure there turns this into a random killer.
#
# ASCII-only on purpose; see start-hidden.cmd for the code page reasoning.

$ErrorActionPreference = 'SilentlyContinue'

$allowed = @('node.exe', 'cmd.exe', 'wscript.exe', 'frpc.exe')
$self = $PID
$procs = Get-CimInstance Win32_Process

$roots = $procs | Where-Object {
    $_.CommandLine -and (
        $_.CommandLine -match 'start-hidden' -or
        $_.CommandLine -match 'frpc-pidroid\.toml'
    )
}

if (-not $roots) {
    Write-Output '[stop-gateway] nothing to stop'
    exit 0
}

$victims = @{}
foreach ($root in $roots) {
    $rootId = [int]$root.ProcessId
    if (($allowed -contains $root.Name) -and ($rootId -gt 4) -and ($rootId -ne $self)) {
        $victims[$rootId] = $root.Name
    }
}

# Walk down: any child of a victim is a victim too (cmd -> node -> frpc).
do {
    $added = $false
    foreach ($proc in $procs) {
        $childId = [int]$proc.ProcessId
        $parentId = [int]$proc.ParentProcessId
        if ($victims.ContainsKey($parentId) -and -not $victims.ContainsKey($childId)) {
            if (($allowed -contains $proc.Name) -and ($childId -gt 4) -and ($childId -ne $self)) {
                $victims[$childId] = $proc.Name
                $added = $true
            }
        }
    }
} while ($added)

foreach ($id in $victims.Keys) {
    Write-Output ('[stop-gateway] kill ' + $id + ' ' + $victims[$id])
    Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Milliseconds 600

$left = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -and ($_.CommandLine -match 'start-hidden' -or $_.CommandLine -match 'frpc-pidroid\.toml')
}
if ($left) {
    Write-Output '[stop-gateway] WARNING: something survived:'
    $left | ForEach-Object { Write-Output ('  ' + $_.ProcessId + ' ' + $_.Name) }
    exit 1
}

Write-Output '[stop-gateway] all piDroid gateway processes stopped'
exit 0
