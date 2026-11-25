param([switch]$PurgeData)
$ErrorActionPreference = 'Stop'
$installDir = Join-Path $env:ProgramFiles 'RE:Vision SSH Agent'
$shortcut = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\RE-Vision SSH Agent.lnk'
Remove-Item -LiteralPath $installDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
if ($PurgeData) {
    Remove-Item -LiteralPath (Join-Path $env:ProgramData 'REVisionSshAgent') -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host 'RE:Vision SSH Agent removed. No background service needed uninstalling.'
if (-not $PurgeData) { Write-Host 'The saved command catalog was preserved.' }
