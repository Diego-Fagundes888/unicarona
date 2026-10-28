$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivate=Get-TaskPrivateRoot $taskRoot
if(!$taskPrivate){throw 'Protected local runtime must be active first.'}
$taskResults=@()
foreach($taskKind in @('app','sandbox')){
 $taskPath=Get-TaskEnvironmentPath $taskRoot $taskKind
 $taskValues=Read-TaskEnvironment $taskPath
 $taskOriginal=@{};foreach($taskName in $taskValues.Keys){$taskOriginal[$taskName]=$taskValues[$taskName]}
 $taskId=$taskValues['PRIVACY_EVIDENCE_KEY_ID'];$taskRing=$taskValues['PRIVACY_EVIDENCE_KEYS'];$taskAdded=$false
 if(!$taskId -and !$taskRing){
  $taskBytes=New-Object byte[] 32;$taskGenerator=[Security.Cryptography.RandomNumberGenerator]::Create()
  try{$taskGenerator.GetBytes($taskBytes);$taskSecret=[BitConverter]::ToString($taskBytes).Replace('-','').ToLowerInvariant()}
  finally{$taskGenerator.Dispose();[Array]::Clear($taskBytes,0,$taskBytes.Length)}
  $taskId='evidence-'+[DateTime]::UtcNow.ToString('yyyyMMdd')
  $taskValues['PRIVACY_EVIDENCE_KEY_ID']=$taskId
  $taskValues['PRIVACY_EVIDENCE_KEYS']=@{$taskId=$taskSecret}|ConvertTo-Json -Compress
  $taskSecret=$null;$taskAdded=$true
 }elseif(!$taskId -or !$taskRing){throw 'Evidence key ring is partially configured; existing keys were not replaced.'}
 else{
  if($taskId -notmatch '^[a-zA-Z0-9_-]{1,48}$'){throw 'Invalid evidence key identifier.'}
  $taskParsed=$taskRing|ConvertFrom-Json
  $taskProperties=@($taskParsed.PSObject.Properties)
  if($taskProperties.Count -lt 1 -or $taskProperties.Count -gt 8 -or !($taskProperties|Where-Object Name -CEQ $taskId)){throw 'Invalid existing evidence key ring.'}
  foreach($taskProperty in $taskProperties){
   if($taskProperty.Name -notmatch '^[a-zA-Z0-9_-]{1,48}$' -or $taskProperty.Value -isnot [string] -or $taskProperty.Value -notmatch '^[a-fA-F0-9]{64}$' -or $taskProperty.Value -ieq $taskValues['PIN_ENCRYPTION_KEY']){throw 'Evidence encryption keys must be valid and separate from the financial key.'}
  }
 }
 foreach($taskName in $taskOriginal.Keys){if($taskValues[$taskName] -cne $taskOriginal[$taskName]){throw 'Existing environment values must not change when adding evidence keys.'}}
 if($taskAdded){
  $taskTemporary=Join-Path (Split-Path -Parent $taskPath) ('evidence-key-'+[Guid]::NewGuid().ToString()+'.pending')
  try{
   $taskLines=foreach($taskName in ($taskValues.Keys|Sort-Object)){$taskName+'='+$taskValues[$taskName]}
   [IO.File]::WriteAllLines($taskTemporary,[string[]]$taskLines,[Text.UTF8Encoding]::new($false));Assert-TaskPrivatePermissions $taskTemporary
   [IO.File]::Replace($taskTemporary,$taskPath,[NullString]::Value)
  }finally{if(Test-Path -LiteralPath $taskTemporary){Remove-Item -LiteralPath $taskTemporary -Force}}
 }
 $taskResults+=@{environment=$taskKind;dedicatedEncryptionKeysConfigured=$true;existingValuesPreserved=$true;added=$taskAdded}
}
$taskResults|ConvertTo-Json -Depth 3
exit 0
