' piDroid - run a supervisor.js subcommand with no console window.
'
' Why this shim: both Scheduled Tasks ("piDroid Stack" at startup/logon, and
' "piDroid Stack Watchdog" every 5 minutes) run with LogonType=InteractiveToken.
' Such a task always gets a console window for a console program, and with
' Windows Terminal as the default terminal that window is a black terminal
' popping up over the desktop. For the watchdog it happened every 5 minutes.
' wscript creates no console at all, and Run(..., 0, ...) hides the child.
'
' Why wait instead of fire-and-forget: staying alive keeps wscript as the task's
' root process, so the task reads "Running" while the stack is up and a crash
' propagates a non-zero exit code - that is what makes Task Scheduler's
' "restart on failure" mean something. Same reasoning as start-hidden.vbs.
'
' Scheduled Task actions should be:
'   wscript.exe "<repo>\server\supervisor-hidden.vbs" run      (piDroid Stack)
'   wscript.exe "<repo>\server\supervisor-hidden.vbs" ensure   (watchdog)
'
' NOTE: ASCII-only on purpose; wscript reads .vbs in the OEM code page.

Option Explicit
Dim fso, shell, serverDir, nodeExe, supJs, action, cmdLine, exitCode
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

serverDir = fso.GetParentFolderName(WScript.ScriptFullName)
supJs = fso.BuildPath(serverDir, "supervisor.js")

action = "ensure"
If WScript.Arguments.Count > 0 Then action = WScript.Arguments(0)

' Prefer the absolute node path: a scheduled task does not always inherit the
' same PATH as an interactive shell (same rule as start-hidden.cmd).
nodeExe = fso.BuildPath(shell.ExpandEnvironmentStrings("%ProgramFiles%"), "nodejs\node.exe")
If Not fso.FileExists(nodeExe) Then nodeExe = "node"

' A task's working directory is not reliably the server folder (it is System32
' when wscript itself is the action), so pin it here like start-hidden.cmd does.
shell.CurrentDirectory = serverDir

cmdLine = """" & nodeExe & """ """ & supJs & """ " & action

' 0 = hidden window, True = wait for exit and hand the exit code back
exitCode = shell.Run(cmdLine, 0, True)
WScript.Quit exitCode
