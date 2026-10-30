param([string]$JavaHome = $env:JAVA_HOME, [string]$ReleaseDirectory = '')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -Force
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$appTitle = 'Noki Vpn'
if (!(Get-Command dotnet -ErrorAction SilentlyContinue) -or !((& dotnet --list-runtimes) -match '^Microsoft.NETCore.App 10\.')) {
    throw '.NET 10 runtime is required on the build machine for the pinned vpk packaging tool.'
}
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    $jdks = Join-Path $env:USERPROFILE '.gradle\jdks'
    $JavaHome = Get-ChildItem -LiteralPath $jdks -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '-21-' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Pass -JavaHome with a JDK 21 installation, or set JAVA_HOME. Native Image also requires MSVC and Windows SDK.'
}
Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'prepare-updater.ps1') -Packaging
    & (Join-Path $JavaHome 'bin\java.exe') -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon :test createGraalvmNativeDistributable
    if ($LASTEXITCODE -ne 0) { throw 'Desktop build or tests failed' }
    # The packaged guard entry point must reject another identity before any vault/WFP access.
    $packageRoot = Join-Path $root 'build\compose\binaries\main\graalvm-app\Noki'
    Copy-Item -LiteralPath (Join-Path $root 'src\main\resources\licenses') -Destination $packageRoot -Recurse -Force
    Get-ChildItem -LiteralPath (Join-Path $root 'vendor\licenses') | Copy-Item -Destination (Join-Path $packageRoot 'licenses') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $root 'LICENSE'), (Join-Path $root 'COPYING') -Destination $packageRoot -Force
    $licenses = Join-Path $packageRoot 'licenses'
    $notices = @{
        'LICENSE' = 'Noki\LICENSE'
        'COPYING' = 'Noki\COPYING'
        'LICENSE-sing-box' = 'sing-box\LICENSE'
        'COPYING-GPLv3' = 'sing-box\COPYING'
        'NOTICE-native' = 'sing-box\THIRD_PARTY_NOTICES.txt'
        'LICENSE-WinDivert' = 'WinDivert\LICENSE.txt'
    }
    foreach ($name in $notices.Keys) {
        $destination = [IO.Path]::GetFullPath((Join-Path $licenses $notices[$name]))
        if (!$destination.StartsWith(([IO.Path]::GetFullPath($licenses) + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Notice path escapes the package licenses directory' }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Move-Item -LiteralPath (Join-Path $packageRoot $name) -Destination $destination -Force
    }
    $duplicate = Join-Path $licenses 'Nucleus-LICENSE.txt'
    if ((Get-FileHash -LiteralPath $duplicate).Hash -ne (Get-FileHash -LiteralPath (Join-Path $licenses 'Nucleus\LICENSE')).Hash) { throw 'Nucleus license copies differ; preserve both and inspect them' }
    Remove-Item -LiteralPath $duplicate
    & python -X utf8 (Join-Path $root 'scripts\verify-native-metadata.py')
    if ($LASTEXITCODE -ne 0) { throw 'Native metadata validation failed' }
    $preflightRoot = Join-Path $root 'build\package-preflight'
    New-Item -ItemType Directory -Path $preflightRoot -Force | Out-Null
    $platformOutput = Join-Path $preflightRoot 'platform-stdout.txt'
    $platformError = Join-Path $preflightRoot 'platform-stderr.txt'
    $platform = Start-Process -FilePath (Join-Path $packageRoot 'Noki.exe') -ArgumentList '--platform-preflight' -WorkingDirectory $packageRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $platformOutput -RedirectStandardError $platformError
    $null = $platform.Handle
    if (!$platform.WaitForExit(30000)) { $platform.Kill(); throw 'Packaged platform preflight timed out' }
    $platform.Refresh()
    if ($null -eq $platform.ExitCode -or $platform.ExitCode -ne 0 -or !([IO.File]::ReadAllText($platformOutput).Contains('platform-preflight: ok')) -or !([IO.File]::ReadAllText($platformOutput).Contains('platform-preflight: velopack=ok')) -or [IO.File]::ReadAllText($platformError).Trim()) {
        throw ('Packaged Win32 preflight failed (exit ' + $platform.ExitCode + '): ' + [IO.File]::ReadAllText($platformError))
    }
    Get-Content -LiteralPath $platformOutput
    $unusedVault = Join-Path $preflightRoot 'unused-vault'
    $preflightError = Join-Path $preflightRoot 'stderr.txt'
    $probe = Start-Process -FilePath (Join-Path $packageRoot 'Noki.exe') -ArgumentList @('--network-guard', 'off', ('"' + $unusedVault + '"'), 'guard-result-preflight.json', 'invalid-identity') -WorkingDirectory $packageRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $preflightRoot 'stdout.txt') -RedirectStandardError $preflightError
    $null = $probe.Handle
    if (!$probe.WaitForExit(15000)) { $probe.Kill(); throw 'Packaged guard preflight timed out' }
    $probe.Refresh()
    if ($null -eq $probe.ExitCode -or $probe.ExitCode -eq 0 -or !([IO.File]::ReadAllText($preflightError).Contains('UAC')) -or (Test-Path -LiteralPath $unusedVault)) {
        throw 'Packaged guard did not reject the identity before vault/WFP access'
    }
    Write-Output 'Packaged guard entry point verified without changing storage or network filters'
    $releases = Join-Path $root 'build\releases'
    New-Item -ItemType Directory -Path $releases -Force | Out-Null
    $version = [regex]::Match([IO.File]::ReadAllText((Join-Path $root 'build.gradle.kts')), '(?m)^version = "([0-9]+\.[0-9]+\.[0-9]+)"').Groups[1].Value
    if (!$version) { throw 'A numeric release version is required' }
    # Verify the native SDK's early install hook instead of vpk's managed-assembly inspection.
    $hook = Start-Process -FilePath (Join-Path $packageRoot 'Noki.exe') -ArgumentList @('--veloapp-install', $version) -WorkingDirectory $packageRoot -WindowStyle Hidden -PassThru
    $null = $hook.Handle
    if (!$hook.WaitForExit(15000)) { $hook.Kill(); throw 'Velopack install hook did not exit before opening the app' }
    $hook.Refresh()
    if ($hook.ExitCode -ne 0) { throw 'Velopack install hook failed' }
    $velopack = Join-Path $releases "velopack-$version"
    if (Test-Path -LiteralPath $velopack) { throw "Use a fresh version output directory: $velopack" }
    & dotnet (Join-Path $root 'build\tools\vpk-1.2.161\tools\net10.0\any\vpk.dll') pack --packId Noki.Desktop --packTitle $appTitle --packAuthors Noki --packVersion $version --packDir $packageRoot --mainExe Noki.exe --runtime win10.0.19041-x64 --channel win --delta None --icon (Join-Path $root 'build\generated\desktop-icons\app.ico') --skipVeloAppCheck --outputDir $velopack
    if ($LASTEXITCODE -ne 0) { throw 'Velopack packaging failed' }
    $feed = Join-Path $velopack 'releases.win.json'
    $assets = ([IO.File]::ReadAllText($feed) | ConvertFrom-Json).Assets
    if ($assets.Count -ne 1 -or $assets[0].Type -ne 'Full' -or $assets[0].PackageId -ne 'Noki.Desktop' -or $assets[0].Version -ne $version) { throw 'Unexpected Velopack feed' }
    $full = Join-Path $velopack "Noki.Desktop-$version-full.nupkg"
    if ($assets[0].FileName -ne [IO.Path]::GetFileName($full) -or !(Test-Path -LiteralPath $full)) { throw 'Unexpected Velopack package name' }
    $portable = Join-Path $releases "Noki-$version-portable.zip"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $portableSource = [IO.Compression.ZipFile]::OpenRead((Join-Path $velopack 'Noki.Desktop-win-Portable.zip'))
    try {
        $portableTarget = [IO.Compression.ZipFile]::Open($portable, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entry in $portableSource.Entries) {
                $wrapped = $portableTarget.CreateEntry(('Noki/' + $entry.FullName), [IO.Compression.CompressionLevel]::Optimal)
                $wrapped.LastWriteTime = $entry.LastWriteTime
                $wrapped.ExternalAttributes = $entry.ExternalAttributes
                $inputStream = $entry.Open()
                try {
                    $outputStream = $wrapped.Open()
                    try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
                } finally { $inputStream.Dispose() }
            }
        } finally { $portableTarget.Dispose() }
        $portableCheck = [IO.Compression.ZipFile]::OpenRead($portable)
        try {
            if ($portableCheck.Entries.Count -ne $portableSource.Entries.Count -or !$portableCheck.GetEntry('Noki/.portable') -or
                !$portableCheck.GetEntry(('Noki/' + $appTitle + '.exe')) -or !$portableCheck.GetEntry('Noki/Update.exe') -or !$portableCheck.GetEntry('Noki/current/Noki.exe')) {
                throw 'Portable archive is missing the Noki folder or Velopack launcher files'
            }
            foreach ($name in $notices.Keys) {
                $expected = 'Noki/current/licenses/' + $notices[$name].Replace('\', '/')
                if (!$portableCheck.GetEntry($expected) -or $portableCheck.GetEntry(('Noki/current/' + $name))) { throw "Unexpected packaged notice location: $name" }
            }
            if ($portableCheck.GetEntry('Noki/current/licenses/Nucleus-LICENSE.txt')) { throw 'Duplicate Nucleus license remains in the package' }
        } finally { $portableCheck.Dispose() }
    } finally { $portableSource.Dispose() }
    $installer = Join-Path $releases "Noki-$version-Setup.exe"
    & (Join-Path $PSScriptRoot 'package-installer.ps1') -Version $version -Payload (Join-Path $velopack 'Noki.Desktop-win-Setup.exe') -OutputDirectory $releases
    $updates = Join-Path $releases "Noki-$version-updates.zip"
    Compress-Archive -LiteralPath $feed, $full -DestinationPath $updates -Force
    $contents = @($portable, $installer, $updates)
    $archive = Join-Path $releases "Noki-$version.zip"
    Compress-Archive -LiteralPath $contents -DestinationPath $archive -Force
    if (!$ReleaseDirectory) { $ReleaseDirectory = Join-Path $root '..\realeses\windows' }
    $ReleaseDirectory = [IO.Path]::GetFullPath($ReleaseDirectory)
    New-Item -ItemType Directory -Path $ReleaseDirectory -Force | Out-Null
    $delivered = Join-Path $ReleaseDirectory ([IO.Path]::GetFileName($archive))
    if (Test-Path -LiteralPath $delivered) { throw "Release already exists: $delivered" }
    Copy-Item -LiteralPath $archive -Destination $delivered
    Copy-Item -LiteralPath $portable, $installer, $updates -Destination $ReleaseDirectory -Force
    Write-Output $delivered
    $stream = [IO.File]::OpenRead($delivered)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { Write-Output ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose(); $stream.Dispose() }
} finally { Pop-Location }
