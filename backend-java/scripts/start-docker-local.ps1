$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskPidPath = Join-Path $taskRoot '.local/docker-wsl-keeper.pid'
$taskExisting = $null
if (Test-Path -LiteralPath $taskPidPath) {
    $taskKeeperId = 0
    if ([int]::TryParse(([IO.File]::ReadAllText($taskPidPath)).Trim(), [ref]$taskKeeperId)) {
        $taskExisting = Get-CimInstance Win32_Process -Filter "ProcessId=$taskKeeperId" |
            Where-Object { $_.Name -eq 'wsl.exe' -and $_.CommandLine -like '*UniCaronaDocker*sleep*infinity*' }
    }
}
if (!$taskExisting) {
    $taskKeeper = Start-Process -FilePath wsl.exe -ArgumentList @('-d','UniCaronaDocker','-u','root','--exec','sleep','infinity') -WindowStyle Hidden -PassThru
    [IO.Directory]::CreateDirectory((Join-Path $taskRoot '.local')) | Out-Null
    [IO.File]::WriteAllText($taskPidPath, [string]$taskKeeper.Id)
}
$taskDeadline = [datetime]::UtcNow.AddSeconds(45)
do {
    try {
        $taskDocker = Invoke-RestMethod -Uri 'http://127.0.0.1:52375/version' -TimeoutSec 3
        $env:DOCKER_HOST = 'tcp://127.0.0.1:52375'
        $env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = '/var/run/docker.sock'
        Write-Output "Docker $($taskDocker.Version) disponível no WSL 2 para esta sessão."
        return
    } catch { Start-Sleep -Seconds 1 }
} while ([datetime]::UtcNow -lt $taskDeadline)
throw 'Docker não iniciou. Este helper requer a distribuição UniCaronaDocker já configurada.'
