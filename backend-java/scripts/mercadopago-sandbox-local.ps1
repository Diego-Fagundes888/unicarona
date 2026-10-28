param(
    [ValidateSet('api','worker','migrate','seed')][string]$Mode = 'api',
    [string]$RuntimeJarPath
)
$ErrorActionPreference = 'Stop'
function Get-TaskJarHash([string]$taskPath) {
    $taskStream = [IO.File]::OpenRead($taskPath)
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-','').ToLowerInvariant() }
    finally { $taskHasher.Dispose(); $taskStream.Dispose() }
}
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivateRoot=Get-TaskPrivateRoot $taskRoot
$taskPrivate=Get-TaskEnvironmentPath $taskRoot 'sandbox'
if (!(Test-Path -LiteralPath $taskPrivate)) { throw 'Prepare o ambiente privado .local/mp-sandbox.env primeiro.' }
$taskSettings = @{}
foreach ($taskLine in Get-Content -LiteralPath $taskPrivate) {
    if ($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $taskSettings[$Matches[1]] = $Matches[2] }
}
# The official webhook secret may be provisioned privately without copying it into a command/chat.
$taskRootEnvironment=Get-TaskEnvironmentPath $taskRoot 'app'
if (Test-Path -LiteralPath $taskRootEnvironment) {
    Merge-TaskSandboxEnvironment $taskSettings (Read-TaskEnvironment $taskRootEnvironment)
}
$taskDatabase = [Uri]::new($taskSettings['DATABASE_URL'])
if ($taskSettings['PAYMENT_PROVIDER'] -ne 'mercadopago' -or $taskSettings['MERCADO_PAGO_ENVIRONMENT'] -ne 'test' -or $taskSettings['NODE_ENV'] -eq 'production' -or $taskDatabase.AbsolutePath -ne '/unicarona_mercadopago_sandbox' -or $taskDatabase.Host -notin @('localhost','127.0.0.1')) {
    throw 'Sandbox exige provedor Mercado Pago de teste e banco local exclusivo unicarona_mercadopago_sandbox.'
}
if (!$taskSettings['MERCADO_PAGO_WEBHOOK_SECRET'] -or $taskSettings['MERCADO_PAGO_WEBHOOK_SECRET'].Length -lt 32) { throw 'Configure o segredo oficial de webhook no ambiente privado. Não use um segredo inventado.' }
if (!$taskSettings['MERCADO_PAGO_ACCESS_TOKEN']) { throw 'Access Token de teste ausente no ambiente privado.' }
$taskPrevious = @{}
foreach ($taskName in $taskSettings.Keys) {
    $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName,'Process')
    [Environment]::SetEnvironmentVariable($taskName,$taskSettings[$taskName],'Process')
}
try {
    # Windows locks an open Jar. Run an immutable private copy so the target can be rebuilt.
    $taskSourceJar = if ($RuntimeJarPath) { [IO.Path]::GetFullPath($RuntimeJarPath) } else { Join-Path $taskRoot 'backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar' }
    if (!(Test-Path -LiteralPath $taskSourceJar)) { throw 'Compile o Jar antes de iniciar o sandbox.' }
    $taskJarHash = Get-TaskJarHash $taskSourceJar
    $taskRuntimeDirectory=if($taskPrivateRoot){Join-Path $taskPrivateRoot 'runtime/app'}else{Join-Path $taskRoot '.local/mp-runtime'}
    New-Item -ItemType Directory -Path $taskRuntimeDirectory -Force | Out-Null
    $taskRuntimeJar = Join-Path $taskRuntimeDirectory ('unicarona-' + $taskJarHash + '.jar')
    $taskJarMutex = New-Object Threading.Mutex($false,('Local\UniCaronaSandboxJar-' + $taskJarHash))
    $taskJarLock = $false
    try {
        try { $taskJarLock = $taskJarMutex.WaitOne(30000) }
        catch [Threading.AbandonedMutexException] { $taskJarLock = $true }
        if (!$taskJarLock) { throw 'Outra inicialização ainda está preparando o Jar privado.' }
        if (!(Test-Path -LiteralPath $taskRuntimeJar)) { [IO.File]::Copy($taskSourceJar,$taskRuntimeJar,$false) }
        if ((Get-TaskJarHash $taskRuntimeJar) -ne $taskJarHash) { throw 'O Jar privado de runtime não corresponde ao artefato compilado.' }
    } finally {
        if ($taskJarLock) { $taskJarMutex.ReleaseMutex() }
        $taskJarMutex.Dispose()
    }
    & (Join-Path $PSScriptRoot 'run-local.ps1') -Mode $Mode -RuntimeJarPath $taskRuntimeJar '--server.address=127.0.0.1' '--server.port=3005' ('--spring.config.import=file:'+($taskPrivate.Replace('\','/'))+'[.properties]')
    $taskExitCode = $LASTEXITCODE
} finally {
    foreach ($taskName in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process') }
}
exit $taskExitCode
