param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{4,512}$')]
    [string]$PhraseBase64
)
$ErrorActionPreference = 'Stop'
$catalogPath = Join-Path $env:ProgramData 'WinSshAgent\commands.json'
$legacyCatalogPath = Join-Path $env:ProgramData 'REVisionSshAgent\commands.json'
if (-not (Test-Path -LiteralPath $catalogPath)) { $catalogPath = $legacyCatalogPath }
if (-not (Test-Path -LiteralPath $catalogPath)) {
    throw 'RE:Vision SSH Agent catalog not found. Open the local SSH Agent manager and save a command first.'
}
$encoded = $PhraseBase64.Replace('-', '+').Replace('_', '/')
switch ($encoded.Length % 4) { 2 { $encoded += '==' } 3 { $encoded += '=' } }
$phrase = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded)).Trim()
if ([string]::IsNullOrWhiteSpace($phrase)) { throw 'Empty voice phrase.' }
$catalog = @(Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json)
$entry = $catalog | Where-Object {
    [string]::Equals([string]$_.Phrase, $phrase, [StringComparison]::OrdinalIgnoreCase) -or
    @($_.Aliases) -contains $phrase
} | Select-Object -First 1
if ($null -eq $entry) { throw "No RE:Vision SSH Agent command matches phrase: $phrase" }
Write-Output ("REVISION_SSH_COMMAND=" + $entry.Name)

function Start-ConfiguredProcess([string]$path, [string]$arguments, [string]$workingDirectory) {
    $exe = [Environment]::ExpandEnvironmentVariables($path)
    if (-not (Test-Path -LiteralPath $exe)) { throw "File not found: $exe" }
    $params = @{ FilePath = $exe; PassThru = $true }
    if (-not [string]::IsNullOrWhiteSpace($arguments)) { $params.ArgumentList = $arguments }
    if (-not [string]::IsNullOrWhiteSpace($workingDirectory)) {
        $wd = [Environment]::ExpandEnvironmentVariables($workingDirectory)
        if (-not (Test-Path -LiteralPath $wd -PathType Container)) { throw "Working directory not found: $wd" }
        $params.WorkingDirectory = $wd
    }
    return Start-Process @params
}
function Get-TargetProcesses([string]$target) {
    $value = [IO.Path]::GetFileNameWithoutExtension($target)
    if ([string]::IsNullOrWhiteSpace($value)) { return @() }
    return @(Get-Process -Name $value -ErrorAction SilentlyContinue)
}
function Send-VirtualKey([int]$vk) {
    [RevisionSshNative]::keybd_event([byte]$vk, [byte]0, [uint32]0, [UIntPtr]::Zero)
    [RevisionSshNative]::keybd_event([byte]$vk, [byte]0, [uint32]2, [UIntPtr]::Zero)
}
if (-not ('RevisionSshNative' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class RevisionSshNative {
 [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
}
'@
}
$kind = if (-not [string]::IsNullOrWhiteSpace([string]$entry.Kind)) { [string]$entry.Kind } elseif (-not [string]::IsNullOrWhiteSpace([string]$entry.Action)) { [string]$entry.Action } else { 'script' }
switch ($kind) {
    'app' {
        $process = Start-ConfiguredProcess ([string]$entry.Target) ([string]$entry.Arguments) ([string]$entry.WorkingDirectory)
        Write-Output ("STARTED_PID=" + $process.Id)
    }
    'bat' {
        $bat = [Environment]::ExpandEnvironmentVariables([string]$entry.Target)
        if (-not (Test-Path -LiteralPath $bat)) { throw "BAT file not found: $bat" }
        $process = Start-Process -FilePath $env:ComSpec -ArgumentList ('/c ""' + $bat + '""') -WorkingDirectory (Split-Path -Parent $bat) -PassThru
        Write-Output ("STARTED_BAT_PID=" + $process.Id)
    }
    'close' {
        $processes = Get-TargetProcesses ([string]$entry.Target)
        if ($processes.Count -eq 0) { Write-Output 'PROCESS_NOT_RUNNING' }
        else { $processes | Stop-Process -Force; Write-Output ("CLOSED_COUNT=" + $processes.Count) }
    }
    'restart' {
        $processName = if ([string]::IsNullOrWhiteSpace([string]$entry.ProcessName)) { [IO.Path]::GetFileNameWithoutExtension([string]$entry.Target) } else { [string]$entry.ProcessName }
        $processes = @(Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($processName)) -ErrorAction SilentlyContinue)
        if ($processes.Count -gt 0) { $processes | Stop-Process -Force; Start-Sleep -Milliseconds 700 }
        $process = Start-ConfiguredProcess ([string]$entry.Target) ([string]$entry.Arguments) ([string]$entry.WorkingDirectory)
        Write-Output ("RESTARTED_PID=" + $process.Id)
    }
    'media' {
        $mediaKeys = @{
            GLOBAL_PLAYPAUSE = 0xB3; GLOBAL_NEXT = 0xB0; GLOBAL_PREV = 0xB1;
            GLOBAL_STOP = 0xB2; GLOBAL_MUTE = 0xAD; GLOBAL_VOLUP = 0xAF; GLOBAL_VOLDWN = 0xAE;
            '/PLAY' = 0xB3; '/PAUSE' = 0xB3; '/PLAYPAUSE' = 0xB3; '/NEXT' = 0xB0;
            '/PREV' = 0xB1; '/STOP' = 0xB2; '/MUTE' = 0xAD; '/VOLUP' = 0xAF; '/VOLDWN' = 0xAE
        }
        $key = [string]$entry.Arguments
        if (-not $mediaKeys.ContainsKey($key)) { throw "Unsupported media action: $key" }
        Send-VirtualKey ([int]$mediaKeys[$key])
        Write-Output ("MEDIA_ACTION=" + $key)
    }
    'hotkey' {
        $map = @{ CTRL=0x11; ALT=0x12; SHIFT=0x10; WIN=0x5B; ESC=0x1B; ENTER=0x0D; TAB=0x09; SPACE=0x20; DELETE=0x2E; BACKSPACE=0x08; UP=0x26; DOWN=0x28; LEFT=0x25; RIGHT=0x27; HOME=0x24; END=0x23 }
        $tokens = ([string]$entry.Target).ToUpperInvariant().Split('+')
        $codes = @()
        foreach ($token in $tokens) {
            if ($map.ContainsKey($token)) { $codes += [int]$map[$token] }
            elseif ($token -match '^F([1-9]|1[0-9]|2[0-4])$') { $codes += (0x70 + [int]$Matches[1] - 1) }
            elseif ($token -match '^[A-Z]$') { $codes += [int][char]$token }
            elseif ($token -match '^[0-9]$') { $codes += (0x30 + [int]$token) }
            else { throw "Unsupported hotkey token: $token" }
        }
        foreach ($vk in $codes) { [RevisionSshNative]::keybd_event([byte]$vk,0,0,[UIntPtr]::Zero) }
        for ($i=$codes.Count-1; $i -ge 0; $i--) { [RevisionSshNative]::keybd_event([byte]$codes[$i],0,2,[UIntPtr]::Zero) }
        Write-Output ("HOTKEY=" + $entry.Target)
    }
    'window' {
        if ($entry.Arguments -in @('next','previous')) {
            $vkAlt=0x12; $vkTab=0x09
            [RevisionSshNative]::keybd_event($vkAlt,0,0,[UIntPtr]::Zero)
            [RevisionSshNative]::keybd_event($vkTab,0,0,[UIntPtr]::Zero)
            [RevisionSshNative]::keybd_event($vkTab,0,2,[UIntPtr]::Zero)
            [RevisionSshNative]::keybd_event($vkAlt,0,2,[UIntPtr]::Zero)
            Write-Output ("WINDOW_ACTION=" + $entry.Arguments); break
        }
        $target = [string]$entry.Target
        $proc = Get-TargetProcesses $target | Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } | Select-Object -First 1
        if ($null -eq $proc) {
            $proc = Get-Process | Where-Object { $_.MainWindowTitle -and $_.MainWindowTitle -like ("*" + $target + "*") } | Select-Object -First 1
        }
        if ($null -eq $proc) { throw "Window not found: $target" }
        $hwnd = $proc.MainWindowHandle
        switch ([string]$entry.Arguments) {
            'focus' { [RevisionSshNative]::SetForegroundWindow($hwnd) | Out-Null }
            'minimize' { [RevisionSshNative]::ShowWindow($hwnd,6) | Out-Null }
            'maximize' { [RevisionSshNative]::ShowWindow($hwnd,3) | Out-Null }
            'restore' { [RevisionSshNative]::ShowWindow($hwnd,9) | Out-Null; [RevisionSshNative]::SetForegroundWindow($hwnd) | Out-Null }
            'close' { [RevisionSshNative]::PostMessage($hwnd,0x0010,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null }
        }
        Write-Output ("WINDOW_ACTION=" + $entry.Arguments)
    }
    'script' {
        if ($entry.Shell -eq 'cmd') { & $env:ComSpec /d /s /c ([string]$entry.Command) }
        else { & ([scriptblock]::Create([string]$entry.Command)) }
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    'launch' {
        $process = Start-ConfiguredProcess ([string]$entry.Executable) ([string]$entry.Arguments) ([string]$entry.WorkingDirectory)
        Write-Output ("STARTED_PID=" + $process.Id)
    }
    default { throw "Unsupported action in SSH command catalog: $kind" }
}
