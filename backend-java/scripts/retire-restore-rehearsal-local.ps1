param([switch]$Apply)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivate=Get-TaskPrivateRoot $taskRoot
if(!$taskPrivate){throw 'Protected runtime is required.'}
$taskRehearsal=[IO.Path]::GetFullPath((Join-Path $taskPrivate 'data/restore-proof'))
$taskExpected=[IO.Path]::GetFullPath((Join-Path $taskPrivate 'data'))+'\restore-proof'
if($taskRehearsal -ne $taskExpected -or $taskRehearsal -eq (Join-Path $taskPrivate 'data/postgres')){throw 'Unsafe rehearsal cleanup scope.'}
if(!(Test-Path -LiteralPath $taskRehearsal)){[ordered]@{rehearsalAlreadyAbsent=$true}|ConvertTo-Json;return}
foreach($taskProofName in @('clone','after')){
 $taskProof=Get-Content -LiteralPath (Join-Path $taskPrivate ('archives/migration/'+$taskProofName+'.json')) -Encoding utf8 -Raw|ConvertFrom-Json
 if(!$taskProof.matchesSource){throw 'Both physical restore proofs must exist before rehearsal retirement.'}
}
if(!(Test-Path -LiteralPath (Join-Path $taskPrivate 'backups/migration-baseline/postgres/PG_VERSION'))){throw 'The independent cold backup must remain present.'}
if(Test-Path -LiteralPath (Join-Path $taskRehearsal 'postmaster.pid')){throw 'The rehearsal may still be running.'}
if(@(Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Replace('\','/').Contains($taskRehearsal.Replace('\','/'))}).Count){throw 'A PostgreSQL process still uses the rehearsal directory.'}
& (Join-Path $taskPrivate 'runtime/postgres/bin/pg_ctl.exe') -D $taskRehearsal status | Out-Null
if($LASTEXITCODE -ne 3){throw 'The rehearsal cluster is not confirmed stopped.'}
$taskItems=@((Get-Item -LiteralPath $taskRehearsal -Force))+@(Get-ChildItem -LiteralPath $taskRehearsal -Recurse -Force)
foreach($taskItem in $taskItems){
 $taskPath=[IO.Path]::GetFullPath($taskItem.FullName)
 if($taskPath -ne $taskRehearsal -and !$taskPath.StartsWith($taskRehearsal+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'A rehearsal item escaped its intended directory.'}
 if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Linked rehearsal items are never removed.'}
}
$taskFileCount=@($taskItems|Where-Object {!$_.PSIsContainer}).Count
if($Apply){
 foreach($taskFile in $taskItems|Where-Object {!$_.PSIsContainer}){Remove-Item -LiteralPath $taskFile.FullName -ErrorAction Stop}
 # Empty directories only. A concurrent new file causes cleanup to fail rather than broaden its scope.
 foreach($taskDirectory in $taskItems|Where-Object {$_.PSIsContainer}|Sort-Object {$_.FullName.Length} -Descending){
  if(@(Get-ChildItem -LiteralPath $taskDirectory.FullName -Force).Count){throw 'Rehearsal directory is no longer empty.'}
  Remove-Item -LiteralPath $taskDirectory.FullName -ErrorAction Stop
 }
}
$taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');applied=[bool]$Apply;rehearsalFiles=$taskFileCount;independentColdBackupPreserved=$true;scope='Stopped temporary physical-restore rehearsal only; native active data and retained backup untouched'}
$taskReport|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskPrivate 'logs/rehearsal-retirement.json') -Encoding utf8
$taskReport|ConvertTo-Json
