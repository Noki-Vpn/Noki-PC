"""Prepare pinned Tao sources and the Windows dispatcher/idle GPU fixes."""

import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parent
ARCHIVE_SHA256 = "07d108fdbccd8778ef595c379d20c01403379df24e7b91350d1e9afde011cb1a"
DISPATCHER_SHA256 = "3a33c54e39155310f82066cb51029b04a174a3eb2c3cc0a0eca5fc1c92ecca24"
WINDOWS_SOURCE_SHA256 = "5f03faf39c89a278c1059393632925e797fb4f234f03e46d09d2b3235517a3ad"
WINDOWS_PATCHED_SHA256 = "5f1e02bf0881c84cf9aa8294cbe6fe917b7b4c3d9adf38e92730896a49d1a4dc"
WINDOWS_SOURCE = "dev/nucleusframework/window/tao/scene/TaoComposeSceneHostWindows.kt"
GL_ARCHIVE_SHA256 = "0bdf9f949fef2ae4fa59497fd4d3eeb983b51bb6f5f53d25567b952ac4602908"
GL_PATCHED_SHA256 = "3479fa909a5375f507b73622ebaeabdbdecb570cdb8974c698ec3992a186d179"


def checked_hash(data: bytes, expected: str, name: str) -> None:
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError(f"Pinned Nucleus {name} SHA-256 mismatch")


def backport(source: str, patch: str, target: str) -> str:
    """Apply this one pinned file's unified diff, checking every context line and hunk size."""
    original = source.splitlines(keepends=True)
    changes = patch.splitlines(keepends=True)
    if changes[:2] != [f"--- a/{target}\n", f"+++ b/{target}\n"]:
        raise ValueError("Unexpected Nucleus patch target")
    output = []
    cursor = 0
    index = 2
    while index < len(changes):
        match = re.fullmatch(r"@@ -(\d+),(\d+) \+(\d+),(\d+) @@[^\n]*\n", changes[index])
        if match is None:
            raise ValueError("Invalid Nucleus patch hunk")
        start, old_count, new_start, new_count = map(int, match.groups())
        if start - 1 < cursor or new_start - 1 != len(output) + start - 1 - cursor:
            raise ValueError("Overlapping or shifted Nucleus patch hunk")
        output.extend(original[cursor:start - 1])
        cursor = start - 1
        consumed = produced = 0
        index += 1
        while index < len(changes) and not changes[index].startswith("@@ "):
            operation, text = changes[index][:1], changes[index][1:]
            if operation in (" ", "-"):
                if cursor >= len(original) or original[cursor] != text:
                    raise ValueError("Nucleus patch context does not match the pinned source")
                cursor += 1
                consumed += 1
            if operation in (" ", "+"):
                output.append(text)
                produced += 1
            if operation not in (" ", "+", "-"):
                raise ValueError("Unexpected Nucleus patch operation")
            index += 1
        if (consumed, produced) != (old_count, new_count):
            raise ValueError("Nucleus patch hunk size mismatch")
    output.extend(original[cursor:])
    return "".join(output)


def build_windows_gl(destination: Path, java_home: Path) -> None:
    archive = ROOT / "windows-gl-2.5.18-sources.zip"
    checked_hash(archive.read_bytes(), GL_ARCHIVE_SHA256, "Windows GL source archive")
    native = destination / "native"
    with zipfile.ZipFile(archive) as sources:
        for entry in sources.infolist():
            name = Path(entry.filename)
            if name.is_absolute() or ".." in name.parts or entry.is_dir():
                raise ValueError("Unexpected Nucleus native archive entry")
            target = native / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(sources.read(entry))
    source = native / "windows/nucleus_tao_gl.c"
    patched = backport(source.read_text(encoding="utf-8"),
        (ROOT / "patches/windows-gl-idle-display.patch").read_text(encoding="utf-8"),
        "decorated-window-tao/src/main/native/windows/nucleus_tao_gl.c")
    checked_hash(patched.encode("utf-8"), GL_PATCHED_SHA256, "Windows GL backport")
    source.write_text(patched, encoding="utf-8", newline="\n")
    vswhere = Path(os.environ["ProgramFiles(x86)"]) / "Microsoft Visual Studio/Installer/vswhere.exe"
    installation = subprocess.check_output([str(vswhere), "-latest", "-products", "*", "-requires",
        "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"], text=True).strip()
    if not installation:
        raise ValueError("MSVC x64 Build Tools are required for the Windows GL backport")
    vcvars = Path(installation) / "VC/Auxiliary/Build/vcvars64.bat"
    output = destination / "resources/nucleus/native/win32-x64/nucleus_tao_gl.dll"
    command = ["cl.exe", "/nologo", "/LD", "/O1", "/GS-",
        "/I" + str(java_home / "include"), "/I" + str(java_home / "include/win32"),
        "/I" + str(native / "vendor/angle-headers"), str(source), str(native / "windows/nucleus_tao_texture.c"),
        "/Fo" + str(native) + os.sep, "/Fe" + str(output),
        "/link", "/ENTRY:DllMain", "/NODEFAULTLIB", "/Brepro", "/IMPLIB:" + str(native / "windows-gl.lib"),
        "gdi32.lib", "user32.lib", "kernel32.lib"]
    script = native / "build-windows-gl.cmd"
    script.write_text('@echo off\nchcp 65001 >nul\ncall "' + str(vcvars) + '" >nul\nif errorlevel 1 exit /b %errorlevel%\n'
        + subprocess.list2cmdline(command) + '\nexit /b %errorlevel%\n', encoding="utf-8")
    subprocess.run(["cmd.exe", "/d", "/c", str(script)], check=True, cwd=native)


def prepare(destination: Path, java_home: Path) -> None:
    archive = (ROOT / "nucleus.decorated-window-tao-2.5.18-sources.jar").read_bytes()
    dispatcher = (ROOT / "patches/SceneMainDispatcher.kt").read_bytes()
    checked_hash(archive, ARCHIVE_SHA256, "source archive")
    checked_hash(dispatcher, DISPATCHER_SHA256, "upstream dispatcher")
    patch = (ROOT / "patches/windows-scene-dispatcher.patch").read_text(encoding="utf-8")
    with zipfile.ZipFile(ROOT / "nucleus.decorated-window-tao-2.5.18-sources.jar") as sources:
        windows = sources.read(WINDOWS_SOURCE).decode("utf-8").replace("\r\n", "\n")
        checked_hash(windows.encode("utf-8"), WINDOWS_SOURCE_SHA256, "Windows source")
        windows = backport(windows, patch, "decorated-window-tao/src/main/kotlin/" + WINDOWS_SOURCE).encode("utf-8")
        checked_hash(windows, WINDOWS_PATCHED_SHA256, "Windows backport")
        # Re-create only this task's generated tree, so removed upstream files cannot survive.
        import shutil
        workspace = ROOT.parents[1].resolve()
        expected = workspace / "build/nucleus-tao/upstream"
        resolved = destination.resolve()
        if resolved != expected or not resolved.is_relative_to(workspace):
            raise ValueError("Unexpected Nucleus generated source directory")
        if destination.exists():
            shutil.rmtree(destination)
        for entry in sources.infolist():
            if entry.is_dir() or entry.filename == "META-INF/MANIFEST.MF":
                continue
            name = Path(entry.filename)
            if name.is_absolute() or ".." in name.parts:
                raise ValueError("Unexpected Nucleus archive entry")
            kind = "kotlin" if name.suffix == ".kt" else "java" if name.suffix == ".java" else "resources"
            target = destination / kind / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(windows if entry.filename == WINDOWS_SOURCE else sources.read(entry))
    target = destination / "kotlin/dev/nucleusframework/window/tao/scene/SceneMainDispatcher.kt"
    target.write_bytes(dispatcher)
    build_windows_gl(destination, java_home)


if __name__ == "__main__":
    prepare(Path(sys.argv[1]), Path(sys.argv[2]))
