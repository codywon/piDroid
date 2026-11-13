# One sample per run: upstream latency, pi-web CPU, and whether git.exe is running.
# ASCII only: Windows PowerShell 5.1 reads a no-BOM .ps1 as ANSI, and non-ASCII
# comments in this file previously broke parsing.
$ErrorActionPreference = 'SilentlyContinue'

$line = netstat -ano -p TCP | Select-String ':30141' | Select-String LISTENING | Select-Object -First 1
$piwebPid = 0
if ($line) { $piwebPid = [int](($line.ToString() -split '\s+') | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1) }

$req = [System.Net.HttpWebRequest]::Create('http://127.0.0.1:30141/api/agent/running')
$req.Timeout = 15000
$req.ReadWriteTimeout = 15000
$req.Proxy = $null
$sw = [Diagnostics.Stopwatch]::StartNew()
$code = 0
try {
    $resp = $req.GetResponse()
    $code = [int]$resp.StatusCode
    $resp.Close()
} catch {
    if ($_.Exception.Response) { $code = 500 } else { $code = 0 }
}
$ms = [int]$sw.Elapsed.TotalMilliseconds

$cpuBefore = 0.0
$cpuAfter = 0.0
$rssMb = 0
if ($piwebPid -gt 0) {
    $p = Get-Process -Id $piwebPid
    if ($p) {
        $cpuBefore = $p.CPU
        Start-Sleep -Milliseconds 400
        $p2 = Get-Process -Id $piwebPid
        if ($p2) { $cpuAfter = $p2.CPU; $rssMb = [int]($p2.WorkingSet64 / 1MB) }
    }
}
$cpuPct = if ($cpuAfter -gt 0 -and $cpuBefore -gt 0) { [math]::Round(($cpuAfter - $cpuBefore) / 0.4 * 100, 0) } else { -1 }

$gitCount = @(Get-Process -Name git).Count
$nodeKids = @(Get-CimInstance Win32_Process -Filter "name='node.exe'" | Where-Object { $_.ParentProcessId -eq $piwebPid }).Count

"$((Get-Date).ToString('HH:mm:ss')) http=$code ${ms}ms pid=$piwebPid rss=${rssMb}MB cpu=${cpuPct}% git=$gitCount nodeKids=$nodeKids"
