# Run in an elevated PowerShell window on the Windows 10/11 target PC.
$ErrorActionPreference = 'Stop'
$serviceName = 'REVisionWindowsAgent'
$installDir = Join-Path $env:ProgramFiles 'REVision Windows Agent'
$dataDir = Join-Path $env:ProgramData 'RevisionWindowsAgent'
$sshDataDir = Join-Path $env:ProgramData 'WinSshAgent'
$zip = Join-Path $PSScriptRoot 'Revision.WindowsAgent-win-x64.zip'
New-Item -ItemType Directory -Force -Path $installDir, $dataDir, $sshDataDir | Out-Null
# Windows Agent secrets remain readable only by SYSTEM and local Administrators.
& icacls.exe $dataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not secure Windows Agent data directory ACL.' }
# SSH catalog is written by the LocalSystem-hosted local manager and read by the SSH login user.
& icacls.exe $sshDataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-11:(OI)(CI)RX' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not set Win SSH Agent catalog permissions.' }
if (Test-Path $zip) {
    Expand-Archive -Path $zip -DestinationPath $installDir -Force
} elseif (Test-Path (Join-Path $PSScriptRoot 'Revision.WindowsAgent.exe')) {
    Get-ChildItem -Path $PSScriptRoot -File |
        Where-Object { $_.Name -ne 'Revision.WindowsAgent-win-x64.zip' } |
        Copy-Item -Destination $installDir -Force
} else {
    throw "Publish archive or extracted agent executable not found in $PSScriptRoot"
}
$exe = Join-Path $installDir 'Revision.WindowsAgent.exe'
$runner = Join-Path $installDir 'run-ssh-command.ps1'
if (-not (Test-Path $exe)) { throw "Agent executable missing: $exe" }
if (-not (Test-Path $runner)) { throw "Win SSH Agent runner missing: $runner" }
if (Get-Service $serviceName -ErrorAction SilentlyContinue) { Stop-Service $serviceName -Force -ErrorAction SilentlyContinue; sc.exe delete $serviceName | Out-Null; Start-Sleep -Seconds 2 }
sc.exe create $serviceName binPath= "`"$exe`"" start= auto DisplayName= "RE:Vision Windows Agent" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not register Windows service.' }
sc.exe description $serviceName "Authenticated local-network controls and local Win SSH Agent manager for RE:Vision." | Out-Null
New-NetFirewallRule -DisplayName 'RE:Vision Windows Agent (Private LAN)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47821 -Profile Private -Service $serviceName -ErrorAction SilentlyContinue | Out-Null
Start-Service $serviceName
Start-Sleep -Seconds 2
$pairingFile = Join-Path $dataDir 'agent.pairing-code.txt'
Write-Host "Windows Agent service installed. Pairing code file: $pairingFile" -ForegroundColor Green
if (Test-Path $pairingFile) { Get-Content $pairingFile }
Write-Host 'Win SSH Agent manager: http://127.0.0.1:47821/ssh-manager' -ForegroundColor Green
Write-Host "Win SSH Agent command catalog: $(Join-Path $sshDataDir 'commands.json')"
Write-Warning 'SSH execution requires Windows OpenSSH Server, the RE:Vision public key in authorized_keys for the SSH login account, and a generated private key retained in Android Keystore.'
Write-Warning 'Windows Agent HTTP is intended for a trusted private LAN only. Do not expose port 47821 to the Internet.'
