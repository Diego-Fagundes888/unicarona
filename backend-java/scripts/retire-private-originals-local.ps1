param([switch]$Apply)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivate=Get-TaskPrivateRoot $taskRoot
if(!$taskPrivate){throw 'Activate and verify the private runtime first.'}
$taskBefore=Get-Content -LiteralPath (Join-Path $taskPrivate 'archives/migration/before.json') -Raw | ConvertFrom-Json
foreach($taskProofName in @('clone','after')){
 $taskProof=Get-Content -LiteralPath (Join-Path $taskPrivate ('archives/migration/'+$taskProofName+'.json')) -Raw | ConvertFrom-Json
 if(!$taskProof.matchesSource){throw 'Verified physical recovery evidence is required before retiring originals.'}
}
if((Invoke-RestMethod -Uri 'http://127.0.0.1:3005/health/ready' -TimeoutSec 5).status -ne 'ready'){throw 'The private runtime API is not ready.'}
$taskJarProcesses=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -like '*02892a03ee550f3eba0227e82e5676e0817e9e6c4bfd63383c1b9743062e875f*'})
if($taskJarProcesses.Count -ne 2 -or @($taskJarProcesses | Where-Object {!$_.ExecutablePath.StartsWith($taskPrivate+'\',[StringComparison]::OrdinalIgnoreCase)}).Count){throw 'Both baseline services must be running from the protected runtime.'}
$taskManifest=Get-Content -LiteralPath (Join-Path $taskPrivate 'archives/cold-copy-manifest.json') -Raw | ConvertFrom-Json
$taskLocal=[IO.Path]::GetFullPath((Join-Path $taskRoot '.local'))
$taskVerified=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($taskCopy in $taskManifest.copies){
 $taskSource=[IO.Path]::GetFullPath($taskCopy.source);$taskTarget=[IO.Path]::GetFullPath($taskCopy.target)
 if(!$taskSource.StartsWith($taskLocal+'\',[StringComparison]::OrdinalIgnoreCase)){continue}
 if(!$taskTarget.StartsWith($taskPrivate+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Retirement manifest target is outside protected storage.'}
 # Active databases change after startup. Require the unchanged cold/archive counterpart instead.
 if($taskTarget.StartsWith((Join-Path $taskPrivate 'data')+'\',[StringComparison]::OrdinalIgnoreCase)){continue}
 if(!(Test-Path -LiteralPath $taskSource)){continue}
 if((Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash -ne $taskCopy.sha256 -or (Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -ne $taskCopy.sha256){throw 'Original or protected backup changed; retirement stopped.'}
 $taskVerified.Add($taskSource)|Out-Null
}
$taskDirectories=@('postgres-data','storage','mp-sandbox-storage','mail','e2e-mail','java-e2e-runs','java-e2e-aborted','mp-backup-proof','pglogs')
$taskRemoveFiles=@()
foreach($taskName in $taskDirectories){
 $taskPath=[IO.Path]::GetFullPath((Join-Path $taskLocal $taskName))
 if(!$taskPath.StartsWith($taskLocal+'\',[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $taskPath -Leaf) -ne $taskName){throw 'Retirement scope check failed.'}
 if(!(Test-Path -LiteralPath $taskPath)){continue}
 foreach($taskItem in @((Get-Item -LiteralPath $taskPath -Force)) + @(Get-ChildItem -LiteralPath $taskPath -Recurse -Force)){
  if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Retirement refuses linked storage.'}
  if(!$taskItem.PSIsContainer -and !$taskVerified.Contains($taskItem.FullName)){throw 'An original file lacks a matching protected backup.'}
 }
}
foreach($taskFile in Get-ChildItem -LiteralPath $taskLocal -File -Force){
 if($taskFile.Name -eq 'private-runtime.json'){continue}
 if(!$taskVerified.Contains($taskFile.FullName)){throw 'A local evidence file changed after its protected copy.'}
 $taskRemoveFiles+=,$taskFile.FullName
}
$taskOriginalEnvironment=Join-Path $taskRoot '.env'
$taskOriginalArchive=Join-Path $taskPrivate 'archives/original-environment/app.env'
if(!(Select-String -LiteralPath $taskOriginalEnvironment -Pattern '^UNICARONA_PRIVATE_ROOT=' -Quiet)){
 if((Get-FileHash -LiteralPath $taskOriginalEnvironment -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $taskOriginalArchive -Algorithm SHA256).Hash){throw 'The original environment changed after staging.'}
}
if($Apply){
 # The non-secret pointer replaces the former secret-bearing .env only after recovery and readiness checks.
 [IO.File]::WriteAllText($taskOriginalEnvironment,'UNICARONA_PRIVATE_ROOT='+$taskPrivate.Replace('\','/')+"`n",[Text.UTF8Encoding]::new($false))
 foreach($taskName in $taskDirectories){$taskPath=[IO.Path]::GetFullPath((Join-Path $taskLocal $taskName));if(Test-Path -LiteralPath $taskPath){Remove-Item -LiteralPath $taskPath -Recurse -Force}}
 foreach($taskPath in $taskRemoveFiles){Remove-Item -LiteralPath $taskPath -Force}
}
$taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');applied=[bool]$Apply;verifiedOriginals=$taskVerified.Count;legacyDirectories=$taskDirectories.Count;rootEvidenceFiles=$taskRemoveFiles.Count;environmentIsPointer=[bool]$Apply;scope='Native application configuration, PostgreSQL, uploads, mail and historical local evidence. Separate Docker/WSL storage not included.'}
$taskReport|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskPrivate 'logs/originals-retirement.json') -Encoding utf8
$taskReport|ConvertTo-Json
