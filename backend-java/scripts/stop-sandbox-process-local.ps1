param([Parameter(Mandatory=$true)][int]$TargetProcessId,[string]$ExpectedJarHash='02892a03ee550f3eba0227e82e5676e0817e9e6c4bfd63383c1b9743062e875f')
$ErrorActionPreference='Stop'
$env:PSModulePath=Join-Path $PSHOME 'Modules'
$taskTarget=Get-CimInstance Win32_Process -Filter "ProcessId=$TargetProcessId"
if($ExpectedJarHash -notmatch '^[a-f0-9]{64}$'){throw 'Expected an immutable sandbox artifact hash.'}
if(!$taskTarget -or $taskTarget.Name -ne 'java.exe' -or !$taskTarget.CommandLine.Contains($ExpectedJarHash)){throw 'Refusing to signal an unrelated Java process.'}
$taskParent=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskTarget.ParentProcessId)
$taskPrivate=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UniCarona/Private'))
$taskProtectedLauncher=Join-Path $taskPrivate 'runtime/scripts/start-protected-sandbox-local.ps1'
$taskOwnedLauncher=$taskParent -and (($taskParent.CommandLine -like '*mercadopago-sandbox-local.ps1*') -or $taskParent.CommandLine.Contains($taskProtectedLauncher))
if(!$taskParent -or $taskParent.Name -ne 'powershell.exe' -or !$taskOwnedLauncher){throw 'The sandbox does not own an isolated launcher console.'}
if($taskParent.CommandLine.Contains($taskProtectedLauncher) -and $taskTarget.ExecutablePath -ne (Join-Path $taskPrivate 'runtime/java/bin/java.exe')){throw 'Protected sandbox executable mismatch.'}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class UniCaronaConsoleShutdown {
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool FreeConsole();
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool AttachConsole(uint pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern uint GetConsoleProcessList([Out] uint[] ids,uint size);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler,bool add);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint signal,uint group);
}
'@
[UniCaronaConsoleShutdown]::FreeConsole() | Out-Null
if(![UniCaronaConsoleShutdown]::AttachConsole([uint32]$TargetProcessId)){throw 'Unable to attach to the sandbox console; nothing was signaled.'}
try{
 [uint32[]]$taskConsoleIds=New-Object uint32[] 32
 $taskCount=[UniCaronaConsoleShutdown]::GetConsoleProcessList($taskConsoleIds,32)
 if(!$taskCount -or $taskCount -gt 32){throw 'Unexpected console membership; nothing was signaled.'}
 $taskAllowed=@([uint32]$PID,[uint32]$TargetProcessId,[uint32]$taskParent.ProcessId)
 if(@($taskConsoleIds[0..($taskCount-1)] | Where-Object {$_ -notin $taskAllowed}).Count){throw 'The console contains another process; nothing was signaled.'}
 if(![UniCaronaConsoleShutdown]::SetConsoleCtrlHandler([IntPtr]::Zero,$true)){throw 'Cannot protect the helper from its console signal.'}
 if(![UniCaronaConsoleShutdown]::GenerateConsoleCtrlEvent(0,0)){throw 'The sandbox shutdown signal was not sent.'}
}finally{[UniCaronaConsoleShutdown]::FreeConsole() | Out-Null}
$taskDeadline=[DateTime]::UtcNow.AddSeconds(35)
while(Get-Process -Id $TargetProcessId -ErrorAction SilentlyContinue){if([DateTime]::UtcNow -gt $taskDeadline){throw 'Sandbox did not finish its graceful shutdown; no forced termination was attempted.'};Start-Sleep -Milliseconds 200}
[ordered]@{gracefulSignal=$true;targetExited=$true;targetProcessId=$TargetProcessId}|ConvertTo-Json -Compress
