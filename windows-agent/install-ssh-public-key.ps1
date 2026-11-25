param(
    [Parameter(Mandatory = $true)]
    [string]$PublicKey
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Запусти PowerShell от имени администратора.'
}

$key = $PublicKey.Trim()
if ($key.Contains("`r") -or $key.Contains("`n")) {
    throw 'Передай одну строку публичного ключа без переносов.'
}

$parts = $key -split '\s+'
if ($parts.Count -lt 2 -or $parts[0] -notin @('ecdsa-sha2-nistp256', 'ssh-ed25519', 'ssh-rsa')) {
    throw 'Поддерживаются ключи OpenSSH: ecdsa-sha2-nistp256, ssh-ed25519 или ssh-rsa.'
}

try {
    [void][Convert]::FromBase64String($parts[1])
} catch {
    throw 'Вторая часть публичного ключа не является корректным Base64.'
}

$sshDirectory = Join-Path $env:ProgramData 'ssh'
$authorizedKeys = Join-Path $sshDirectory 'administrators_authorized_keys'
New-Item -ItemType Directory -Path $sshDirectory -Force | Out-Null

if (Test-Path -LiteralPath $authorizedKeys) {
    $existing = Get-Content -LiteralPath $authorizedKeys -ErrorAction Stop
    if ($existing -notcontains $key) {
        Add-Content -LiteralPath $authorizedKeys -Value $key -Encoding Ascii
    }
} else {
    [System.IO.File]::WriteAllText($authorizedKeys, $key + "`r`n", [System.Text.Encoding]::ASCII)
}

# Windows OpenSSH requires this file to be accessible only to SYSTEM and Administrators.
& icacls.exe $authorizedKeys /inheritance:r /grant:r '*S-1-5-32-544:F' '*S-1-5-18:F' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Не удалось установить ACL для administrators_authorized_keys.'
}

Write-Host 'Публичный SSH-ключ установлен.' -ForegroundColor Green
Write-Host "Файл: $authorizedKeys"
Write-Host 'Разрешения: только SYSTEM и локальная группа Administrators.'
Write-Host 'Перезапуск sshd обычно не требуется.';
