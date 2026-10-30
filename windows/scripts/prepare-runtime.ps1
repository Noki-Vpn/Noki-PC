param(
    [string]$GoArchivePath = '',
    [string]$ModuleCache = '',
    [string]$BuildCache = '',
    [switch]$VerifyTests
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$version = '1.14.1-extended-2.7.2-nokinv-30-pc'
$binaryHash = '6058ec64bf5240f64dd2dca13f6aea60ee4f542ec3649c518a082824be9defff'
$cronetRevision = 'c10c03c318db'
$cronetHash = '3217c6260fbca5f16072e0b79735742f40109a63bb0ff88fd6b96dd6b54a2928'
$sourceHash = '7f46244f661f0ac15a885d19d8b7350bf13b5ee234b52a92880fc63f63961263'
$goVersion = '1.26.9'
$goArchiveHash = 'd722201a9c0c086d1610e111c48203009af690892ed708072bd5ae20160e7a59'
$goExecutableHash = '5b25a4a434c7bbba11112a94c3fa9c1502c0f377bd971705ee8e07b8e3c12620'
$winDivertArchiveHash = '63cb41763bb4b20f600b6de04e991a9c2be73279e317d4d82f237b150c5f3f15'
$winDivertDriverHash = '8da085332782708d8767bcace5327a6ec7283c17cfb85e40b03cd2323a90ddc2'
$tags = 'with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_naive_outbound,with_purego'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime = Join-Path $root 'build\runtime'
$target = Join-Path $runtime 'windows-x64'
$binary = Join-Path $target 'sing-box.exe'
if ($binaryHash -eq 'PENDING_WINDOWS_BUILD') {
    throw 'Native source was updated without a Windows runtime build. Build and reproduce the approved source, then pin its verified binary SHA-256 before preparing this runtime.'
}
New-Item -ItemType Directory -Path $target -Force | Out-Null

function Get-Sha256([string]$Path) {
    $inputStream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($inputStream))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $inputStream.Dispose() }
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

$cronet = Join-Path $target 'libcronet.dll'
if (!(Test-Path -LiteralPath $cronet) -or (Get-Sha256 $cronet) -ne $cronetHash) {
    $download = Join-Path $runtime 'libcronet.download'
    Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -Uri "https://raw.githubusercontent.com/sagernet/cronet-go/$cronetRevision/lib/windows_amd64/libcronet.dll" -OutFile $download
    if ((Get-Sha256 $download) -ne $cronetHash) { throw 'Cronet library checksum mismatch' }
    Move-Item -LiteralPath $download -Destination $cronet -Force
}

# The checked-in source delivery is independent of Git history and other platform trees.
$archive = Join-Path $root 'vendor\sing-box\sing-box.SOURCE.zip'
if (!(Test-Path -LiteralPath $archive -PathType Leaf)) { throw 'Bundled sing-box source archive is missing' }
if ((Get-Sha256 $archive) -ne $sourceHash) { throw 'Native source archive checksum mismatch' }
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach ($notice in @(@('LICENSE', 'LICENSE-sing-box'), @('COPYING', 'COPYING-GPLv3'), @('NOTICE', 'NOTICE-native'),
        @('src/common/windivert/assets/LICENSE.txt', 'LICENSE-WinDivert'))) {
        $entry = $zip.GetEntry($notice[0])
        if ($null -eq $entry) { throw "Native source notice is missing: $($notice[0])" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $target $notice[1]), $true)
    }
} finally { $zip.Dispose() }

if ($VerifyTests -or !(Test-Path -LiteralPath $binary) -or (Get-Sha256 $binary) -ne $binaryHash) {
    $toolchain = Join-Path $runtime "go$goVersion"
    if (!(Test-Path -LiteralPath (Join-Path $toolchain 'go\bin\go.exe'))) {
        if ($GoArchivePath -eq '') {
            $GoArchivePath = Join-Path $runtime "go$goVersion.windows-amd64.zip"
            if (!(Test-Path -LiteralPath $GoArchivePath) -or (Get-Sha256 $GoArchivePath) -ne $goArchiveHash) {
                Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -Uri "https://go.dev/dl/go$goVersion.windows-amd64.zip" -OutFile $GoArchivePath
            }
        }
        if ((Get-Sha256 $GoArchivePath) -ne $goArchiveHash) { throw 'Go archive checksum mismatch' }
        [IO.Compression.ZipFile]::ExtractToDirectory($GoArchivePath, $toolchain)
    }
    $go = Join-Path $toolchain 'go\bin\go.exe'
    if ((Get-Sha256 $go) -ne $goExecutableHash) { throw 'Go executable checksum mismatch' }
    $actualVersion = (& $go version) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $actualVersion -ne "go version go$goVersion windows/amd64") { throw "Go version mismatch: $actualVersion" }
    $build = Join-Path $runtime ('native-build-' + [Guid]::NewGuid().ToString('N'))
    if ([IO.Path]::GetDirectoryName($build) -ne $runtime -or [IO.Path]::GetFileName($build) -notmatch '^native-build-[0-9a-f]{32}$') { throw 'Invalid native build path' }
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $build)
    # The source delivery omits prebuilt driver bytes; native source pins this exact driver.
    $driver = Join-Path $build 'src\common\windivert\assets\WinDivert64.sys'
    $driverCache = Join-Path $runtime 'WinDivert64.sys'
    if (!(Test-Path -LiteralPath $driverCache) -or (Get-Sha256 $driverCache) -ne $winDivertDriverHash) {
        $winDivert = Join-Path $runtime 'WinDivert-2.2.2-A.zip'
        if (!(Test-Path -LiteralPath $winDivert) -or (Get-Sha256 $winDivert) -ne $winDivertArchiveHash) {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -Uri 'https://github.com/basil00/WinDivert/releases/download/v2.2.2/WinDivert-2.2.2-A.zip' -OutFile $winDivert
        }
        if ((Get-Sha256 $winDivert) -ne $winDivertArchiveHash) { throw 'WinDivert archive checksum mismatch' }
        $zip = [IO.Compression.ZipFile]::OpenRead($winDivert)
        try {
            $entry = $zip.GetEntry('WinDivert-2.2.2-A/x64/WinDivert64.sys')
            if ($null -eq $entry) { throw 'WinDivert driver is missing' }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $driverCache, $true)
        } finally { $zip.Dispose() }
    }
    if ((Get-Sha256 $driverCache) -ne $winDivertDriverHash) { throw 'WinDivert driver checksum mismatch' }
    Copy-Item -LiteralPath $driverCache -Destination $driver -Force
    if ((Get-Sha256 $driver) -ne $winDivertDriverHash) { throw 'WinDivert driver checksum mismatch' }
    $workspace = Join-Path $build 'go.work'
    [IO.File]::WriteAllText($workspace, "go $goVersion`n`nuse ./src`n", (New-Object Text.UTF8Encoding($false)))
    $names = @('GOROOT', 'GOTOOLCHAIN', 'GOENV', 'GOWORK', 'GOMODCACHE', 'GOCACHE', 'GOPATH', 'GOFLAGS', 'GODEBUG', 'GOEXPERIMENT', 'CGO_ENABLED', 'GOOS', 'GOARCH', 'GOAMD64', 'GOMAXPROCS', 'GOSUMDB', 'GOPROXY', 'GONOSUMDB', 'GONOPROXY', 'GOPRIVATE', 'PC_NATIVE_TEST_BINARY')
    $saved = @{}
    foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        $env:GOROOT = Join-Path $toolchain 'go'
        $env:GOTOOLCHAIN = 'local'; $env:GOENV = 'off'; $env:GOWORK = $workspace
        $env:GOMODCACHE = if ($ModuleCache -ne '') { [IO.Path]::GetFullPath($ModuleCache) } else { Join-Path $runtime 'gomodcache' }
        $env:GOCACHE = if ($BuildCache -ne '') { [IO.Path]::GetFullPath($BuildCache) } else { Join-Path $runtime 'gocache' }
        $env:GOPATH = Join-Path $build 'gopath'
        $env:GOFLAGS = ''; $env:GODEBUG = ''; $env:GOEXPERIMENT = ''
        $env:CGO_ENABLED = '0'; $env:GOOS = 'windows'; $env:GOARCH = 'amd64'; $env:GOAMD64 = 'v1'; $env:GOMAXPROCS = '4'
        $env:GOSUMDB = 'sum.golang.org'; $env:GOPROXY = 'https://proxy.golang.org,direct'
        $env:GONOSUMDB = ''; $env:GONOPROXY = ''; $env:GOPRIVATE = ''
        Push-Location (Join-Path $build 'src')
        try {
            Invoke-Checked $go @('mod', 'download', 'github.com/sagernet/sing-tun@v0.9.3')
            Invoke-Checked $go @('mod', 'verify')
            $upstreamTun = Join-Path $env:GOMODCACHE 'github.com/sagernet/sing-tun@v0.9.3'
            if ((Get-Sha256 (Join-Path $upstreamTun 'tun_windows.go')) -ne '998d7e712c3d830055c106397f80e797d412a13d1e7d0766b3326d47051ae010') { throw 'Pinned Windows TUN source mismatch' }
            $patchedTun = Join-Path $build 'sing-tun-pc'
            Copy-Item -LiteralPath $upstreamTun -Destination $patchedTun -Recurse
            $patchedWindowsTun = Join-Path $patchedTun 'tun_windows.go'
            [IO.File]::SetAttributes($patchedWindowsTun, [IO.FileAttributes]::Normal)
            Copy-Item -LiteralPath (Join-Path $build 'src\pc-patches\tun_windows.go.patch') -Destination $patchedWindowsTun -Force
            [IO.File]::WriteAllText($workspace, "go $goVersion`n`nuse ./src`n`nreplace github.com/sagernet/sing-tun => ./sing-tun-pc`n", (New-Object Text.UTF8Encoding($false)))
            Invoke-Checked $go @('build', "-tags=$tags", '-trimpath', '-buildvcs=false',
                "-ldflags=-X github.com/sagernet/sing-box/constant.Version=$version -s -w -buildid= -checklinkname=0",
                '-o', (Join-Path $build 'sing-box.exe'), './cmd/sing-box')
            Copy-Item -LiteralPath $cronet -Destination (Join-Path $build 'libcronet.dll') -Force
            if ($VerifyTests) {
                $testPackages = @{
                    'core_log_test.go' = 'log'
                    'dns_capture_filter_test.go' = 'common\windivert'
                    'dns_dialer_windows_test.go' = 'common\dialer'
                    'dns_policy_windows_test.go' = 'protocol\tun'
                    'dns_ports_windows_test.go' = 'common\windivert'
                    'dns_rdp_test.go' = 'dns'
                    'route_rdp_windows_test.go' = 'route'
                    'route_ipv6_test.go' = 'route'
                    'shutdown_api_test.go' = 'experimental\clashapi'
                    'shutdown_cli_test.go' = 'cmd\sing-box'
                }
                foreach ($fixture in $testPackages.Keys) {
                    Copy-Item -LiteralPath (Join-Path $root "src\test\native\$fixture") -Destination (Join-Path $build ('src\' + $testPackages[$fixture] + '\' + $fixture))
                }
                $env:PC_NATIVE_TEST_BINARY = Join-Path $build 'sing-box.exe'
                Invoke-Checked $go @('test', '-trimpath', "-tags=$tags", '-ldflags=-checklinkname=0', '-count=1', '-timeout=2m', '-run=^TestPC',
                    './log', './dns', './common/dialer', './common/windivert', './protocol/tun', './route', './experimental/clashapi', './cmd/sing-box')
            }
        } finally { Pop-Location }
        if ((Get-Sha256 (Join-Path $build 'sing-box.exe')) -ne $binaryHash) { throw 'Reproduced Windows native binary checksum mismatch' }
        Copy-Item -LiteralPath (Join-Path $build 'sing-box.exe') -Destination $binary -Force
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        Remove-Item -LiteralPath $build -Recurse -Force
    }
}
if ((Get-Sha256 $binary) -ne $binaryHash) { throw 'Windows native binary checksum mismatch' }
$nativeVersion = (& $binary version) -join "`n"
if ($LASTEXITCODE -ne 0 -or ($nativeVersion -split "`n")[0] -ne "sing-box version $version") { throw 'Native runtime version mismatch' }
Write-Output "sing-box $version binary SHA256 $binaryHash"
Write-Output $nativeVersion
