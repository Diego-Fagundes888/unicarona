param([switch]$Apply)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskResolvedPrivate=Get-TaskPrivateRoot $taskRoot
if($taskResolvedPrivate){
 $taskChecked=0;$taskVanished=0;$taskEnumerationErrors=@()
 $taskChildren=@(Get-ChildItem -LiteralPath $taskResolvedPrivate -Force -Recurse -ErrorAction SilentlyContinue -ErrorVariable taskEnumerationErrors)
 foreach($taskEnumerationError in $taskEnumerationErrors){if($taskEnumerationError.CategoryInfo.Category -eq 'ObjectNotFound'){$taskVanished++}else{throw 'Private-path enumeration failed; permissions have not been verified.'}}
 foreach($taskItem in @((Get-Item -LiteralPath $taskResolvedPrivate -Force)) + $taskChildren){
  if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Linked paths cannot form part of the protected runtime.'}
  try{Assert-TaskPrivatePermissions $taskItem.FullName}catch{if(!(Test-Path -LiteralPath $taskItem.FullName)){$taskVanished++;continue};throw}
  $taskChecked++
 }
 $taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');supported=$true;privateRuntime=$true;checkedPaths=$taskChecked;vanishedDuringLiveScan=$taskVanished;unapprovedAllowRules=0;scope='Owned private runtime and enumerated children; live PostgreSQL/temp files may disappear and new files inherit the checked parent ACL. Development source workspace is not ACL protected.'}
 $taskReport|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $taskResolvedPrivate 'logs/permissions-audit.json') -Encoding utf8
 $taskReport|ConvertTo-Json
 return
}
$taskPrivate=Join-Path $taskRoot '.local'
$taskCurrent=[Security.Principal.WindowsIdentity]::GetCurrent().User
$taskBroad=@('S-1-1-0','S-1-5-11','S-1-5-32-545')
$taskTrusted=@($taskCurrent,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'),[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
function Get-TaskBroadCount($taskAcl){
 $taskCount=0
 foreach($taskRule in $taskAcl.Access){if($taskRule.AccessControlType -eq 'Allow' -and $taskRule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -in $taskBroad){$taskCount++}}
 return $taskCount
}
$taskTargets=@((Join-Path $taskRoot '.env'),$taskPrivate)
foreach($taskAnchor in $taskTargets){
 $taskDrive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($taskAnchor))
 if($taskDrive.DriveFormat -notin @('NTFS','ReFS')){
  [ordered]@{applied=$false;supported=$false;code='FILESYSTEM_ACL_UNSUPPORTED';format=$taskDrive.DriveFormat;instruction='Place private environment, data and evidence on a filesystem with enforceable access controls before production.'}|ConvertTo-Json
  if($Apply){throw 'The private workspace filesystem cannot enforce the requested ACLs.'}
  return
 }
}
$taskBackup=@()
foreach($taskTarget in $taskTargets){
 if(!(Test-Path -LiteralPath $taskTarget)){continue}
 $taskItem=Get-Item -LiteralPath $taskTarget -Force
 if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private permission anchor cannot be a junction or symbolic link.'}
 $taskResolved=[IO.Path]::GetFullPath($taskItem.FullName)
 if(!$taskResolved.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Private permission anchor is outside the workspace.'}
 $taskAcl=Get-Acl -LiteralPath $taskResolved
 $taskBackup+=@{path=$taskResolved;sddl=$taskAcl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)}
 if($Apply){
  $taskAcl.SetAccessRuleProtection($true,$true)
  foreach($taskRule in @($taskAcl.Access)){if($taskRule.AccessControlType -eq 'Allow' -and $taskRule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -in $taskBroad){$taskAcl.RemoveAccessRuleSpecific($taskRule)}}
  $taskInheritance=if($taskItem.PSIsContainer){[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'}else{[Security.AccessControl.InheritanceFlags]::None}
  foreach($taskSid in $taskTrusted){$taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($taskSid,[Security.AccessControl.FileSystemRights]::FullControl,$taskInheritance,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))}
  Set-Acl -LiteralPath $taskResolved -AclObject $taskAcl
 }
}
if($Apply){$taskBackup|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $taskPrivate ('privacy-acl-before-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')+'.private.json')) -Encoding utf8}
$taskChecks=@((Join-Path $taskRoot '.env'),$taskPrivate,(Join-Path $taskPrivate 'mp-sandbox.env'),(Join-Path $taskPrivate 'mp-sandbox-storage'),(Join-Path $taskPrivate 'storage'))
$taskResults=foreach($taskPath in $taskChecks){if(Test-Path -LiteralPath $taskPath){[pscustomobject]@{target=[IO.Path]::GetFileName($taskPath);broadAllowRules=(Get-TaskBroadCount (Get-Acl -LiteralPath $taskPath))}}}
$taskReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');applied=[bool]$Apply;scope='Local .env and private workspace directory; named service grants preserved';checks=@($taskResults)}
$taskReport|ConvertTo-Json -Depth 4
if($Apply -and @($taskResults|Where-Object {$_.broadAllowRules -gt 0}).Count -gt 0){throw 'A private child still grants broad access; inspect explicit/protected child permissions before declaring this complete.'}
