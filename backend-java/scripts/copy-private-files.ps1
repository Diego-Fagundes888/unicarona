# Dot-source only. Copies bytes with inherited destination ACLs and verifies every file.
function Copy-TaskPrivateTree([string]$Source,[string]$Target,[string]$PrivateRoot){
 $taskSourceFull=[IO.Path]::GetFullPath($Source);$taskTargetFull=[IO.Path]::GetFullPath($Target)
 if(!$taskTargetFull.StartsWith($PrivateRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Private copy destination escaped the owned root.'}
 $taskSourceItem=Get-Item -LiteralPath $taskSourceFull -Force
 if(($taskSourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Linked private storage cannot be copied.'}
 if($taskSourceItem.PSIsContainer){
  [IO.Directory]::CreateDirectory($taskTargetFull)|Out-Null
  foreach($taskChild in Get-ChildItem -LiteralPath $taskSourceFull -Force){Copy-TaskPrivateTree $taskChild.FullName (Join-Path $taskTargetFull $taskChild.Name) $PrivateRoot}
 }else{
  [IO.Directory]::CreateDirectory((Split-Path $taskTargetFull))|Out-Null
  $taskHash=(Get-FileHash -LiteralPath $taskSourceFull -Algorithm SHA256).Hash
  if(!(Test-Path -LiteralPath $taskTargetFull) -or (Get-FileHash -LiteralPath $taskTargetFull -Algorithm SHA256).Hash -ne $taskHash){[IO.File]::Copy($taskSourceFull,$taskTargetFull,$true)}
  if((Get-FileHash -LiteralPath $taskTargetFull -Algorithm SHA256).Hash -ne $taskHash){throw 'A protected copy does not match its source.'}
  [pscustomobject]@{source=$taskSourceFull;target=$taskTargetFull;sha256=$taskHash;bytes=$taskSourceItem.Length}
 }
}
