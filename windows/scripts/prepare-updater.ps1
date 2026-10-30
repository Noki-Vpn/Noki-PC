param([switch]$Packaging)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$toolsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\build\tools'))
New-Item -ItemType Directory -Path $toolsRoot -Force | Out-Null
$version = '1.2.161'
function Get-Digest([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
}
function Get-Checked([string]$Url, [string]$Path, [string]$Hash) {
    if ((Test-Path -LiteralPath $Path) -and (Get-Digest $Path) -eq $Hash) { return }
    $temporary = $Path + '.download'
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $temporary
    if ((Get-Digest $temporary) -ne $Hash) { throw "Checksum mismatch: $Path" }
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}
$sdk = Join-Path $toolsRoot "velopack_libc_$version.zip"
$dll = Join-Path $toolsRoot 'velopack_libc_win_x64_msvc.dll'
$dllHash = '3387c5f362501374b2f2d942466c3ec565ac573ec24e3d6e7679c0b8e7828b12'
if (!(Test-Path -LiteralPath $dll) -or (Get-Digest $dll) -ne $dllHash) {
    Get-Checked "https://github.com/velopack/velopack/releases/download/$version/velopack_libc_$version.zip" $sdk '31de8f2cf05a47bd5328a9f98b0c41aec1582156f6075f1058b26941f7f17c20'
    $zip = [IO.Compression.ZipFile]::OpenRead($sdk)
    try { [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('lib/velopack_libc_win_x64_msvc.dll'), $dll, $true) }
    finally { $zip.Dispose() }
    if ((Get-Digest $dll) -ne $dllHash) { throw 'Velopack DLL checksum mismatch' }
}
if ($Packaging) {
    $package = Join-Path $toolsRoot "vpk.$version.nupkg"
    Get-Checked "https://api.nuget.org/v3-flatcontainer/vpk/$version/vpk.$version.nupkg" $package 'ac9be73846d9a7a76483f8684abd1503cd50703b992727429dcdc045589c5e08'
    $destination = Join-Path $toolsRoot "vpk-$version"
    $marker = Join-Path $destination '.verified'
    if (!(Test-Path -LiteralPath $marker)) {
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        $zip = [IO.Compression.ZipFile]::OpenRead($package)
        try {
            foreach ($entry in $zip.Entries) {
                if (!$entry.Name) { continue }
                $target = [IO.Path]::GetFullPath((Join-Path $destination $entry.FullName))
                if (!$target.StartsWith($destination + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid vpk package path' }
                New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        } finally { $zip.Dispose() }
        [IO.File]::WriteAllText($marker, $version)
    }
}
Write-Output "Velopack $version native SDK verified"
