param([string]$RuntimeJarPath)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
. (Join-Path $PSScriptRoot 'copy-private-files.ps1')
$taskPrivate=Get-TaskPrivateRoot $taskRoot
if(!$taskPrivate){throw 'Protected local runtime must be active first.'}
$taskOwnerPath=Join-Path $taskPrivate 'owner.json';$taskOwner=Get-Content -LiteralPath $taskOwnerPath -Encoding utf8 -Raw|ConvertFrom-Json
if($taskOwner.workspaceRoot -ne $taskRoot -or $taskOwner.sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value){throw 'Private runtime ownership mismatch.'}
$taskSource=if($RuntimeJarPath){[IO.Path]::GetFullPath($RuntimeJarPath)}else{Join-Path $taskRoot 'backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskJarArchive=[IO.Compression.ZipFile]::OpenRead($taskSource)
try{if(!$taskJarArchive.GetEntry('BOOT-INF/classes/com/unicarona/privacy/ops/PrivacyCopiesCommand.class') -or !$taskJarArchive.GetEntry('org/springframework/boot/loader/launch/PropertiesLauncher.class')){throw 'Compile the file-only privacy operations command before installing it.'}}finally{$taskJarArchive.Dispose()}
$taskEnvironment=Get-TaskEnvironmentPath $taskRoot 'app';$taskValues=Read-TaskEnvironment $taskEnvironment
$taskLegacyNames=@('TOKEN_PEPPER','PIN_ENCRYPTION_KEY','JWT_SECRET','SMTP_PASS','EMAIL_PROVIDER_API_KEY','MERCADO_PAGO_ACCESS_TOKEN','MERCADO_PAGO_WEBHOOK_SECRET')
$taskBefore=@{};foreach($taskName in $taskLegacyNames){$taskBefore[$taskName]=$taskValues[$taskName]}
function New-TaskPrivacyKey {
 $taskBytes=New-Object byte[] 32;$taskGenerator=[Security.Cryptography.RandomNumberGenerator]::Create()
 try{$taskGenerator.GetBytes($taskBytes);return [Convert]::ToBase64String($taskBytes).TrimEnd('=').Replace('+','-').Replace('/','_')}finally{$taskGenerator.Dispose();[Array]::Clear($taskBytes,0,$taskBytes.Length)}
}
foreach($taskPurpose in @('BACKUP','JOURNAL')){
 $taskIdName='PRIVACY_'+$taskPurpose+'_KEY_ID';$taskKeysName='PRIVACY_'+$taskPurpose+'_KEYS'
 if(!$taskValues[$taskIdName] -and !$taskValues[$taskKeysName]){
  $taskKeyId='privacy-'+[DateTime]::UtcNow.ToString('yyyyMMdd');$taskValues[$taskIdName]=$taskKeyId;$taskValues[$taskKeysName]=@{$taskKeyId=(New-TaskPrivacyKey)}|ConvertTo-Json -Compress
 }elseif(!$taskValues[$taskIdName] -or !$taskValues[$taskKeysName]){throw 'A privacy key ring is only partly configured; repair it without exposing its keys.'}
}
$taskValues['PRIVACY_OPERATIONS_ROOT']=$taskPrivate.Replace('\','/')
foreach($taskName in $taskLegacyNames){if($taskValues[$taskName] -cne $taskBefore[$taskName]){throw 'Existing authentication/payment/SMTP keys must not change during this installation.'}}
$taskLines=foreach($taskName in ($taskValues.Keys|Sort-Object)){$taskName+'='+$taskValues[$taskName]}
$taskTemporary=Join-Path $taskPrivate 'config/app.env.pending'
[IO.File]::WriteAllLines($taskTemporary,[string[]]$taskLines,[Text.UTF8Encoding]::new($false));Assert-TaskPrivatePermissions $taskTemporary
[IO.File]::Replace($taskTemporary,$taskEnvironment,[NullString]::Value)
$taskOwner|Add-Member -NotePropertyName application -NotePropertyValue 'unicarona-private' -Force
[IO.File]::WriteAllText($taskOwnerPath,($taskOwner|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$taskHash=(Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash.ToLowerInvariant();$taskRuntime=Join-Path $taskPrivate 'runtime/operations'
[IO.Directory]::CreateDirectory($taskRuntime)|Out-Null
Copy-TaskPrivateTree $taskSource (Join-Path $taskRuntime ('unicarona-'+$taskHash+'.jar')) $taskPrivate | Out-Null
foreach($taskName in @('private-copies-command.ps1','private-environment.ps1')){Copy-TaskPrivateTree (Join-Path $PSScriptRoot $taskName) (Join-Path $taskPrivate ('runtime/scripts/'+$taskName)) $taskPrivate|Out-Null}
[IO.File]::WriteAllText((Join-Path $taskRuntime 'current.json'),([ordered]@{version=1;sha256=$taskHash}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
[ordered]@{installed=$true;scope='File-only privacy operations; native API/worker artifact and database unchanged';existingCredentialsPreserved=$true;dedicatedSigningKeysConfigured=$true}|ConvertTo-Json
exit 0
