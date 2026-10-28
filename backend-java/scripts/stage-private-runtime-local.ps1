param()
$ErrorActionPreference='Stop'
# Reversible staging only. Does not stop services, change the active manifest or delete originals.
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskPrivate=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UniCarona/Private'))
$taskSid=[Security.Principal.WindowsIdentity]::GetCurrent().User
. (Join-Path $PSScriptRoot 'private-environment.ps1')
if([IO.DriveInfo]::new([IO.Path]::GetPathRoot($taskPrivate)).DriveFormat -notin @('NTFS','ReFS')){throw 'Private staging requires ACL-capable storage.'}
$taskOwnerPath=Join-Path $taskPrivate 'owner.json'
if(Test-Path -LiteralPath $taskPrivate){
 if(!(Test-Path -LiteralPath $taskOwnerPath)){throw 'Refusing to use an unmanaged existing private directory.'}
 $taskOwner=Get-Content -LiteralPath $taskOwnerPath -Raw | ConvertFrom-Json
 if($taskOwner.workspaceRoot -ne $taskRoot -or $taskOwner.sid -ne $taskSid.Value){throw 'Private directory belongs to another workspace or account.'}
 Assert-TaskPrivatePermissions $taskPrivate
}else{
 $taskAcl=[Security.AccessControl.DirectorySecurity]::new()
 $taskAcl.SetOwner($taskSid)
 $taskAcl.SetAccessRuleProtection($true,$false)
 foreach($taskPrincipal in @($taskSid,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))){
  $taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($taskPrincipal,[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
 }
 [IO.Directory]::CreateDirectory((Split-Path $taskPrivate)) | Out-Null
 if('System.IO.FileSystemAclExtensions' -as [type]){[IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($taskPrivate),$taskAcl)}
 else{[IO.Directory]::CreateDirectory($taskPrivate,$taskAcl) | Out-Null}
 Assert-TaskPrivatePermissions $taskPrivate
 [ordered]@{version=1;workspaceRoot=$taskRoot;sid=$taskSid.Value;createdAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $taskOwnerPath -Encoding utf8
}
$taskCopies=[Collections.Generic.List[object]]::new()
function Copy-TaskVerified([string]$Source,[string]$Target){
 $taskSourceFull=[IO.Path]::GetFullPath($Source);$taskTargetFull=[IO.Path]::GetFullPath($Target)
 if(!$taskTargetFull.StartsWith($taskPrivate+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Copy target escaped the owned private directory.'}
 $taskSourceItem=Get-Item -LiteralPath $taskSourceFull -Force
 if(($taskSourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Refusing to copy linked private storage.'}
 if($taskSourceItem.PSIsContainer){
  [IO.Directory]::CreateDirectory($taskTargetFull) | Out-Null
  foreach($taskEntry in Get-ChildItem -LiteralPath $taskSourceFull -Force){Copy-TaskVerified $taskEntry.FullName (Join-Path $taskTargetFull $taskEntry.Name)}
 }else{
  [IO.Directory]::CreateDirectory((Split-Path $taskTargetFull)) | Out-Null
  $taskSourceHash=(Get-FileHash -LiteralPath $taskSourceFull -Algorithm SHA256).Hash
  if(!(Test-Path -LiteralPath $taskTargetFull) -or (Get-FileHash -LiteralPath $taskTargetFull -Algorithm SHA256).Hash -ne $taskSourceHash){[IO.File]::Copy($taskSourceFull,$taskTargetFull,$true)}
  if((Get-FileHash -LiteralPath $taskTargetFull -Algorithm SHA256).Hash -ne $taskSourceHash){throw 'Private copy hash verification failed.'}
  $taskCopies.Add([ordered]@{source=$taskSourceFull;target=$taskTargetFull;sha256=$taskSourceHash;bytes=$taskSourceItem.Length})
 }
}
foreach($taskDirectory in @('config','logs','data','archives','backups','runtime')){[IO.Directory]::CreateDirectory((Join-Path $taskPrivate $taskDirectory)) | Out-Null}
# Preserve original environment bytes separately; active copies receive protected local paths.
foreach($taskKind in @('app','sandbox','docker')){
 $taskSource=Get-TaskEnvironmentPath $taskRoot $taskKind
 $taskName=@{app='app.env';sandbox='mp-sandbox.env';docker='docker.env'}[$taskKind]
 if(!(Test-Path -LiteralPath (Join-Path $taskPrivate ('config/'+$taskName)))){
  Copy-TaskVerified $taskSource (Join-Path $taskPrivate ('archives/original-environment/'+$taskName))
  $taskValues=Read-TaskEnvironment $taskSource
  $taskStorage=if($taskKind -eq 'sandbox'){'data/mp-sandbox-storage'}else{'data/storage'}
  foreach($taskPair in @(@('STORAGE_PATH',$taskStorage),@('DEV_MAIL_PATH','data/mail'),@('FRONTEND_PATH','runtime/frontend'),@('PRIVACY_ERASURE_JOURNAL_PATH','data/erasure-journal'))){$taskValues[$taskPair[0]]=(Join-Path $taskPrivate $taskPair[1]).Replace('\','/')}
  $taskLines=foreach($taskKey in ($taskValues.Keys | Sort-Object)){ $taskKey+'='+$taskValues[$taskKey] }
  [IO.File]::WriteAllLines((Join-Path $taskPrivate ('config/'+$taskName)),[string[]]$taskLines,[Text.UTF8Encoding]::new($false))
 }
}
$taskJava=Get-ChildItem (Join-Path $taskRoot '.local/java-tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
Copy-TaskVerified $taskJava.FullName (Join-Path $taskPrivate 'runtime/java')
Copy-TaskVerified (Join-Path $taskRoot '.local/java-tools/apache-maven-3.9.11') (Join-Path $taskPrivate 'runtime/maven')
$taskPostgres=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/unicarona-postgres/runtime/pgsql'
foreach($taskPart in @('bin','lib','share')){Copy-TaskVerified (Join-Path $taskPostgres $taskPart) (Join-Path $taskPrivate ('runtime/postgres/'+$taskPart))}
$taskBaseline='unicarona-02892a03ee550f3eba0227e82e5676e0817e9e6c4bfd63383c1b9743062e875f.jar'
Copy-TaskVerified (Join-Path $taskRoot ('.local/mp-runtime/'+$taskBaseline)) (Join-Path $taskPrivate ('runtime/app/'+$taskBaseline))
Copy-TaskVerified (Join-Path $taskRoot 'dist') (Join-Path $taskPrivate 'runtime/frontend')
$taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');phase='staged-not-active';expiresAt=[DateTime]::UtcNow.AddDays(35).ToString('o');copies=@($taskCopies.ToArray())}
$taskReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskPrivate 'archives/staging-manifest.json') -Encoding utf8
[ordered]@{phase='staged-not-active';verifiedFiles=$taskCopies.Count;privateAclChecked=$true;originalsPreserved=$true;servicesChanged=$false} | ConvertTo-Json -Compress
