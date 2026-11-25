# Run in an elevated PowerShell window on STUDIO PC.
$ErrorActionPreference = 'Stop'
$installDir = Join-Path $env:ProgramFiles 'REVision SSH Agent'
$dataDir = Join-Path $env:ProgramData 'REVisionSshAgent'
New-Item -ItemType Directory -Force -Path $installDir, $dataDir | Out-Null
& icacls.exe $dataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not set permissions on the SSH command catalog.' }
$catalogPath = Join-Path $dataDir 'commands.json'
$legacyCatalog = Join-Path $env:ProgramData 'WinSshAgent\commands.json'
if (-not (Test-Path $catalogPath) -and (Test-Path $legacyCatalog)) {
    Copy-Item $legacyCatalog $catalogPath
    Write-Host 'Imported the existing Win SSH Agent command catalog.' -ForegroundColor Green
}
$exe = Join-Path $PSScriptRoot 'Revision.SshAgent.exe'
$runner = Join-Path $PSScriptRoot 'run-ssh-command.ps1'
if (-not (Test-Path $exe)) { throw "SSH Agent executable not found: $exe" }
if (-not (Test-Path $runner)) { throw "SSH command runner not found: $runner" }
Copy-Item $exe (Join-Path $installDir 'Revision.SshAgent.exe') -Force
Copy-Item $runner (Join-Path $installDir 'run-ssh-command.ps1') -Force
$startMenu = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs'
$shortcutPath = Join-Path $startMenu 'RE-Vision SSH Agent.lnk'
$wsh = New-Object -ComObject WScript.Shell
$shortcut = $wsh.CreateShortcut($shortcutPath)
$shortcut.TargetPath = Join-Path $installDir 'Revision.SshAgent.exe'
$shortcut.WorkingDirectory = $installDir
$shortcut.Description = 'Настройка пользовательских SSH-команд RE:Vision'
$shortcut.Save()
Write-Host 'RE:Vision SSH Agent installed.' -ForegroundColor Green
Write-Host "Start Menu shortcut: $shortcutPath"
Write-Host "Command catalog: $(Join-Path $dataDir 'commands.json')"
Write-Host 'No Windows service, scheduled task, tray process, or background listener was created.'
Write-Warning 'Voice execution requires Windows OpenSSH Server and the RE:Vision public key in the authorized_keys of the same Windows account.'
