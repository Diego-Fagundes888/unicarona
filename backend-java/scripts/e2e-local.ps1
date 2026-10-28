param(
    [switch]$LocalDatabase,
    [string[]]$Journey
)
$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testRoot = Join-Path $taskRoot 'backend-java/e2e'
$testFiles = @()
if ($Journey) {
    foreach ($name in $Journey) {
        if ($name -notmatch '^[a-z-]+$') { throw 'Invalid browser journey name' }
        $candidate = Join-Path $testRoot ($name + '.test.ts')
        if (!(Test-Path -LiteralPath $candidate)) { throw 'Browser journey not found' }
        $testFiles += $candidate
    }
} else {
    $testFiles = @(Get-ChildItem -LiteralPath $testRoot -Filter '*.test.ts' | Sort-Object Name | ForEach-Object FullName)
}
& (Join-Path $PSScriptRoot 'maven-local.ps1') -LocalDatabase:$LocalDatabase '-DskipTests' 'test-compile' 'dependency:build-classpath' '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/e2e-classpath.txt'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($env:JAVA_HOME) {
    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $env:JAVA_E2E_EXECUTABLE = Join-Path $env:JAVA_HOME ('bin/' + $javaName)
}
Push-Location $taskRoot
try {
    & node '--import' 'tsx' '--test' '--test-concurrency=1' @testFiles
    $taskExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $taskExitCode
