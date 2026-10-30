; Inno owns elevated uninstall and final cleanup; Velopack owns package updates.
#if Ver != EncodeVer(6, 7, 3)
  #error Use the pinned Inno Setup 6.7.3 compiler
#endif
#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef Payload
  #error Payload is required
#endif
#ifndef LegalDir
  #error LegalDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

[Setup]
AppId=Noki.Desktop.Wizard
AppName=Noki Vpn
AppVersion={#AppVersion}
AppPublisher=Noki
AppPublisherURL=https://noki.ykino.tech
DefaultDirName={autopf64}\Noki
UsePreviousAppDir=no
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
MinVersion=10.0.19041
WizardStyle=modern dark includetitlebar
DefaultDialogFontName=Segoe UI
DisableWelcomePage=yes
DisableDirPage=no
DisableReadyPage=yes
AppendDefaultDirName=no
DirExistsWarning=no
RestartApplications=no
DisableProgramGroupPage=yes
Uninstallable=yes
CreateUninstallRegKey=no
UninstallLogMode=overwrite
UninstallDisplayName=Noki Vpn
UninstallDisplayIcon={app}\Noki Vpn.exe
CreateAppDir=yes
LicenseFile={#LegalDir}\terms.txt
OutputDir={#OutputDir}
OutputBaseFilename=Noki-{#AppVersion}-Setup
Compression=lzma2/fast
SetupLogging=yes
SetupIconFile=..\build\generated\desktop-icons\app.ico

[Languages]
Name: russian; MessagesFile: compiler:Languages\Russian.isl

[Messages]
russian.UninstallAppFullTitle=Удаление %1
russian.LicenseLabel=Условия использования Noki VPN
russian.LicenseLabel3=
russian.LicenseAccepted=Я &принимаю условия соглашения
russian.LicenseNotAccepted=Я &не принимаю условия соглашения
russian.WizardLicense=Условия использования
russian.WizardSelectDir=Папка установки
russian.SelectDirDesc=Выберите папку для Noki VPN.
russian.SelectDirBrowseLabel=
russian.SelectDirLabel3=
russian.InstallingLabel=Установка Noki VPN.
russian.FinishedHeadingLabel=Установка завершена
russian.FinishedLabel=Noki VPN установлена.
russian.FinishedLabelNoIcons=Noki VPN установлена.
russian.ClickFinish=
russian.ButtonNext=Далее
russian.ButtonBack=Назад
russian.ButtonInstall=Установить
russian.ButtonCancel=Отмена
russian.ButtonFinish=Готово

[Files]
Source: {#Payload}; DestDir: {tmp}; DestName: Noki-Velopack-Setup.exe; Flags: dontcopy

[UninstallDelete]
Type: filesandordirs; Name: {app}
Type: filesandordirs; Name: {tmp}\velopack_Noki.Desktop

[Run]
Filename: {app}\Noki Vpn.exe; Description: Запустить Noki VPN; Flags: postinstall nowait skipifsilent runascurrentuser

[Code]
var
  ExistingDirectory: String;
  CleanupWarning: Boolean;
  RemoveApplicationData: Boolean;

function SelectUninstallOptions(): Boolean;
var
  Form: TSetupForm;
  DeleteData: TNewCheckBox;
  Details: TNewStaticText;
  DeleteButton, CancelButton: TNewButton;
begin
  RemoveApplicationData := True;
  if UninstallSilent then begin
    Result := True;
    Exit;
  end;
  Form := CreateCustomForm(430, 180, True, True);
  try
    Form.Caption := 'Удаление Noki';
    DeleteData := TNewCheckBox.Create(Form);
    DeleteData.Parent := Form;
    DeleteData.SetBounds(ScaleX(24), ScaleY(24), Form.ClientWidth - ScaleX(48), ScaleY(24));
    DeleteData.Caption := 'Удалить данные приложения';
    DeleteData.Checked := True;
    Details := TNewStaticText.Create(Form);
    Details.Parent := Form;
    Details.AutoSize := False;
    Details.WordWrap := True;
    Details.SetBounds(ScaleX(44), ScaleY(55), Form.ClientWidth - ScaleX(68), ScaleY(50));
    Details.Caption := 'Настройки, данные входа, логи, кеш и пользовательские списки.';
    CancelButton := TNewButton.Create(Form);
    CancelButton.Parent := Form;
    CancelButton.SetBounds(Form.ClientWidth - ScaleX(208), Form.ClientHeight - ScaleY(48), ScaleX(88), ScaleY(26));
    CancelButton.Caption := 'Отмена';
    CancelButton.Cancel := True;
    CancelButton.ModalResult := mrCancel;
    DeleteButton := TNewButton.Create(Form);
    DeleteButton.Parent := Form;
    DeleteButton.SetBounds(Form.ClientWidth - ScaleX(112), Form.ClientHeight - ScaleY(48), ScaleX(88), ScaleY(26));
    DeleteButton.Caption := 'Удалить';
    DeleteButton.Default := True;
    DeleteButton.ModalResult := mrOK;
    Form.ActiveControl := DeleteButton;
    Result := Form.ShowModal() = mrOK;
    if Result then RemoveApplicationData := DeleteData.Checked;
  finally
    Form.Free;
  end;
end;

function CreateWriteProbe(PathName, Prefix: String; Unique: Cardinal; Buffer: String): Cardinal;
  external 'GetTempFileNameW@kernel32.dll stdcall';
function GetPathAttributes(PathName: String): Cardinal;
  external 'GetFileAttributesW@kernel32.dll stdcall';

function RequireAdministratorForLaunch(FileName: String): Boolean;
var
  Layers: String;
begin
  RegQueryStringValue(HKCU64, 'Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers', FileName, Layers);
  if Pos('RUNASADMIN', Uppercase(Layers)) = 0 then begin
    if Layers = '' then Layers := '~';
    Layers := Layers + ' RUNASADMIN';
  end;
  Result := RegWriteStringValue(HKCU64, 'Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers', FileName, Layers);
end;

procedure RemoveLaunchElevation(Directory: String);
begin
  RegDeleteValue(HKCU64, 'Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers', AddBackslash(Directory) + 'Update.exe');
  RegDeleteValue(HKCU64, 'Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers', AddBackslash(Directory) + 'Noki Vpn.exe');
  RegDeleteValue(HKCU64, 'Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers', AddBackslash(Directory) + 'current\Noki.exe');
end;

function NormalDirectory(Directory: String): String;
begin
  Result := RemoveBackslashUnlessRoot(ExpandFileName(Directory));
end;

function ContainsDirectory(ParentDirectory, ChildDirectory: String): Boolean;
begin
  Result := Pos(Lowercase(AddBackslash(NormalDirectory(ParentDirectory))),
    Lowercase(AddBackslash(NormalDirectory(ChildDirectory)))) = 1;
end;

function SafeApplicationRoot(Directory: String): Boolean;
var
  Ancestor, ParentDirectory: String;
begin
  Directory := NormalDirectory(Directory);
  Result := False;
  if (ExtractFileDir(Directory) = Directory) or
    ContainsDirectory(Directory, ExpandConstant('{localappdata}\Noki\Desktop')) or
    ContainsDirectory(ExpandConstant('{localappdata}\Noki\Desktop'), Directory) or
    ContainsDirectory(Directory, ExpandConstant('{%USERPROFILE}')) or
    ContainsDirectory(Directory, ExpandConstant('{localappdata}')) or
    ContainsDirectory(Directory, ExpandConstant('{userappdata}')) or
    ContainsDirectory(Directory, ExpandConstant('{commonappdata}')) or
    ContainsDirectory(Directory, ExpandConstant('{pf64}')) or
    ContainsDirectory(Directory, ExpandConstant('{win}')) then Exit;
  // Refuse destructive replacement/cleanup through junctions or symlinks.
  Ancestor := Directory;
  while Ancestor <> '' do begin
    if DirExists(Ancestor) and ((GetPathAttributes(Ancestor) and $400) <> 0) then Exit;
    ParentDirectory := ExtractFileDir(Ancestor);
    if ParentDirectory = Ancestor then Break;
    Ancestor := ParentDirectory;
  end;
  Result := True;
end;

function IsNokiDirectory(Directory: String): Boolean;
var
  Manifest: AnsiString;
begin
  Result := FileExists(AddBackslash(Directory) + 'Update.exe') and
    FileExists(AddBackslash(Directory) + 'current\Noki.exe') and
    LoadStringFromFile(AddBackslash(Directory) + 'current\sq.version', Manifest);
  if Result then Result := Pos('<id>Noki.Desktop</id>', String(Manifest)) > 0;
end;

function InitializeSetup(): Boolean;
begin
  // Acceptance must be explicit in this wizard, including automation attempts.
  Result := not WizardSilent;
  if not Result then Log('Silent installation is disabled: interactive legal acceptance is required.');
  if not Result then Exit;
  if RegQueryStringValue(HKCU64,
    'Software\Microsoft\Windows\CurrentVersion\Uninstall\Noki.Desktop',
    'InstallLocation', ExistingDirectory) then begin
    ExistingDirectory := NormalDirectory(ExistingDirectory);
    if not IsNokiDirectory(ExistingDirectory) then
      ExistingDirectory := '';
  end;
end;

function DirectoryEmpty(Directory: String): Boolean;
var
  Entry: TFindRec;
begin
  Result := True;
  if FindFirst(AddBackslash(Directory) + '*', Entry) then begin
    try
      repeat
        if (Entry.Name <> '.') and (Entry.Name <> '..') then begin
          Result := False;
          Break;
        end;
      until not FindNext(Entry);
    finally
      FindClose(Entry);
    end;
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Directory, DedicatedDirectory: String;
begin
  Result := True;
  if CurPageID <> wpSelectDir then Exit;
  Directory := RemoveBackslashUnlessRoot(WizardDirValue);
  // Velopack replaces the whole destination directory on repair. A parent with
  // unrelated files is a valid choice, but must get its own application subfolder.
  if DirExists(Directory) and not DirectoryEmpty(Directory) and not IsNokiDirectory(Directory) then begin
    DedicatedDirectory := AddBackslash(Directory) + 'Noki';
    WizardForm.DirEdit.Text := DedicatedDirectory;
    MsgBox('В выбранной папке есть другие файлы. Noki будет установлена в отдельную папку:' + #13#10 + DedicatedDirectory, mbInformation, MB_OK);
    Result := False;
  end;
end;

procedure RegisterExtraCloseApplicationsResources;
begin
  if ExistingDirectory <> '' then begin
    RegisterExtraCloseApplicationsResource(True, AddBackslash(ExistingDirectory) + 'current\Noki.exe');
    RegisterExtraCloseApplicationsResource(True, AddBackslash(ExistingDirectory) + 'current\sing-box.exe');
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Directory, Probe: String;
begin
  Result := '';
  if not WizardForm.LicenseAcceptedRadio.Checked then begin
    Result := 'Необходимо принять пользовательское соглашение.';
    Exit;
  end;
  Directory := NormalDirectory(WizardDirValue);
  Log('Selected Noki installation directory: ' + Directory);
  if not SafeApplicationRoot(Directory) then begin
    Result := 'Выберите отдельную папку для Noki.';
    Exit;
  end;
  if (ExistingDirectory <> '') and (CompareText(Directory, NormalDirectory(ExistingDirectory)) <> 0) and
    (ContainsDirectory(ExistingDirectory, Directory) or ContainsDirectory(Directory, ExistingDirectory)) then begin
    Result := 'Для переноса выберите папку вне текущей установки Noki.';
    Exit;
  end;
  if DirExists(Directory) and not DirectoryEmpty(Directory) and not IsNokiDirectory(Directory) then begin
    Result := 'Выберите отдельную папку Noki внутри выбранного каталога.';
    Exit;
  end;
  if not ForceDirectories(Directory) then begin
    Result := 'Нет доступа к выбранной папке. Выберите папку, доступную текущему пользователю.';
    Exit;
  end;
  Probe := StringOfChar(#0, 260);
  if CreateWriteProbe(Directory, 'nki', 0, Probe) = 0 then
    Result := 'Папка недоступна для записи. Выберите другую папку или проверьте права доступа.'
  else DeleteFile(Copy(Probe, 1, Pos(#0, Probe) - 1));
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ExitCode: Integer;
begin
  if CurStep = ssInstall then begin
    ExtractTemporaryFile('Noki-Velopack-Setup.exe');
    // Velopack owns this phase; it does not expose a numeric progress value.
    WizardForm.ProgressGauge.Style := npbstMarquee;
    if not Exec(ExpandConstant('{tmp}\Noki-Velopack-Setup.exe'),
      '--silent --installto "' + WizardDirValue + '"', '', SW_HIDE, ewWaitUntilTerminated, ExitCode) then
      RaiseException('Не удалось запустить установку Noki.');
    if ExitCode <> 0 then
      RaiseException(Format('Установка Noki не завершена (код %d).', [ExitCode]));
    if not FileExists(ExpandConstant('{app}\Update.exe')) or
      not FileExists(ExpandConstant('{app}\Noki Vpn.exe')) or
      not FileExists(ExpandConstant('{app}\current\Noki.exe')) then
      RaiseException('Не найдены установленные файлы Noki.');
    // The root launchers still need elevation for normal startup in Program Files.
    if not RequireAdministratorForLaunch(ExpandConstant('{app}\Update.exe')) or
      not RequireAdministratorForLaunch(ExpandConstant('{app}\Noki Vpn.exe')) then
      RaiseException('Не удалось настроить права запуска Noki.');
    if (ExistingDirectory <> '') and (CompareText(RemoveBackslashUnlessRoot(WizardDirValue), RemoveBackslashUnlessRoot(ExistingDirectory)) <> 0) then begin
      // Never run the old uninstaller here: it would delete the new install's
      // shared HKCU entry and shortcuts. Only the verified former root is removed.
      CleanupWarning := True;
      if SafeApplicationRoot(ExistingDirectory) and IsNokiDirectory(ExistingDirectory) and
        not ContainsDirectory(ExistingDirectory, WizardDirValue) and
        not ContainsDirectory(WizardDirValue, ExistingDirectory) then
        CleanupWarning := not DelTree(ExistingDirectory, True, True, True);
      if CleanupWarning then
        MsgBox('Noki установлена в выбранную папку. Прежняя папка сохранена, поскольку её не удалось безопасно очистить: ' + ExistingDirectory, mbInformation, MB_OK);
      RemoveLaunchElevation(ExistingDirectory);
    end;
  end;
  if CurStep = ssPostInstall then begin
    if not RegWriteStringValue(HKCU64,
      'Software\Microsoft\Windows\CurrentVersion\Uninstall\Noki.Desktop',
      'UninstallString', '"' + ExpandConstant('{uninstallexe}') + '" /LOG') or
      not RegWriteStringValue(HKCU64,
      'Software\Microsoft\Windows\CurrentVersion\Uninstall\Noki.Desktop',
      'QuietUninstallString', '"' + ExpandConstant('{uninstallexe}') + '" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG') then
      RaiseException('Не удалось зарегистрировать удаление Noki.');
  end;
end;

function ApplicationDataDirectoryIsSafe(): Boolean;
var
  Directory: String;
begin
  Directory := ExpandConstant('{localappdata}\Noki\Desktop');
  Result := not (((GetPathAttributes(Directory) <> $FFFFFFFF) and ((GetPathAttributes(Directory) and $400) <> 0)) or
    ((GetPathAttributes(ExtractFileDir(Directory)) <> $FFFFFFFF) and ((GetPathAttributes(ExtractFileDir(Directory)) and $400) <> 0)));
end;

function InitializeUninstall(): Boolean;
var
  RegisteredDirectory: String;
begin
  Result := False;
  if not SafeApplicationRoot(ExpandConstant('{app}')) or
    not IsNokiDirectory(ExpandConstant('{app}')) or
    not RegQueryStringValue(HKCU64, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\Noki.Desktop',
      'InstallLocation', RegisteredDirectory) or
    (CompareText(NormalDirectory(RegisteredDirectory), NormalDirectory(ExpandConstant('{app}'))) <> 0) then begin
    MsgBox('Не удалось безопасно определить папку Noki.', mbError, MB_OK);
    Exit;
  end;
  if not ApplicationDataDirectoryIsSafe() then begin
    MsgBox('Папка данных Noki является ссылкой. Удаление отменено.', mbError, MB_OK);
    Exit;
  end;
  Result := SelectUninstallOptions();
end;

procedure RemoveOwnedShortcut(FileName: String);
var
  Shell, Shortcut: Variant;
begin
  if not FileExists(FileName) then Exit;
  Shell := CreateOleObject('WScript.Shell');
  Shortcut := Shell.CreateShortcut(FileName);
  if ContainsDirectory(ExpandConstant('{app}'), String(Shortcut.TargetPath)) then
    if not DeleteFile(FileName) then RaiseException('Не удалось удалить ярлык Noki: ' + FileName);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ExitCode: Integer;
  ProfileDirectory: String;
begin
  if CurUninstallStep = usUninstall then begin
    if not ExecAndLogOutput(ExpandConstant('{app}\current\Noki.exe'), '--prepare-uninstall', '',
      SW_HIDE, ewWaitUntilTerminated, ExitCode, nil) or (ExitCode <> 0) then begin
      SuppressibleMsgBox('Не удалось завершить Noki. Закройте приложение и повторите удаление.', mbError, MB_OK, IDOK);
      Abort;
    end;
    RemoveOwnedShortcut(ExpandConstant('{userdesktop}\Noki Vpn.lnk'));
    RemoveOwnedShortcut(ExpandConstant('{userprograms}\Noki Vpn.lnk'));
    RemoveOwnedShortcut(ExpandConstant('{commondesktop}\Noki Vpn.lnk'));
    RemoveOwnedShortcut(ExpandConstant('{commonprograms}\Noki Vpn.lnk'));
  end;
  if CurUninstallStep = usPostUninstall then begin
    if RemoveApplicationData then begin
      ProfileDirectory := ExpandConstant('{localappdata}\Noki\Desktop');
      if not ApplicationDataDirectoryIsSafe() then
        SuppressibleMsgBox('Папка данных Noki является ссылкой. Данные сохранены.', mbError, MB_OK, IDOK)
      else begin
        if DirExists(ProfileDirectory) and not DelTree(ProfileDirectory, True, True, True) then
          SuppressibleMsgBox('Не удалось полностью удалить данные Noki: ' + ProfileDirectory, mbError, MB_OK, IDOK);
        RemoveDir(ExtractFileDir(ProfileDirectory));
      end;
    end;
    RemoveLaunchElevation(ExpandConstant('{app}'));
    RegDeleteKeyIncludingSubkeys(HKCU64, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\Noki.Desktop');
  end;
end;
