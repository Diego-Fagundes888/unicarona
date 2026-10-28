param([ValidateSet('start','status')][string]$Mode='status')
$ErrorActionPreference='Stop'
# Development only: resume the existing isolated cluster; never initialize or replace a database.
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'private-environment.ps1')
$taskPrivateRoot=Get-TaskPrivateRoot $taskRoot
$taskExpected=if($taskPrivateRoot){Join-Path $taskPrivateRoot 'data/postgres'}else{Join-Path $taskRoot '.local/postgres-data'}
$taskData=(Resolve-Path -LiteralPath $taskExpected).Path
if($taskData -ne [IO.Path]::GetFullPath($taskExpected) -or (Get-Content -LiteralPath (Join-Path $taskData 'PG_VERSION') -Raw).Trim() -ne '18'){throw 'Unexpected local PostgreSQL data directory.'}
$taskTools=if($taskPrivateRoot){Join-Path $taskPrivateRoot 'runtime/postgres/bin'}else{Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/unicarona-postgres/runtime/pgsql/bin'}
$taskControl=Join-Path $taskTools 'pg_ctl.exe'
$taskReady=Join-Path $taskTools 'pg_isready.exe'
if(!(Test-Path -LiteralPath $taskControl) -or !(Test-Path -LiteralPath $taskReady)){throw 'Configured local PostgreSQL runtime is unavailable.'}
& $taskControl -D $taskData status | Out-Null
$taskStatus=$LASTEXITCODE
if($taskStatus -eq 3 -and $Mode -eq 'start'){
    # The Windows service on 5432 is separate. Always supply this cluster's port and loopback explicitly.
    $taskServerOptions='-p 55432 -h 127.0.0.1'
    if($taskPrivateRoot){
        $taskPostgresLogs=Join-Path $taskPrivateRoot 'logs/postgres'
        [IO.Directory]::CreateDirectory($taskPostgresLogs)|Out-Null
        $taskServerOptions+=' -c log_directory="'+$taskPostgresLogs.Replace('\','/')+'"'
    }
    & $taskControl -D $taskData -o $taskServerOptions -l $(if($taskPrivateRoot){Join-Path $taskPrivateRoot 'logs/postgres.log'}else{Join-Path $taskRoot '.local/postgres-local.log'}) start -w -t 30
    $taskStatus=$LASTEXITCODE
}
if($taskStatus -ne 0){throw 'The isolated local PostgreSQL cluster is stopped or unavailable.'}
& $taskReady -h 127.0.0.1 -p 55432 | Out-Null
if($LASTEXITCODE -ne 0){throw 'The local PostgreSQL cluster is not ready on its configured port.'}
$taskPid=Get-Content -LiteralPath (Join-Path $taskData 'postmaster.pid')
if($taskPid.Count -lt 4 -or $taskPid[3].Trim() -ne '55432'){throw 'The cluster is running with an unexpected port; inspect it before starting application services.'}
[ordered]@{ready=$true;mode=$Mode;host='127.0.0.1';port=55432;scope='existing-isolated-development-cluster'} | ConvertTo-Json -Compress
