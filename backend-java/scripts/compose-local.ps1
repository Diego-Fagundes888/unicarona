param(
    [ValidateSet('up','down','ps','restart','build','logs')][string]$Action = 'ps',
    [string]$EnvFile,
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
if(!$EnvFile){$EnvFile=Get-TaskEnvironmentPath $taskRoot 'docker'}
$taskEnvPath = if ([IO.Path]::IsPathRooted($EnvFile)) { [IO.Path]::GetFullPath($EnvFile) } else { [IO.Path]::GetFullPath((Join-Path $taskRoot $EnvFile)) }
if (!(Test-Path -LiteralPath $taskEnvPath)) { throw 'Defina o arquivo privado de ambiente para Compose; veja backend-java/README.md.' }
& (Join-Path $PSScriptRoot 'start-docker-local.ps1')
$taskLinuxRoot = (& wsl -d UniCaronaDocker -u root --exec wslpath -u $taskRoot).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível converter o diretório do projeto para WSL.' }
$taskLinuxEnv = (& wsl -d UniCaronaDocker -u root --exec wslpath -u $taskEnvPath).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível converter o caminho do ambiente para WSL.' }
[string[]]$ComposeArguments = switch ($Action) {
    'up' { if ($NoBuild) { @('up','-d') } else { @('up','--build','-d') } }
    'logs' { @('logs','--tail','100') }
    default { @($Action) }
}
& wsl -d UniCaronaDocker -u root --exec docker compose --project-directory $taskLinuxRoot --env-file $taskLinuxEnv -f "$taskLinuxRoot/compose.yaml" @ComposeArguments
exit $LASTEXITCODE
