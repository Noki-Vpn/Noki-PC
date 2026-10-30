param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Payload,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$InnoCompiler = $env:INNO_SETUP_COMPILER
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$InnoCompiler) {
    $candidates = @(
        (Join-Path $root 'build\tools\innosetup-6.7.3\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
    )
    $InnoCompiler = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (!$InnoCompiler -or !(Test-Path -LiteralPath $InnoCompiler)) {
    throw 'Inno Setup 6.7.3 is required. Set INNO_SETUP_COMPILER to ISCC.exe.'
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected a numeric release version' }
$Payload = [IO.Path]::GetFullPath($Payload)
if (!(Test-Path -LiteralPath $Payload -PathType Leaf)) { throw 'Velopack Setup payload is missing' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = Join-Path $OutputDirectory "Noki-$Version-Setup.exe"
if (Test-Path -LiteralPath $output) { throw "Installer already exists: $output" }
$legalDirectory = Join-Path $root 'build\generated\installer-legal'
& python -X utf8 (Join-Path $PSScriptRoot 'prepare-installer.py') --output-dir $legalDirectory
if ($LASTEXITCODE -ne 0) { throw 'Cannot package without the current public legal documents' }
& $InnoCompiler "/DAppVersion=$Version" "/DPayload=$Payload" "/DLegalDir=$legalDirectory" "/DOutputDir=$OutputDirectory" (Join-Path $root 'packaging\installer.iss')
if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $output)) { throw 'Installer wizard compilation failed' }
Write-Output $output
