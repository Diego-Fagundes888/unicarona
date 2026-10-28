param([switch]$Apply)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivate=Get-TaskPrivateRoot $taskRoot
if(!$taskPrivate){throw 'Protected report archive is required.'}
$taskFiles=[Collections.Generic.List[object]]::new()
foreach($taskName in @('surefire-reports','failsafe-reports')){
 $taskSource=[IO.Path]::GetFullPath((Join-Path $taskRoot ('backend-java/target/'+$taskName)))
 $taskArchive=[IO.Path]::GetFullPath((Join-Path $taskPrivate ('archives/pre-migration-test-reports/'+$taskName)))
 if(!(Test-Path -LiteralPath $taskSource)){continue}
 if(!(Test-Path -LiteralPath $taskArchive)){throw 'The report directory lacks a protected archive.'}
 $taskExpected=[IO.Path]::GetFullPath((Join-Path $taskRoot 'backend-java/target'))+'\'+$taskName
 if($taskSource -ne $taskExpected -or !$taskArchive.StartsWith($taskPrivate+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Test report archive scope check failed.'}
 foreach($taskItem in @((Get-Item -LiteralPath $taskSource -Force))+@(Get-ChildItem -LiteralPath $taskSource -Recurse -Force)){
  if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Linked report paths are never retired.'}
  if($taskItem.PSIsContainer){continue}
  $taskRelative=$taskItem.FullName.Substring($taskSource.Length+1)
  $taskCopy=[IO.Path]::GetFullPath((Join-Path $taskArchive $taskRelative))
  if(!$taskItem.FullName.StartsWith($taskSource+'\',[StringComparison]::OrdinalIgnoreCase) -or !$taskCopy.StartsWith($taskArchive+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'An individual report path escaped its allowed directory.'}
  Assert-TaskPrivatePermissions $taskCopy
  $taskHash=(Get-FileHash -LiteralPath $taskItem.FullName -Algorithm SHA256).Hash
  if((Get-FileHash -LiteralPath $taskCopy -Algorithm SHA256).Hash -ne $taskHash){throw 'A report differs from its private copy. Nothing else will be retired.'}
  $taskFiles.Add([pscustomobject]@{source=$taskItem.FullName;copy=$taskCopy;sha256=$taskHash})
 }
}
# Every candidate is copied and hashed before any removal. No directory is deleted and no recursive removal is used.
if($Apply){foreach($taskFile in $taskFiles){
 if((Get-FileHash -LiteralPath $taskFile.source -Algorithm SHA256).Hash -ne $taskFile.sha256 -or (Get-FileHash -LiteralPath $taskFile.copy -Algorithm SHA256).Hash -ne $taskFile.sha256){throw 'An individual report changed before removal.'}
 Remove-Item -LiteralPath $taskFile.source -ErrorAction Stop
}}
[ordered]@{at=[DateTime]::UtcNow.ToString('o');applied=[bool]$Apply;verifiedIndividualFiles=$taskFiles.Count;directoriesRemoved=0;scope='Only byte-identical Java test reports already archived under protected storage'}|ConvertTo-Json
