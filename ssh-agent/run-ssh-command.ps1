param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{4,512}$')]
    [string]$PhraseBase64
)
$ErrorActionPreference = 'Stop'
$encoded = $PhraseBase64.Replace('-', '+').Replace('_', '/')
switch ($encoded.Length % 4) { 2 { $encoded += '==' } 3 { $encoded += '=' } }
$phrase = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)).Trim()
if ([string]::IsNullOrWhiteSpace($phrase)) { throw 'Empty voice phrase.' }

# Windows Agent owns the SSH catalog and launches UI actions in the active desktop session.
$body = @{ phrase = $phrase } | ConvertTo-Json -Compress
try {
    $result = Invoke-RestMethod -Uri 'http://127.0.0.1:47821/v1/ssh-manager/execute' -Method Post -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 15
    if (-not $result.accepted) { throw 'Windows Agent did not accept the SSH command.' }
    Write-Output ('REVISION_SSH_COMMAND=' + $result.command)
    Write-Output ('REVISION_SSH_EXECUTED=' + $result.executed)
}
catch {
    $detail = $_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($detail)) { $detail = $_.Exception.Message }
    throw ('RE:Vision SSH Agent: ' + $detail)
}
