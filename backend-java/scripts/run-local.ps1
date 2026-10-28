param(
    [ValidateSet('api','worker','migrate','seed','seed-scenarios')][string]$Mode = 'api',
    [string]$RuntimeJarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ApplicationArguments
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskJar = if ($RuntimeJarPath) { [IO.Path]::GetFullPath($RuntimeJarPath) } else { Join-Path $taskRoot 'backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar' }
if (!(Test-Path -LiteralPath $taskJar)) { throw 'Compile primeiro: npm run build ou ./backend-java/scripts/maven-local.ps1 -DskipTests package' }
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivateRoot=Get-TaskPrivateRoot $taskRoot
$taskPrivateEnvironment=Get-TaskEnvironmentPath $taskRoot 'app'
Import-TaskEnvironment $taskPrivateEnvironment
$taskJava = 'java'
$taskPortable = Get-ChildItem (Join-Path $taskRoot '.local/java-tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if($taskPrivateRoot){$taskJava=Join-Path $taskPrivateRoot 'runtime/java/bin/java.exe'}
elseif ($taskPortable) { $taskJava = Join-Path $taskPortable.FullName 'bin/java.exe' }
elseif ($env:JAVA_HOME) { $taskJava = Join-Path $env:JAVA_HOME 'bin/java.exe' }
Push-Location $taskRoot
try {
    $taskConfigArguments=@()
    if($taskPrivateRoot -and !(@($ApplicationArguments | Where-Object {$_ -like '--spring.config.import=*'}).Count)){$taskConfigArguments=@('--spring.config.import=file:'+($taskPrivateEnvironment.Replace('\','/'))+'[.properties]')}
    & $taskJava '-jar' $taskJar "--UNICARONA_MODE=$Mode" @taskConfigArguments @ApplicationArguments
    $taskExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $taskExitCode
