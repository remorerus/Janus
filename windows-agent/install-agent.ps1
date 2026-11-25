# Run in an elevated PowerShell window on the Windows 10/11 target PC.
$ErrorActionPreference = 'Stop'
$serviceName = 'REVisionWindowsAgent'
$installDir = Join-Path $env:ProgramFiles 'REVision Windows Agent'
$dataDir = Join-Path $env:ProgramData 'RevisionWindowsAgent'
$zip = Join-Path $PSScriptRoot 'Revision.WindowsAgent-win-x64.zip'
if (-not (Test-Path $zip)) { throw "Publish archive not found: $zip" }
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$sshDataDir = Join-Path $env:ProgramData 'WinSshAgent'
New-Item -ItemType Directory -Force -Path $sshDataDir | Out-Null
& icacls.exe $sshDataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)RX' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not secure Win SSH Agent command directory.' }
# Only LocalSystem (service identity) and local Administrators may read agent secrets.
& icacls.exe $dataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not secure agent data directory ACL.' }
Expand-Archive -Path $zip -DestinationPath $installDir -Force
Copy-Item (Join-Path $PSScriptRoot 'run-ssh-command.ps1') (Join-Path $installDir 'run-ssh-command.ps1') -Force
$exe = Join-Path $installDir 'Revision.WindowsAgent.exe'
if (-not (Test-Path $exe)) { throw "Agent executable missing: $exe" }
if (Get-Service $serviceName -ErrorAction SilentlyContinue) { Stop-Service $serviceName -Force -ErrorAction SilentlyContinue; sc.exe delete $serviceName | Out-Null; Start-Sleep -Seconds 2 }
sc.exe create $serviceName binPath= "`"$exe`"" start= auto DisplayName= "RE:Vision Windows Agent" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not register Windows service.' }
sc.exe description $serviceName "Authenticated local-network power controls for RE:Vision." | Out-Null
New-NetFirewallRule -DisplayName 'RE:Vision Windows Agent (Private LAN)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47821 -Profile Private -Service $serviceName -ErrorAction SilentlyContinue | Out-Null
Start-Service $serviceName
Start-Sleep -Seconds 2
$pairingFile = Join-Path $dataDir 'agent.pairing-code.txt'
Write-Host "Agent service installed. Pairing code file: $pairingFile" -ForegroundColor Green
if (Test-Path $pairingFile) { Get-Content $pairingFile }
Write-Warning 'Prototype uses HTTP on a trusted private LAN. Do not expose port 47821 to the Internet or public networks.'
