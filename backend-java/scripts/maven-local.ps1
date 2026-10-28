param(
    [switch]$LocalDatabase,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments
)
$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivateRoot=Get-TaskPrivateRoot $taskRoot
$privateEnvironment=Get-TaskEnvironmentPath $taskRoot 'app'
# Build/tests need a disposable database URL, never the runtime's SMTP/payment/encryption credentials.
$portableJava = Get-ChildItem (Join-Path $taskRoot '.local/java-tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if($taskPrivateRoot){$env:JAVA_HOME=Join-Path $taskPrivateRoot 'runtime/java'}
elseif ($portableJava) { $env:JAVA_HOME = $portableJava.FullName }
$portableMaven = if($taskPrivateRoot){Join-Path $taskPrivateRoot 'runtime/maven/bin/mvn.cmd'}else{Join-Path $taskRoot '.local/java-tools/apache-maven-3.9.11/bin/mvn.cmd'}
if ($LocalDatabase) {
    if($taskPrivateRoot -and !$env:TEST_PG_TOOLS_PATH){$env:TEST_PG_TOOLS_PATH=Join-Path $taskPrivateRoot 'runtime/postgres/bin'}
    if (!$env:TEST_JAVA_DATABASE_URL) {
        $taskDatabaseUrl=if($env:DATABASE_URL){$env:DATABASE_URL}else{(Read-TaskEnvironment $privateEnvironment)['DATABASE_URL']}
        if(!$taskDatabaseUrl){throw 'Configure a local database URL for the dedicated Java test database.'}
        $isolatedConnection = [System.UriBuilder]::new($taskDatabaseUrl)
        $isolatedConnection.Path = '/unicarona_java_test'
        $env:TEST_JAVA_DATABASE_URL = $isolatedConnection.Uri.AbsoluteUri
    }
}
if (!$MavenArguments) { $MavenArguments = @('verify') }
# Maven accepts a mixed list containing a misspelled class when another class matches.
# Reject missing literal selectors before a partial test run can be mistaken for complete evidence.
$taskTestNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach($taskTestPath in [IO.Directory]::EnumerateFiles((Join-Path $PSScriptRoot '../src/test/java'),'*.java',[IO.SearchOption]::AllDirectories)){[void]$taskTestNames.Add([IO.Path]::GetFileNameWithoutExtension($taskTestPath))}
foreach($taskArgument in $MavenArguments){
    if($taskArgument -match '^-D(?:test|it\.test)=(.+)$'){
        foreach($taskSelection in $Matches[1].Split(',')){
            $taskLiteral=$taskSelection.Split('#')[0]
            if($taskLiteral -match '^[A-Za-z_$][A-Za-z0-9_$.]*$'){
                $taskSimple=$taskLiteral.Split('.')[-1].Split('$')[0]
                if(!$taskTestNames.Contains($taskSimple)){throw ('Selected test class does not exist: '+$taskSimple+'. No Maven run was started.')}
            }
        }
    }
}
if($taskPrivateRoot){
    $taskReports=Join-Path $taskPrivateRoot 'logs/java-reports'
    $taskTestTemp=Join-Path $taskPrivateRoot 'data/java-test-temp'
    [IO.Directory]::CreateDirectory($taskReports)|Out-Null
    [IO.Directory]::CreateDirectory($taskTestTemp)|Out-Null
    $MavenArguments=@(('-Dunicarona.testArtifactsRoot='+$taskReports.Replace('\','/')),('-Dunicarona.testTempRoot='+$taskTestTemp.Replace('\','/')))+@($MavenArguments)
}
Push-Location $taskRoot
try {
    if (Test-Path -LiteralPath $portableMaven) { & $portableMaven -f backend-java/pom.xml -B -ntp @MavenArguments }
    else { & mvn -f backend-java/pom.xml -B -ntp @MavenArguments }
    $taskExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $taskExitCode
