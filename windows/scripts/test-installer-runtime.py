"""Exercise the real wizard callbacks and pinned Velopack in an isolated package.

No production package, registry entry, shortcuts, user vault or VPN is touched.
"""
from pathlib import Path
import os
import subprocess
import uuid
import winreg
import time
import base64

ROOT = Path(__file__).resolve().parents[1]


def run(arguments, expected=0):
    result = subprocess.run(list(map(str, arguments)), capture_output=True, text=True, errors="replace", timeout=90)
    if result.returncode != expected:
        raise AssertionError(f"Command exited {result.returncode}, expected {expected}:\n{result.stdout}\n{result.stderr}")


def main():
    work = ROOT / "build" / "installer-runtime-tests" / uuid.uuid4().hex
    work.mkdir(parents=True)
    package_id = "Noki.Installer.Test." + uuid.uuid4().hex
    package = work / "package"
    package.mkdir()
    # A small GUI-subsystem stub exercises installer lifecycle without VPN/network access.
    stub = work / "stub.c"
    stub.write_text(r'''#include <windows.h>
#include <wchar.h>
int main(void) {
    wchar_t path[32768];
    if (!GetModuleFileNameW(NULL, path, 32768)) return 9;
    wchar_t *name = wcsrchr(path, L'\\');
    if (!name) return 9;
    wcscpy(name + 1, L"fail-cleanup");
    return GetFileAttributesW(path) == INVALID_FILE_ATTRIBUTES ? 0 : 7;
}
''', encoding="utf-8")
    run(["cl.exe", "/nologo", "/MT", "/O1", f"/Fe:{package / 'Noki.exe'}", f"/Fo:{work / 'stub.obj'}", stub,
         "/link", "/SUBSYSTEM:WINDOWS", "/ENTRY:mainCRTStartup"])
    releases = work / "releases"
    run(["dotnet", ROOT / "build/tools/vpk-1.2.161/tools/net10.0/any/vpk.dll", "pack", "--packId", package_id,
         "--packTitle", "Noki Installer Test", "--packVersion", "0.0.1", "--packDir", package,
         "--mainExe", "Noki.exe", "--runtime", "win10.0.19041-x64", "--channel", "win",
         "--delta", "None", "--shortcuts", "None", "--skipVeloAppCheck", "--outputDir", releases])
    legal = work / "legal"
    legal.mkdir()
    (legal / "terms.txt").write_text("Тестовое соглашение. Только изолированный тест установщика.", encoding="utf-8-sig")
    source = (ROOT / "packaging/installer.iss").read_text(encoding="utf-8")
    source = source.replace("Noki.Desktop", package_id).replace("Noki Vpn.exe", "Noki Installer Test.exe")
    source = source.replace("Result := not WizardSilent;", "Result := True;")
    profile = work / "profile" / "Desktop"
    source = source.replace(r"{localappdata}\Noki\Desktop", str(profile)).replace(r"{localappdata}\Noki", str(profile.parent))
    for constant, name in (("userdesktop", "owned"), ("userprograms", "unrelated"), ("commondesktop", "common-desktop"), ("commonprograms", "common-programs")):
        source = source.replace("{" + constant + r"}\Noki Vpn.lnk", str(work / (name + ".lnk")))
    # The fixture uses explicit writable /DIR paths and never automates UAC.
    # Production remains admin + Program Files; a human confirms its UAC prompt.
    source = source.replace("PrivilegesRequired=admin", "PrivilegesRequired=lowest")
    # Select the same deletion branch without showing UI in this disposable fixture.
    source = source.replace("RemoveApplicationData := True;", "RemoveApplicationData := Pos('/KEEPDATA', Uppercase(GetCmdTail)) = 0;")
    source += "\nprocedure InitializeWizard();\nbegin\n  WizardForm.LicenseAcceptedRadio.Checked := True;\nend;\n"
    # Inno /DIR exercises the same editable DirEdit used by the interactive picker.
    source = source.replace("SetupIconFile=..\\build\\generated\\desktop-icons\\app.ico", f"SetupIconFile={ROOT / 'build/generated/desktop-icons/app.ico'}")
    script = work / "test.iss"
    script.write_text(source, encoding="utf-8-sig")
    run([ROOT / "build/tools/innosetup-6.7.3/ISCC.exe", "/Q", "/DAppVersion=0.0.1",
         f"/DPayload={releases / (package_id + '-win-Setup.exe')}", f"/DLegalDir={legal}", f"/DOutputDir={work}", script])
    setup = work / "Noki-0.0.1-Setup.exe"
    first = work / "Первая папка"
    second = work / "Другая папка"
    key_path = rf"Software\Microsoft\Windows\CurrentVersion\Uninstall\{package_id}"
    layers_path = r"Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers"

    def clear_test_layers(directory, names=("Update.exe", "Noki Installer Test.exe")):
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, layers_path, 0, winreg.KEY_SET_VALUE | winreg.KEY_WOW64_64KEY) as key:
            for name in names:
                try:
                    winreg.DeleteValue(key, str(directory / name))
                except FileNotFoundError:
                    pass

    def install(destination):
        run([setup, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", f"/DIR={destination}", f"/LOG={work / 'install.log'}"])
        for entry in ("Update.exe", "Noki Installer Test.exe", "current/Noki.exe"):
            assert (destination / entry).is_file(), (destination, entry)
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
            actual = winreg.QueryValueEx(key, "InstallLocation")[0]
            uninstall = winreg.QueryValueEx(key, "UninstallString")[0]
        assert Path(actual) == destination, (actual, destination)
        assert str(destination / "unins000.exe") in uninstall
        assert (destination / "unins000.exe").is_file()
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, layers_path, 0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
            for name in ("Update.exe", "Noki Installer Test.exe"):
                assert "RUNASADMIN" in winreg.QueryValueEx(key, str(destination / name))[0]

    try:
        install(first)
        print("PASS: selected Unicode/spaced directory, Velopack files and uninstall path")
        install(first)
        print("PASS: repair/reinstall into nonempty existing Noki directory")
        install(second)
        assert not first.exists(), "Old owned installation root was not cleaned"
        print("PASS: choosing another directory on reinstall migrates install and uninstall registration")
        for name in ("Update.exe", "Noki Installer Test.exe"):
            try:
                subprocess.run([str(second / name), "--help"], timeout=10, capture_output=True)
            except OSError as error:
                assert error.winerror == 740, error
            else:
                raise AssertionError(f"{name} did not request elevation")
        print("PASS: both native updater/uninstaller and root launcher require Windows elevation")
        # Only Update.exe's layer is removed to avoid UAC in this lowest-rights fixture.
        # The launcher layer stays present so the real Inno uninstaller must remove it.
        clear_test_layers(second, names=("Update.exe",))
        profile.mkdir(parents=True)
        (profile / "settings.dpapi").write_text("isolated fixture", encoding="utf-8")
        (second / "leftover.log").write_text("must be removed", encoding="utf-8")
        sentinel = work / "outside" / "keep.txt"
        sentinel.parent.mkdir()
        sentinel.write_text("must survive", encoding="utf-8")
        for name, target in (("owned", second / "Noki Installer Test.exe"), ("unrelated", sentinel)):
            escape = lambda value: str(value).replace("'", "''")
            command = f"$link=(New-Object -ComObject WScript.Shell).CreateShortcut('{escape(work / (name + '.lnk'))}'); $link.TargetPath='{escape(target)}'; $link.Save()"
            run(["powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", base64.b64encode(command.encode("utf-16le")).decode()])
        run(["cmd.exe", "/c", "mklink", "/J", profile / "outside-link", sentinel.parent])
        uninstaller = second / "unins000.exe"
        failure = second / "current" / "fail-cleanup"
        failure.touch()
        result = subprocess.run([str(uninstaller), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", f"/LOG={work / 'failed-uninstall.log'}"], timeout=30, capture_output=True)
        assert result.returncode != 0, "Failed VPN cleanup was accepted"
        assert (second / "current/Noki.exe").exists() and profile.exists(), "Failed cleanup must preserve installation and profile"
        failure.unlink()
        run([uninstaller, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/KEEPDATA", f"/LOG={work / 'keep-data-uninstall.log'}"])
        deadline = time.monotonic() + 15
        while second.exists() and time.monotonic() < deadline:
            time.sleep(.1)
        assert not second.exists(), "Keeping data must still remove the installed application"
        assert (profile / "settings.dpapi").read_text(encoding="utf-8") == "isolated fixture", "Keeping data removed the profile"
        assert sentinel.read_text(encoding="utf-8") == "must survive", "Keeping data followed a profile link"
        install(second)
        clear_test_layers(second, names=("Update.exe",))
        print("PASS: keeping application data preserves the profile while removing the installation")
        run([uninstaller, "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", f"/LOG={work / 'uninstall.log'}"])
        deadline = time.monotonic() + 15
        while second.exists() and time.monotonic() < deadline:
            time.sleep(.1)
        assert not second.exists(), "Uninstall left the installation directory"
        assert not profile.exists(), "Uninstall left private profile/cache files"
        assert sentinel.read_text(encoding="utf-8") == "must survive", "Uninstall followed a profile link"
        assert not (work / "owned.lnk").exists(), "Owned shortcut remains"
        assert (work / "unrelated.lnk").exists(), "Shortcut to another location was removed"
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, layers_path, 0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
            for name in ("Update.exe", "Noki Installer Test.exe"):
                try:
                    winreg.QueryValueEx(key, str(second / name))
                except FileNotFoundError:
                    pass
                else:
                    raise AssertionError("Launch elevation registry value remains")
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path):
                raise AssertionError("Test uninstall registry entry remains")
        except FileNotFoundError:
            pass
        print("PASS: Inno uninstall removes the whole root, profile, registry and layers; cleanup failure aborts and external links remain safe")
    finally:
        # Cleanup uses only Update.exe from explicitly named unique test roots.
        for directory in (second, first):
            clear_test_layers(directory)
            if (directory / "Update.exe").is_file():
                subprocess.run([str(directory / "Update.exe"), "--uninstall", "--silent"], timeout=30, capture_output=True)
    print(f"Isolated validation files: {work}")


if __name__ == "__main__":
    main()
