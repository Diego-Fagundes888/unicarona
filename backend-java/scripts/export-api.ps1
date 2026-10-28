param([string]$Origin = 'http://127.0.0.1:3000')
$ErrorActionPreference = 'Stop'
$taskUri = [uri]$Origin
if ($taskUri.Scheme -ne 'http' -or $taskUri.Host -notin @('localhost','127.0.0.1')) { throw 'A exportação local requer a API em loopback.' }
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskDocument = Invoke-RestMethod -Uri ($Origin.TrimEnd('/') + '/api/docs/json') -TimeoutSec 20
$taskDestination = Join-Path $taskRoot 'docs/api/openapi-java.json'
[IO.File]::WriteAllText($taskDestination,($taskDocument | ConvertTo-Json -Depth 100),[Text.UTF8Encoding]::new($false))
Write-Output 'OpenAPI Java exportado para docs/api/openapi-java.json.'
