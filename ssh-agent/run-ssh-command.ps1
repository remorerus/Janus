param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{4,512}$')]
    [string]$PhraseBase64
)
$ErrorActionPreference = 'Stop'
# Standalone RE:Vision SSH Agent catalog (kept separate from Windows Agent).
$catalogPath = Join-Path $env:ProgramData 'REVisionSshAgent\commands.json'
if (-not (Test-Path -LiteralPath $catalogPath)) {
    throw 'RE:Vision SSH Agent catalog not found. Launch RE:Vision SSH Agent and save a command first.'
}
$encoded = $PhraseBase64.Replace('-', '+').Replace('_', '/')
switch ($encoded.Length % 4) { 2 { $encoded += '==' } 3 { $encoded += '=' } }
$phrase = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)).Trim()
if ([string]::IsNullOrWhiteSpace($phrase)) { throw 'Empty voice phrase.' }
$catalog = @(Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json)
$entry = $catalog | Where-Object { [string]::Equals($_.Phrase, $phrase, [StringComparison]::OrdinalIgnoreCase) } | Select-Object -First 1
if ($null -eq $entry) { throw "No RE:Vision SSH Agent command matches phrase: $phrase" }
Write-Output ("REVISION_SSH_COMMAND=" + $entry.Name)
if ($entry.Shell -eq 'powershell') {
    & ([scriptblock]::Create([string]$entry.Command))
} elseif ($entry.Shell -eq 'cmd') {
    & $env:ComSpec /d /s /c ([string]$entry.Command)
} else {
    throw 'Unsupported command shell in RE:Vision SSH Agent catalog.'
}
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
