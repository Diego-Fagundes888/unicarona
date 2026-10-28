param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskPrivate=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UniCarona/Private'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
. (Join-Path $PSScriptRoot 'copy-private-files.ps1')
Assert-TaskPrivatePermissions $taskPrivate
$taskOwner=Get-Content -LiteralPath (Join-Path $taskPrivate 'owner.json') -Raw | ConvertFrom-Json
if($taskOwner.workspaceRoot -ne $taskRoot -or $taskOwner.sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value){throw 'Private storage ownership mismatch.'}
if(Test-Path -LiteralPath (Join-Path $taskRoot '.local/private-runtime.json')){throw 'Cold staging cannot run after activation.'}
if(@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -like '*02892a03ee550f3eba0227e82e5676e0817e9e6c4bfd63383c1b9743062e875f*'}).Count){throw 'Gracefully stop the existing sandbox API and worker first.'}
& node (Join-Path $PSScriptRoot 'private-runtime-proof.mjs') before
if($LASTEXITCODE -ne 0){throw 'Read-only pre-copy checkpoint failed.'}
$taskOldData=Join-Path $taskRoot '.local/postgres-data'
$taskPid=Get-Content -LiteralPath (Join-Path $taskOldData 'postmaster.pid')
if($taskPid.Count -lt 4 -or $taskPid[3].Trim() -ne '55432'){throw 'The source cluster is not the isolated development cluster.'}
$taskProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskPid[0].Trim())
if(!$taskProcess -or $taskProcess.Name -ne 'postgres.exe' -or !$taskProcess.CommandLine.Replace('\','/').Contains($taskOldData.Replace('\','/'))){throw 'PostgreSQL process ownership mismatch.'}
$taskOldControl=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/unicarona-postgres/runtime/pgsql/bin/pg_ctl.exe'
& $taskOldControl -D $taskOldData stop -m fast -w -t 30
if($LASTEXITCODE -ne 0 -or (Test-Path -LiteralPath (Join-Path $taskOldData 'postmaster.pid'))){throw 'Source PostgreSQL did not stop cleanly. Originals remain intact.'}
$taskCopies=[Collections.Generic.List[object]]::new()
function Add-TaskCopies([string]$Source,[string]$Target){foreach($taskCopy in @(Copy-TaskPrivateTree $Source $Target $taskPrivate)){$taskCopies.Add($taskCopy)}}
# A cold backup, the active copy and the restore-test copy are distinct directories.
Add-TaskCopies $taskOldData (Join-Path $taskPrivate 'data/postgres')
Add-TaskCopies $taskOldData (Join-Path $taskPrivate 'backups/migration-baseline/postgres')
Add-TaskCopies (Join-Path $taskPrivate 'backups/migration-baseline/postgres') (Join-Path $taskPrivate 'data/restore-proof')
foreach($taskName in @('storage','mp-sandbox-storage','mail','e2e-mail','java-e2e-runs','java-e2e-aborted','mp-backup-proof','pglogs')){
 $taskSource=Join-Path $taskRoot ('.local/'+$taskName)
 if(Test-Path -LiteralPath $taskSource){Add-TaskCopies $taskSource (Join-Path $taskPrivate ('archives/legacy-local/'+$taskName))}
}
foreach($taskName in @('storage','mp-sandbox-storage','mail')){Add-TaskCopies (Join-Path $taskRoot ('.local/'+$taskName)) (Join-Path $taskPrivate ('data/'+$taskName))}
foreach($taskFile in Get-ChildItem -LiteralPath (Join-Path $taskRoot '.local') -File -Force){Add-TaskCopies $taskFile.FullName (Join-Path $taskPrivate ('archives/legacy-local/'+$taskFile.Name))}
$taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');expiresAt=[DateTime]::UtcNow.AddDays(35).ToString('o');phase='cold-copied-not-active';sourceCleanlyStopped=$true;copies=@($taskCopies.ToArray())}
$taskReport|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $taskPrivate 'archives/cold-copy-manifest.json') -Encoding utf8
[ordered]@{at=$taskReport.at;expiresAt=$taskReport.expiresAt;phase=$taskReport.phase;sourceCleanlyStopped=$taskReport.sourceCleanlyStopped;verifiedFiles=$taskCopies.Count}|ConvertTo-Json -Compress
