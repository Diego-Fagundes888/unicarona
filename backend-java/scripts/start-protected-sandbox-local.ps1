param(
 [ValidateSet('api','worker')][string]$Mode='api',
 [string]$JarHash='02892a03ee550f3eba0227e82e5676e0817e9e6c4bfd63383c1b9743062e875f'
)
$ErrorActionPreference='Stop'
# Resolve built-in modules from this PowerShell version, without inherited/user module search paths.
$env:PSModulePath=Join-Path $PSHOME 'Modules'
# Install this launcher plus private-environment.ps1 under Private/runtime/scripts before using it.
$taskPrivate=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UniCarona/Private'))
$taskExpectedScriptRoot=[IO.Path]::GetFullPath((Join-Path $taskPrivate 'runtime/scripts'))
if($PSScriptRoot -ne $taskExpectedScriptRoot){throw 'Start the installed protected launcher, not its development source.'}
. (Join-Path $PSScriptRoot 'private-environment.ps1')
foreach($taskAnchor in @($taskPrivate,$taskExpectedScriptRoot,(Join-Path $taskPrivate 'config'))){
 if(((Get-Item -LiteralPath $taskAnchor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Protected runtime anchor cannot be linked.'}
 Assert-TaskPrivatePermissions $taskAnchor
}
$taskOwner=Get-Content -LiteralPath (Join-Path $taskPrivate 'owner.json') -Raw | ConvertFrom-Json
if($taskOwner.sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value){throw 'Private runtime belongs to another account.'}
$taskApp=Join-Path $taskPrivate 'config/app.env';$taskSandbox=Join-Path $taskPrivate 'config/mp-sandbox.env'
foreach($taskFile in @($taskApp,$taskSandbox)){
 if(((Get-Item -LiteralPath $taskFile -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private configuration cannot be linked.'}
 Assert-TaskPrivatePermissions $taskFile
}
$taskSettings=Read-TaskEnvironment $taskSandbox
$taskApplication=Read-TaskEnvironment $taskApp
Merge-TaskSandboxEnvironment $taskSettings $taskApplication
$taskDatabase=[Uri]::new($taskSettings['DATABASE_URL'])
if($taskSettings['PAYMENT_PROVIDER'] -ne 'mercadopago' -or $taskSettings['MERCADO_PAGO_ENVIRONMENT'] -ne 'test' -or $taskSettings['NODE_ENV'] -eq 'production' -or $taskDatabase.AbsolutePath -ne '/unicarona_mercadopago_sandbox' -or $taskDatabase.Host -notin @('localhost','127.0.0.1') -or $taskDatabase.Port -ne 55432){throw 'Protected launcher is restricted to the existing isolated test sandbox.'}
if($JarHash -notmatch '^[a-f0-9]{64}$'){throw 'Expected an immutable artifact SHA256.'}
$taskJar=Join-Path $taskPrivate ('runtime/app/unicarona-'+$JarHash+'.jar')
Assert-TaskPrivatePermissions $taskJar
if((Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $JarHash){throw 'Protected application artifact hash mismatch.'}
foreach($taskName in $taskSettings.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSettings[$taskName],'Process')}
$taskJava=Join-Path $taskPrivate 'runtime/java/bin/java.exe'
Assert-TaskPrivatePermissions $taskJava
Push-Location (Join-Path $taskPrivate 'runtime/app')
try{
 & $taskJava '-jar' $taskJar "--UNICARONA_MODE=$Mode" '--server.address=127.0.0.1' '--server.port=3005' ('--spring.config.import=file:'+($taskSandbox.Replace('\','/'))+'[.properties]')
 $taskExitCode=$LASTEXITCODE
}finally{Pop-Location}
exit $taskExitCode
