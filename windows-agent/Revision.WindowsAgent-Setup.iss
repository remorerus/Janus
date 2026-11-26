[Setup]
AppId={{7C8C8D4A-0D0D-4D6E-9C4C-REVISIONWINAGENT}
AppName=RE:Vision Windows Agent
AppVersion=1.0.0
AppPublisher=RE:Vision
DefaultDirName={autopf}\REVision Windows Agent Setup
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
Uninstallable=no
OutputDir=.
OutputBaseFilename=RE-Vision-Windows-Agent-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Files]
Source: "publish\*"; DestDir: "{tmp}\REVisionWindowsAgent"; Flags: ignoreversion recursesubdirs createallsubdirs

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
  ErrorCode: Integer;
  Params: String;
begin
  if CurStep = ssPostInstall then
  begin
    Params := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
      ExpandConstant('{tmp}\REVisionWindowsAgent\install-agent.ps1') + '"';
    if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
      Params, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      RaiseException('Не удалось запустить встроенный установщик службы Windows Agent.');
    if ResultCode <> 0 then
      RaiseException('Установка Windows Agent завершилась с ошибкой. Код: ' + IntToStr(ResultCode) +
        '. Повторный запуск установщика безопасен: существующие данные сопряжения не удаляются.');
    ShellExec('', 'http://127.0.0.1:47821/manager', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
  end;
end;
