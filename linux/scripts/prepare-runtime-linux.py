#!/usr/bin/env python3
"""Reproduce the PC sing-box source delivery for Linux x86_64, without Windows drivers."""
import hashlib
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

VERSION = '1.14.1-extended-2.7.2-nokinv-30-pc'
SOURCE_HASH = '7f46244f661f0ac15a885d19d8b7350bf13b5ee234b52a92880fc63f63961263'
GO_VERSION = '1.26.9'
GO_HASH = '42d158b4d8f7b61ac0a830567c940a86098fb7aac52e467a5ebec03ef5cc2f8d'
CRONET_REVISION = 'c10c03c318db'
CRONET_HASH = '23109c55b08829bb1a68682a61a64852b1bbf243753b153cbd8f6e4ea2c05294'
TAGS = 'with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_naive_outbound,with_purego'
ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download(url, target, expected):
    if target.is_file() and digest(target) == expected:
        return
    temporary = target.with_suffix('.download')
    with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
        shutil.copyfileobj(response, output)
    if digest(temporary) != expected:
        temporary.unlink()
        raise ValueError(f'Checksum mismatch: {target.name}')
    temporary.replace(target)


def checked_member(name):
    value = PurePosixPath(name)
    if value.is_absolute() or '..' in value.parts or '\\' in name or ':' in name:
        raise ValueError(f'Unsafe archive member: {name}')


def prepare_byedpi(target, runtime):
    delivery = ROOT / 'vendor/byedpi'
    source = dict(line.split('=', 1) for line in (delivery / 'byedpi.source.properties').read_text().splitlines() if '=' in line)
    manifest = dict(line.split('=', 1) for line in (delivery / 'byedpi-linux.properties').read_text().splitlines() if '=' in line)
    archive = delivery / 'byedpi.SOURCE.zip'
    if (source.get('version') != '0.17.3' or manifest.get('version') != '0.17.3' or
            manifest.get('profile') != 'linux-md5sig' or manifest.get('source') != source.get('source') or
            digest(archive) != source.get('source')):
        raise ValueError('Pinned ByeDPI source/profile mismatch')
    binary = target / 'ciadpi'
    if not binary.is_file() or digest(binary) != manifest.get('ciadpi'):
        compiler = os.environ.get('BYEDPI_CC') or shutil.which('musl-gcc')
        if not compiler or not shutil.which('make'):
            raise ValueError('ByeDPI requires the pinned musl-gcc/make toolchain or its verified prebuilt runtime')
        with tempfile.TemporaryDirectory(prefix='byedpi-linux-', dir=runtime) as scratch:
            build = Path(scratch)
            with zipfile.ZipFile(archive) as packed:
                for entry in packed.infolist():
                    checked_member(entry.filename)
                    if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                        raise ValueError('Unexpected symlink in ByeDPI source')
                packed.extractall(build)
            cppflags = '-D_DEFAULT_SOURCE -D_GNU_SOURCE'
            for headers in ('/usr/include', '/usr/include/x86_64-linux-gnu'):
                if Path(headers).is_dir():
                    cppflags += f' -idirafter {headers}'
            subprocess.run(['make', f'CC={compiler}', f'CPPFLAGS={cppflags}', 'LDFLAGS=-static'], cwd=build, check=True)
            if digest(build / 'ciadpi') != manifest.get('ciadpi'):
                raise ValueError('Reproduced Linux ByeDPI checksum mismatch; use its pinned toolchain')
            shutil.copy2(build / 'ciadpi', binary)
    if digest(binary) != manifest.get('ciadpi'):
        raise ValueError('Linux ByeDPI checksum mismatch')
    binary.chmod(0o755)
    with zipfile.ZipFile(archive) as packed:
        (target / 'LICENSE-ByeDPI').write_bytes(packed.read('LICENSE'))
    shutil.copy2(delivery / 'byedpi-linux.properties', target / 'byedpi-linux.properties')


def main():
    if sys.platform != 'linux' or platform.machine() not in ('x86_64', 'AMD64'):
        raise SystemExit('Build this runtime on Linux x86_64')
    archive = ROOT / 'vendor/sing-box/sing-box.SOURCE.zip'
    if digest(archive) != SOURCE_HASH:
        raise ValueError('PC native source archive checksum mismatch')
    runtime = ROOT / 'build/runtime'
    target = runtime / 'linux-x64'
    target.mkdir(parents=True, exist_ok=True)
    prepare_byedpi(target, runtime)
    binary = target / 'sing-box'
    cronet = target / 'libcronet.so'
    download(f'https://raw.githubusercontent.com/SagerNet/cronet-go/{CRONET_REVISION}/lib/linux_amd64/libcronet.so',
             cronet, CRONET_HASH)
    manifest = target / 'linux-core.properties'
    if manifest.exists() and binary.is_file():
        previous = dict(line.split('=', 1) for line in manifest.read_text().splitlines())
        if (previous.get('source') == SOURCE_HASH and previous.get('sing-box') == digest(binary) and
                previous.get('version') == VERSION and previous.get('libcronet.so') == CRONET_HASH and
                previous.get('go') == GO_VERSION and previous.get('tags') == TAGS):
            return
    toolchain = runtime / f'go{GO_VERSION}-linux'
    if not (toolchain / 'go/bin/go').is_file():
        go_archive = runtime / f'go{GO_VERSION}.linux-amd64.tar.gz'
        download(f'https://go.dev/dl/go{GO_VERSION}.linux-amd64.tar.gz', go_archive, GO_HASH)
        toolchain.mkdir(exist_ok=True)
        with tarfile.open(go_archive) as packed:
            for entry in packed.getmembers():
                checked_member(entry.name)
                if not (entry.isfile() or entry.isdir()):
                    raise ValueError('Unexpected link in Go toolchain')
            # Debian 12's Python 3.11 lacks extraction filters; all members were checked above.
            packed.extractall(toolchain)
    go = toolchain / 'go/bin/go'
    environment = os.environ.copy()
    environment.update(GOROOT=str(toolchain / 'go'), GOTOOLCHAIN='local', GOENV='off',
                       GOOS='linux', GOARCH='amd64', GOAMD64='v1', CGO_ENABLED='0', GOMAXPROCS='4',
                       GOFLAGS='', GOEXPERIMENT='', GODEBUG='', GOSUMDB='sum.golang.org',
                       GOPROXY='https://proxy.golang.org,direct', GONOSUMDB='', GONOPROXY='', GOPRIVATE='',
                       GOMODCACHE=str(runtime / 'gomodcache-linux'), GOCACHE=str(runtime / 'gocache-linux'))
    version = subprocess.check_output([str(go), 'version'], env=environment, text=True).strip()
    if version != f'go version go{GO_VERSION} linux/amd64':
        raise ValueError('Go toolchain version mismatch')
    with tempfile.TemporaryDirectory(prefix='native-linux-', dir=runtime) as scratch:
        build = Path(scratch)
        with zipfile.ZipFile(archive) as packed:
            for entry in packed.infolist():
                checked_member(entry.filename)
                if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('Unexpected symlink in native source')
            packed.extractall(build)
        workspace = build / 'go.work'
        workspace.write_text(f'go {GO_VERSION}\n\nuse ./src\n')
        environment.update(GOWORK=str(workspace), GOPATH=str(build / 'gopath'))
        source = build / 'src'
        subprocess.run([str(go), 'mod', 'download'], cwd=source, env=environment, check=True)
        subprocess.run([str(go), 'mod', 'verify'], cwd=source, env=environment, check=True)
        subprocess.run([str(go), 'build', f'-tags={TAGS}', '-trimpath', '-buildvcs=false',
                        f'-ldflags=-X github.com/sagernet/sing-box/constant.Version={VERSION} -s -w -buildid= -checklinkname=0',
                        '-o', str(build / 'sing-box'), './cmd/sing-box'], cwd=source, env=environment, check=True)
        shutil.copy2(build / 'sing-box', binary)
        binary.chmod(0o755)
        for source_name, notice in [('LICENSE', 'LICENSE-sing-box'), ('COPYING', 'COPYING-GPLv3'), ('NOTICE', 'NOTICE-native')]:
            shutil.copy2(build / source_name, target / notice)
    native_version = subprocess.check_output([str(binary), 'version'], text=True).splitlines()[0]
    if native_version != f'sing-box version {VERSION}':
        raise ValueError('Linux core version mismatch')
    # Embed the manifest into the JAR; never accept a hash manifest from user storage.
    manifest.write_text(f'version={VERSION}\nsource={SOURCE_HASH}\nsing-box={digest(binary)}\nlibcronet.so={CRONET_HASH}\ngo={GO_VERSION}\ntags={TAGS}\n')
    print(f'Linux runtime prepared: {VERSION}, SHA256 {digest(binary)}')


if __name__ == '__main__':
    main()
