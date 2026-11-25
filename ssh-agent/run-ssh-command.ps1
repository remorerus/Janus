param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{4,512}$')]
    [string]$PhraseBase64
)
$ErrorActionPreference = 'Stop'
$catalogPath = Join-Path $env:ProgramData 'REVisionSshAgent\commands.json'
if (-not (Test-Path -LiteralPath $catalogPath)) {
    throw 'RE:Vision SSH Agent catalog not found. Open RE:Vision SSH Agent and save a command first.'
}
$encoded = $PhraseBase64.Replace('-', '+').Replace('_', '/')
switch ($encoded.Length % 4) { 2 { $encoded += '==' } 3 { $encoded += '=' } }
$phrase = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)).Trim()
if ([string]::IsNullOrWhiteSpace($phrase)) { throw 'Empty voice phrase.' }
$catalog = @(Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json)
$entry = $catalog | Where-Object { [string]::Equals([string]$_.Phrase, $phrase, [StringComparison]::OrdinalIgnoreCase) } | Select-Object -First 1
if ($null -eq $entry) { throw "No RE:Vision SSH Agent command matches phrase: $phrase" }
$action = if ([string]::IsNullOrWhiteSpace([string]$entry.Action)) { 'script' } else { [string]$entry.Action }
Write-Output ("REVISION_SSH_COMMAND=" + $entry.Name)
switch ($action) {
    'script' {
        if ($entry.Shell -eq 'cmd') {
            & $env:ComSpec /d /s /c ([string]$entry.Command)
        } else {
            & ([scriptblock]::Create([string]$entry.Command))
        }
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    'launch' {
        $exe = [Environment]::ExpandEnvironmentVariables([string]$entry.Executable)
        if (-not (Test-Path -LiteralPath $exe)) { throw "Application not found: $exe" }
        $params = @{ FilePath = $exe; PassThru = $true }
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.Arguments)) { $params.ArgumentList = [string]$entry.Arguments }
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.WorkingDirectory)) {
            $wd = [Environment]::ExpandEnvironmentVariables([string]$entry.WorkingDirectory)
            if (-not (Test-Path -LiteralPath $wd -PathType Container)) { throw "Working directory not found: $wd" }
            $params.WorkingDirectory = $wd
        }
        $process = Start-Process @params
        Write-Output ("STARTED_PID=" + $process.Id)
    }
    'close' {
        $name = [IO.Path]::GetFileNameWithoutExtension([string]$entry.ProcessName)
        $processes = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
        if ($processes.Count -eq 0) { Write-Output "PROCESS_NOT_RUNNING=$name" }
        else {
            $processes | Stop-Process -Force -ErrorAction Stop
            Write-Output ("CLOSED_COUNT=" + $processes.Count)
        }
    }
    'restart' {
        $name = [IO.Path]::GetFileNameWithoutExtension([string]$entry.ProcessName)
        $processes = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
        if ($processes.Count -gt 0) { $processes | Stop-Process -Force -ErrorAction Stop; Start-Sleep -Milliseconds 700 }
        $exe = [Environment]::ExpandEnvironmentVariables([string]$entry.Executable)
        if (-not (Test-Path -LiteralPath $exe)) { throw "Application not found: $exe" }
        $params = @{ FilePath = $exe; PassThru = $true }
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.Arguments)) { $params.ArgumentList = [string]$entry.Arguments }
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.WorkingDirectory)) {
            $wd = [Environment]::ExpandEnvironmentVariables([string]$entry.WorkingDirectory)
            if (-not (Test-Path -LiteralPath $wd -PathType Container)) { throw "Working directory not found: $wd" }
            $params.WorkingDirectory = $wd
        }
        $process = Start-Process @params
        Write-Output ("RESTARTED_PID=" + $process.Id)
    }
    default { throw "Unsupported action in SSH command catalog: $action" }
}
