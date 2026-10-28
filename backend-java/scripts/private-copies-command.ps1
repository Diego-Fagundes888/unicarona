param(
 [ValidateSet('initialize','import-legacy','status','prune','rekey','reserve','finalize')][string]$Action='status',
 [int]$Batch=100,[string]$ResourceName,[string]$RecordId,[string]$RestorePoint,[string]$CreatedAt
)
$ErrorActionPreference='Stop'
$env:PSModulePath=Join-Path $PSHOME 'Modules'
$taskPrivate=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UniCarona/Private'))
if($PSScriptRoot -ne [IO.Path]::GetFullPath((Join-Path $taskPrivate 'runtime/scripts'))){throw 'Run the installed private operations launcher.'}
. (Join-Path $PSScriptRoot 'private-environment.ps1')
foreach($taskAnchor in @($taskPrivate,(Join-Path $taskPrivate 'config'),$PSScriptRoot,(Join-Path $taskPrivate 'runtime/operations'))){
 if(((Get-Item -LiteralPath $taskAnchor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private operations anchor cannot be redirected.'}
 Assert-TaskPrivatePermissions $taskAnchor
}
$taskConfiguration=Join-Path $taskPrivate 'config/app.env'
Assert-TaskPrivatePermissions $taskConfiguration
if(((Get-Item -LiteralPath $taskConfiguration -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private operations configuration cannot be redirected.'}
$taskSettings=Read-TaskEnvironment $taskConfiguration
if([IO.Path]::GetFullPath($taskSettings['PRIVACY_OPERATIONS_ROOT']) -ne $taskPrivate){throw 'Private operations root mismatch.'}
$taskPointer=Join-Path $taskPrivate 'runtime/operations/current.json';Assert-TaskPrivatePermissions $taskPointer
if(((Get-Item -LiteralPath $taskPointer -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private operations artifact pointer cannot be redirected.'}
$taskArtifact=Get-Content -LiteralPath $taskPointer -Encoding utf8 -Raw|ConvertFrom-Json
if($taskArtifact.version -ne 1 -or $taskArtifact.sha256 -notmatch '^[a-f0-9]{64}$'){throw 'Invalid private operations artifact.'}
$taskJar=Join-Path $taskPrivate ('runtime/operations/unicarona-'+$taskArtifact.sha256+'.jar');Assert-TaskPrivatePermissions $taskJar
if(((Get-Item -LiteralPath $taskJar -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskArtifact.sha256){throw 'Private operations artifact hash mismatch.'}
$taskArguments=@($Action,'--batch',[string]$Batch)
foreach($taskPair in @(@('name',$ResourceName),@('id',$RecordId),@('restore-point',$RestorePoint),@('created-at',$CreatedAt))){if($taskPair[1]){$taskArguments+=@(('--'+$taskPair[0]),[string]$taskPair[1])}}
$taskAllowed='^(NODE_ENV|TOKEN_PEPPER|STORAGE_PATH|FRONTEND_PATH|LEGAL_[A-Z_]+|PRIVACY_(OPERATIONS_ROOT|ERASURE_JOURNAL_PATH|BACKUP_RETENTION_DAYS|DELETION_GRACE_DAYS|REGISTRATION_RESTRICTION_DAYS|BACKUP_KEY_ID|BACKUP_KEYS|JOURNAL_KEY_ID|JOURNAL_KEYS))$'
$taskPrevious=@{}
foreach($taskName in @($taskSettings.Keys)+@('JWT_SECRET','PIN_ENCRYPTION_KEY','DATABASE_URL','MERCADO_PAGO_ACCESS_TOKEN','MERCADO_PAGO_WEBHOOK_SECRET','MERCADO_PAGO_PUBLIC_KEY','SMTP_PASS','SMTP_USER','EMAIL_PROVIDER_API_KEY','SEED_PASSWORD','PAYMENT_API_TOKEN','PAYMENT_WEBHOOK_SECRET')){
 if($taskPrevious.ContainsKey($taskName)){continue}
 $taskPrevious[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')
 [Environment]::SetEnvironmentVariable($taskName,$(if($taskName -match $taskAllowed){$taskSettings[$taskName]}else{$null}),'Process')
}
Push-Location (Join-Path $taskPrivate 'runtime/operations')
try{
 & (Join-Path $taskPrivate 'runtime/java/bin/java.exe') '-Dloader.main=com.unicarona.privacy.ops.PrivacyCopiesCommand' '-cp' $taskJar 'org.springframework.boot.loader.launch.PropertiesLauncher' @taskArguments
 $taskExitCode=$LASTEXITCODE
}finally{Pop-Location;foreach($taskName in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process')}}
exit $taskExitCode
