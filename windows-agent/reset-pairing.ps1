# Run locally on STUDIO PC in an elevated PowerShell window.
# This invalidates the existing Windows Agent pairing and creates a fresh one-time code.
$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open PowerShell as Administrator and run this script again.'
}

$serviceName = 'REVisionWindowsAgent'
$dataDir = Join-Path $env:ProgramData 'RevisionWindowsAgent'
$configPath = Join-Path $dataDir 'agent.json'
$pairingPath = Join-Path $dataDir 'agent.pairing-code.txt'

if (-not (Get-Service $serviceName -ErrorAction SilentlyContinue)) {
    throw "Windows Agent service '$serviceName' was not found."
}
if (-not (Test-Path $configPath)) {
    throw "Agent configuration was not found: $configPath"
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupPath = "$configPath.before-reset-$stamp"
Stop-Service $serviceName -Force
try {
    Copy-Item -LiteralPath $configPath -Destination $backupPath

    $random = [byte[]]::new(4)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $code = [Convert]::ToHexString($random)
    $expires = [DateTimeOffset]::UtcNow.AddHours(24).ToString('o')

    $newConfig = [ordered]@{
        PairingCode = $code
        PairingExpiresUtc = $expires
        ClientSecret = $null
    }
    $newConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    @(
        "RE:Vision pairing code (valid 24h): $code"
        'Run this file locally on STUDIO and keep the code private.'
    ) | Set-Content -LiteralPath $pairingPath -Encoding UTF8
}
finally {
    Start-Service $serviceName
    (Get-Service $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
}

Write-Host 'Pairing reset completed. Existing config backup:' -ForegroundColor Green
Write-Host $backupPath
Write-Host "New one-time code file: $pairingPath" -ForegroundColor Green
Get-Content -LiteralPath $pairingPath
Write-Warning 'The previous Android pairing key is now invalid. Pair again only on your trusted private LAN.'
