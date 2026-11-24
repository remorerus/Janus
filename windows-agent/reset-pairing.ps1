# Run in an elevated PowerShell window on STUDIO PC after installing the fixed RE:Vision APK.
# Resets ONLY the Android pairing secret/code. The saved command catalog is preserved.
$ErrorActionPreference = 'Stop'

$serviceName = 'REVisionWindowsAgent'
$dataDir = Join-Path $env:ProgramData 'RevisionWindowsAgent'
$configPath = Join-Path $dataDir 'agent.json'
$pairingFile = Join-Path $dataDir 'agent.pairing-code.txt'

if (-not (Test-Path $configPath)) {
    throw "Windows Agent configuration not found: $configPath"
}

$service = Get-Service -Name $serviceName -ErrorAction Stop
$wasRunning = $service.Status -eq 'Running'

try {
    if ($wasRunning) {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }

    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $code = -join (1..8 | ForEach-Object { '0123456789ABCDEF'[(Get-Random -Maximum 16)] })
    $config.PairingCode = $code
    $config.PairingExpiresUtc = [DateTime]::UtcNow.AddHours(24).ToString('o')
    $config.ClientSecret = $null

    $json = $config | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($configPath, $json, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText(
        $pairingFile,
        "RE:Vision pairing code (valid 24h): $code`r`nRun this file locally on STUDIO and keep the code private.",
        [System.Text.UTF8Encoding]::new($false)
    )
}
finally {
    if ($wasRunning) {
        Start-Service -Name $serviceName
    }
}

Write-Host ''
Write-Host 'Pairing reset complete. Your saved command catalog was not changed.' -ForegroundColor Green
Write-Host "New pairing code: $code" -ForegroundColor Cyan
Write-Host 'Enter this code and the STUDIO PC address in RE:Vision.'
