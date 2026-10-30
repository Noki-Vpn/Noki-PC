@echo off
setlocal
set "NOKI_EXE=%~dp0build\compose\binaries\main\graalvm-app\Noki\Noki.exe"
if not exist "%NOKI_EXE%" (
  echo Build the Windows app first: powershell -NoProfile -File scripts\build.ps1
  exit /b 1
)
start "" "%NOKI_EXE%"
