# Emit "<pid>,<ppid>,<rssMB>" for every node.exe / cmd.exe process.
#
# Why a script file instead of `powershell -Command "..."` from Node: the -Command
# form needs nested quotes, and cmd.exe eats them. A file takes a typed parameter
# and stays ASCII, which is the same reason start-hidden.cmd is ASCII-only.
#
# The tree walk itself lives in crash-forensics.js (`sumProcessTreeMb`) so it can
# be unit-tested; this script only dumps the process table.

param([Parameter(Mandatory = $true)][int]$RootPid)

$ErrorActionPreference = 'SilentlyContinue'

Get-CimInstance Win32_Process -Filter "Name='node.exe' or Name='cmd.exe'" |
    ForEach-Object {
        '{0},{1},{2}' -f $_.ProcessId, $_.ParentProcessId, [int]($_.WorkingSetSize / 1MB)
    }
