param([string]$ResultPath)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskLocal = Join-Path $taskRoot '.local'
if (!$ResultPath) { $ResultPath = Join-Path $taskLocal 'wsl-enable-result.json' }
$taskResult = [IO.Path]::GetFullPath($ResultPath)
if (!$taskResult.StartsWith(($taskLocal + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) { throw 'Result must stay inside workspace .local' }
$taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$taskPrincipal = New-Object Security.Principal.WindowsPrincipal($taskIdentity)
if (!$taskPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'An elevated administrator terminal is required' }
try {
    $taskResults = @()
    foreach ($taskFeature in @('VirtualMachinePlatform','Microsoft-Windows-Subsystem-Linux')) {
        $taskState = Get-WindowsOptionalFeature -Online -FeatureName $taskFeature
        if ($taskState.State -eq 'Enabled') { $taskResults += @{Feature=$taskFeature;Enabled=$true;RestartNeeded=$false} }
        else {
            $taskEnabled = Enable-WindowsOptionalFeature -Online -FeatureName $taskFeature -All -NoRestart
            $taskResults += @{Feature=$taskFeature;Enabled=$true;RestartNeeded=[bool]$taskEnabled.RestartNeeded}
        }
    }
    # WSL 2 also needs the hypervisor to launch at boot. Do not reboot the user's computer.
    $taskBoot = & "$env:SystemRoot\System32\bcdedit.exe" /enum '{current}' 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect hypervisor boot settings' }
    $taskHypervisorChanged = [bool]($taskBoot -match 'hypervisorlaunchtype\s+Off')
    if ($taskHypervisorChanged) {
        $taskBootChange = & "$env:SystemRoot\System32\bcdedit.exe" /set '{current}' hypervisorlaunchtype Auto 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Could not enable hypervisor boot' }
    }
    $taskHypervisorPresent = [bool](Get-CimInstance Win32_ComputerSystem).HypervisorPresent
    @{Success=$true;Features=$taskResults;HypervisorBootChanged=$taskHypervisorChanged;HypervisorPresent=$taskHypervisorPresent;RestartNeeded=(!$taskHypervisorPresent -or [bool]($taskResults | Where-Object {$_.RestartNeeded}))} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskResult -Encoding UTF8
} catch {
    @{Success=$false;ErrorType=$_.Exception.GetType().Name;HResult=$_.Exception.HResult} | ConvertTo-Json | Set-Content -LiteralPath $taskResult -Encoding UTF8
    exit 1
}
