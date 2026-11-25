# Run in an elevated PowerShell window on the Windows 10/11 target PC.
$ErrorActionPreference = 'Stop'
$serviceName = 'REVisionWindowsAgent'
$installDir = Join-Path $env:ProgramFiles 'REVision Windows Agent'
$dataDir = Join-Path $env:ProgramData 'RevisionWindowsAgent'
$sshDataDir = Join-Path $env:ProgramData 'WinSshAgent'
$zip = Join-Path $PSScriptRoot 'Revision.WindowsAgent-win-x64.zip'
$exe = Join-Path $installDir 'Revision.WindowsAgent.exe'

# Stop the service before replacing its executable. Persistent pairing data is never deleted.
$existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $existingService) {
    if ($existingService.Status -ne 'Stopped') {
        Write-Host "Stopping $serviceName before updating files..." -ForegroundColor Yellow
        Stop-Service -Name $serviceName -Force
    }
    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 500
        $existingService = Get-Service -Name $serviceName -ErrorAction Stop
    } while ($existingService.Status -ne 'Stopped' -and (Get-Date) -lt $deadline)
    if ($existingService.Status -ne 'Stopped') {
        throw "Service $serviceName did not stop. No files were replaced. Close the service manually and run the installer again."
    }
    $serviceInfo = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
    if ($serviceInfo -and $serviceInfo.ProcessId -gt 0) {
        $process = Get-Process -Id $serviceInfo.ProcessId -ErrorAction SilentlyContinue
        if ($process) {
            try { Wait-Process -Id $process.Id -Timeout 20 -ErrorAction Stop }
            catch { throw "The old agent process is still running (PID $($process.Id)). No files were replaced." }
        }
    }
}

New-Item -ItemType Directory -Force -Path $installDir, $dataDir, $sshDataDir | Out-Null
& icacls.exe $dataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not secure Windows Agent data directory ACL.' }
& icacls.exe $sshDataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-11:(OI)(CI)RX' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not set Win SSH Agent catalog permissions.' }

$legacyCatalog = Join-Path $env:ProgramData 'REVisionSshAgent\commands.json'
$newCatalog = Join-Path $sshDataDir 'commands.json'
if (-not (Test-Path $newCatalog) -and (Test-Path $legacyCatalog)) {
    Copy-Item -LiteralPath $legacyCatalog -Destination $newCatalog
    Write-Host 'Imported existing SSH command catalog into Win SSH Agent.' -ForegroundColor Green
}

if (Test-Path $zip) {
    Expand-Archive -Path $zip -DestinationPath $installDir -Force
} elseif (Test-Path (Join-Path $PSScriptRoot 'Revision.WindowsAgent.exe')) {
    Get-ChildItem -Path $PSScriptRoot -File |
        Where-Object { $_.Name -ne 'Revision.WindowsAgent-win-x64.zip' } |
        Copy-Item -Destination $installDir -Force
} else {
    throw "Publish archive or extracted agent executable not found in $PSScriptRoot"
}

if (-not (Test-Path $exe)) { throw "Agent executable missing: $exe" }
$runner = Join-Path $installDir 'run-ssh-command.ps1'
if (-not (Test-Path $runner)) { Write-Warning 'Optional SSH command runner is absent; Windows Agent controls can still be installed.' }

if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
    & sc.exe config $serviceName binPath= "`"$exe`"" start= auto DisplayName= "RE:Vision Windows Agent" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not update Windows service configuration.' }
} else {
    & sc.exe create $serviceName binPath= "`"$exe`"" start= auto DisplayName= "RE:Vision Windows Agent" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not register Windows service.' }
}
& sc.exe description $serviceName "Authenticated local-network controls and local Win SSH Agent manager for RE:Vision." | Out-Null

if (-not (Get-NetFirewallRule -DisplayName 'RE:Vision Windows Agent (Private LAN)' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'RE:Vision Windows Agent (Private LAN)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47821 -Profile Private -Service $serviceName | Out-Null
}

if (-not (Get-NetFirewallRule -DisplayName 'RE:Vision LAN Discovery (Private LAN)' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'RE:Vision LAN Discovery (Private LAN)' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 47822 -Profile Private -Service $serviceName | Out-Null
}

Start-Service -Name $serviceName
$deadline = (Get-Date).AddSeconds(20)
do {
    Start-Sleep -Milliseconds 500
    $serviceStatus = (Get-Service -Name $serviceName).Status
} while ($serviceStatus -ne 'Running' -and (Get-Date) -lt $deadline)
if ($serviceStatus -ne 'Running') { throw "Windows Agent service did not reach Running state (current state: $serviceStatus)." }

$pairingFile = Join-Path $dataDir 'agent.pairing-code.txt'
Write-Host 'Windows Agent service installed and running. Existing pairing data was preserved.' -ForegroundColor Green
Write-Host "Pairing code file: $pairingFile"
if (Test-Path $pairingFile) { Write-Host 'A pairing-code file exists. Check pairing status in the local manager; do not share the code.' }
Write-Host 'RE:Vision unified manager: http://127.0.0.1:47821/manager (Windows Agent + SSH tabs)' -ForegroundColor Green
Write-Host "Win SSH Agent command catalog: $newCatalog"
Write-Warning 'SSH execution requires Windows OpenSSH Server, the RE:Vision public key in authorized_keys for the SSH login account, and a generated private key retained in Android Keystore.'
Write-Warning 'Windows Agent HTTP and UDP discovery are intended for a trusted private LAN only. Do not expose ports 47821/47822 to the Internet.'
