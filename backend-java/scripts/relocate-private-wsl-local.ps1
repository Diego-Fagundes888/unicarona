param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivate=Get-TaskPrivateRoot $taskRoot
if(!$taskPrivate){throw 'Protected native storage must be active first.'}
$taskSource=[IO.Path]::GetFullPath((Join-Path $taskRoot '.local/docker-wsl'))
$taskDestination=[IO.Path]::GetFullPath((Join-Path $taskPrivate 'data/wsl-Docker'))
$taskDistro=@(Get-ChildItem HKCU:/Software/Microsoft/Windows/CurrentVersion/Lxss | ForEach-Object {Get-ItemProperty $_.PSPath} | Where-Object {$_.DistributionName -eq 'UniCaronaDocker'})
if($taskDistro.Count -ne 1){throw 'Expected exactly the project-owned WSL distribution.'}
$taskBase=[IO.Path]::GetFullPath($taskDistro[0].BasePath.TrimStart('\','?'))
if($taskBase -ne $taskSource -or !$taskSource.StartsWith($taskRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or !$taskDestination.StartsWith($taskPrivate+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'WSL migration source/destination scope mismatch.'}
foreach($taskItem in @((Get-Item -LiteralPath $taskSource -Force))+@(Get-ChildItem -LiteralPath $taskSource -Force -Recurse)){if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Cannot relocate linked WSL storage.'}}
$taskState=(& wsl --list --verbose | Out-String).Replace([string][char]0,'')
if($taskState -notmatch 'UniCaronaDocker\s+Stopped\s+2'){throw 'The project distribution must be stopped before an offline relocation.'}
$taskDisk=Join-Path $taskSource 'ext4.vhdx'
$taskLength=(Get-Item -LiteralPath $taskDisk).Length
# Preserve the downloaded, public vendor archive on the source disk before removing reproducible cache material.
# This is installer cache only; application data and the separate Windows PostgreSQL service are untouched.
$taskCache=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/unicarona-postgres'))
$taskZip=Join-Path $taskCache 'postgresql-windows.zip'
$taskVendorDirectory=Join-Path $taskRoot '.local/vendor-cache'
[IO.Directory]::CreateDirectory($taskVendorDirectory)|Out-Null
$taskVendorZip=Join-Path $taskVendorDirectory 'postgresql-windows.zip'
if(Test-Path -LiteralPath $taskZip){
 $taskZipHash=(Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash
 [IO.File]::Copy($taskZip,$taskVendorZip,$true)
 if((Get-FileHash -LiteralPath $taskVendorZip -Algorithm SHA256).Hash -ne $taskZipHash){throw 'Vendor cache recovery archive hash mismatch.'}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $taskArchive=[IO.Compression.ZipFile]::OpenRead($taskVendorZip)
 try{if(!@($taskArchive.Entries|Where-Object {$_.FullName -like 'pgsql/pgAdmin 4/*'}).Count){throw 'Vendor recovery archive does not contain the optional cache component.'}}finally{$taskArchive.Dispose()}
 $taskOptionalCache=[IO.Path]::GetFullPath((Join-Path $taskCache 'runtime/pgsql/pgAdmin 4'))
 if(!$taskOptionalCache.StartsWith($taskCache+'\',[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $taskOptionalCache -Leaf) -ne 'pgAdmin 4'){throw 'Optional installer cache deletion scope mismatch.'}
 if(Test-Path -LiteralPath $taskOptionalCache){
  foreach($taskEntry in @((Get-Item -LiteralPath $taskOptionalCache -Force))+@(Get-ChildItem -LiteralPath $taskOptionalCache -Recurse -Force)){if(($taskEntry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Optional installer cache contains a linked path.'}}
  Remove-Item -LiteralPath $taskOptionalCache -Recurse -Force
 }
 Remove-Item -LiteralPath $taskZip -Force
}
if([IO.DriveInfo]::new([IO.Path]::GetPathRoot($taskPrivate)).AvailableFreeSpace -lt ($taskLength+1500MB)){throw 'Insufficient free space to relocate the private WSL disk with a safe reserve.'}
if(Test-Path -LiteralPath $taskDestination){throw 'Refusing to overwrite an existing WSL migration destination.'}
[IO.Directory]::CreateDirectory($taskDestination)|Out-Null
Assert-TaskPrivatePermissions $taskDestination
$taskHash=(Get-FileHash -LiteralPath $taskDisk -Algorithm SHA256).Hash
$taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');distribution='UniCaronaDocker';source=$taskSource;destination=$taskDestination;bytes=$taskLength;sha256=$taskHash;phase='offline-copy-pending';bootValidated=$false}
$taskReport|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskPrivate 'archives/wsl-migration.json') -Encoding utf8
# Official WSL relocation updates its registration; no hand-written registry edits or unregister operation.
$taskResult=(& wsl --manage UniCaronaDocker --move $taskDestination 2>&1 | Out-String).Replace([string][char]0,'')
if($LASTEXITCODE -ne 0){throw 'The official WSL relocation failed; inspect the owned source and destination before retrying.'}
$taskDistroAfter=Get-ItemProperty $taskDistro[0].PSPath
if([IO.Path]::GetFullPath($taskDistroAfter.BasePath.TrimStart('\','?')) -ne $taskDestination){throw 'WSL registration did not move to protected storage.'}
$taskNewDisk=Join-Path $taskDestination 'ext4.vhdx'
if((Get-FileHash -LiteralPath $taskNewDisk -Algorithm SHA256).Hash -ne $taskHash){throw 'Relocated WSL disk hash mismatch.'}
Assert-TaskPrivatePermissions $taskNewDisk
if(Test-Path -LiteralPath $taskDisk){throw 'WSL left a second disk on the source volume; inspect before claiming completion.'}
$taskReport.phase='offline-relocation-verified';$taskReport.at=[DateTime]::UtcNow.ToString('o')
$taskReport|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskPrivate 'archives/wsl-migration.json') -Encoding utf8
[ordered]@{phase=$taskReport.phase;diskBytesPreserved=$true;registrationUpdated=$true;privateAclChecked=$true;bootValidated=$false;scope='Offline WSL storage protection only; Windows virtualization remains unavailable.'}|ConvertTo-Json -Compress
