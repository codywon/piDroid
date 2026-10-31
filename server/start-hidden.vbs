' piDroid companion gateway - start start-hidden.cmd with no console window.
'
' Why this shim: a Scheduled Task that runs cmd.exe or node.exe "only when the
' user is logged on" always gets a console window, so booting the PC pops a
' black box. wscript with Run(..., 0, True) starts it hidden.
'
' Why wait instead of fire-and-forget: with wait=True wscript stays alive as the
' task's root process, so the task shows "Running" while the gateway works and a
' crashed launcher propagates its non-zero exit code. That is what lets Task
' Scheduler's "restart on failure" (1 min, 999 tries) actually mean something.
' A fire-and-forget shim would make every start look like an instant success.
'
' Scheduled Task action should be:
'   wscript.exe "<repo>\server\start-hidden.vbs"
'
' To stop the stack: schtasks /End /TN "piDroid Gateway (frp)"
'
' NOTE: ASCII-only on purpose; wscript reads .vbs in the OEM code page.

Option Explicit
Dim fso, shell, cmdPath, exitCode
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

cmdPath = fso.BuildPath(fso.GetParentFolderName(WScript.ScriptFullName), "start-hidden.cmd")

' 0 = hidden window, True = wait for exit and hand the exit code back
exitCode = shell.Run("cmd.exe /c """ & cmdPath & """", 0, True)
WScript.Quit exitCode
