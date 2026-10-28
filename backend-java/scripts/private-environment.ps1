# Local environment resolution. Never echo the loaded dictionary or secret values.
# A Windows PowerShell child may inherit PowerShell 7's PSModulePath. Load its own ACL module explicitly.
$taskSecurityModule=Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1'
if(Test-Path -LiteralPath $taskSecurityModule){Import-Module -Name $taskSecurityModule -ErrorAction Stop}
function Get-TaskPrivateRoot([string]$ProjectRoot){
    $taskManifest=Join-Path $ProjectRoot '.local/private-runtime.json'
    if(!(Test-Path -LiteralPath $taskManifest)){
        $taskLegacy=Join-Path $ProjectRoot '.env'
        if((Test-Path -LiteralPath $taskLegacy) -and (Select-String -LiteralPath $taskLegacy -Pattern '^UNICARONA_PRIVATE_ROOT=' -Quiet)){throw 'Private runtime marker exists without its manifest.'}
        return $null
    }
    $taskValue=Get-Content -LiteralPath $taskManifest -Encoding utf8 -Raw | ConvertFrom-Json
    $taskExpected=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'UniCarona/Private'))
    if($taskValue.version -ne 1 -or [IO.Path]::GetFullPath([string]$taskValue.privateRoot) -ne $taskExpected -or [IO.Path]::GetFullPath([string]$taskValue.workspaceRoot) -ne $ProjectRoot){throw 'Unexpected private runtime manifest.'}
    $taskPointer=Join-Path $ProjectRoot '.env'
    if(Test-Path -LiteralPath $taskPointer){
        $taskPointerLines=@(Get-Content -LiteralPath $taskPointer -Encoding utf8 | Where-Object {$_ -and $_ -notmatch '^\s*#'})
        if($taskPointerLines.Count -ne 1 -or $taskPointerLines[0] -ne ('UNICARONA_PRIVATE_ROOT='+$taskExpected.Replace('\','/'))){throw 'After migration, the workspace .env must contain only the non-secret private-root pointer. Edit the protected config file instead.'}
    }
    $taskItem=Get-Item -LiteralPath $taskExpected -Force
    if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or [IO.DriveInfo]::new([IO.Path]::GetPathRoot($taskExpected)).DriveFormat -notin @('NTFS','ReFS')){throw 'Private runtime requires an ordinary directory on an ACL-capable filesystem.'}
    Assert-TaskPrivatePermissions $taskExpected
    return $taskExpected
}
function Assert-TaskPrivatePermissions([string]$Path){
    $taskTrusted=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
    foreach($taskRule in (Get-Acl -LiteralPath $Path).Access){
        if($taskRule.AccessControlType -eq 'Allow' -and $taskRule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $taskTrusted){throw 'A private runtime path grants access to an unapproved principal.'}
    }
}
function Get-TaskEnvironmentPath([string]$ProjectRoot,[ValidateSet('app','sandbox','docker')][string]$Kind='app'){
    $taskPrivateRoot=Get-TaskPrivateRoot $ProjectRoot
    if($taskPrivateRoot){
        $taskName=@{app='app.env';sandbox='mp-sandbox.env';docker='docker.env'}[$Kind]
        $taskPath=Join-Path $taskPrivateRoot ('config/'+$taskName)
        $taskConfig=Get-Item -LiteralPath (Join-Path $taskPrivateRoot 'config') -Force
        if(($taskConfig.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private configuration directory cannot be a link.'}
        Assert-TaskPrivatePermissions $taskConfig.FullName
        $taskItem=Get-Item -LiteralPath $taskPath -Force
        if(($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Private environment file cannot be a link.'}
        Assert-TaskPrivatePermissions $taskPath
        return $taskPath
    }
    return (Join-Path $ProjectRoot (@{app='.env';sandbox='.local/mp-sandbox.env';docker='.local/docker-java.env'}[$Kind]))
}
function Read-TaskEnvironment([string]$Path){
    $taskValues=@{}
    if(Test-Path -LiteralPath $Path){foreach($taskLine in Get-Content -LiteralPath $Path -Encoding utf8){if($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$taskValues[$Matches[1]]=$Matches[2]}}}
    return $taskValues
}
function Import-TaskEnvironment([string]$Path,[switch]$Overwrite){
    $taskValues=Read-TaskEnvironment $Path
    foreach($taskName in $taskValues.Keys){if($Overwrite -or ![Environment]::GetEnvironmentVariable($taskName,'Process')){[Environment]::SetEnvironmentVariable($taskName,$taskValues[$taskName],'Process')}}
}
function Merge-TaskSandboxEnvironment([hashtable]$Settings,[hashtable]$Shared){
    # A database environment owns its AES key ring. Never silently replace it with another environment's ring.
    $taskOwnEvidence=$Settings['PRIVACY_EVIDENCE_KEY_ID'] -or $Settings['PRIVACY_EVIDENCE_KEYS']
    foreach($taskName in $Shared.Keys){
        if($taskOwnEvidence -and $taskName -match '^PRIVACY_EVIDENCE_(KEY_ID|KEYS)$'){continue}
        if($taskName -match '^(MERCADO_PAGO_WEBHOOK_SECRET|MERCADO_PAGO_PUBLIC_KEY|EMAIL_PROVIDER|EMAIL_PROVIDER_API_KEY|EMAIL_FROM|EMAIL_VERIFICATION_MINUTES|EMAIL_SUPPORT_URL|EMAIL_PRIVACY_URL|SMTP_HOST|SMTP_PORT|SMTP_SECURE|SMTP_USER|SMTP_PASS|WORKER_IDLE_MS|LEGAL_[A-Z_]+|PRIVACY_[A-Z_]+)$'){$Settings[$taskName]=$Shared[$taskName]}
    }
}
