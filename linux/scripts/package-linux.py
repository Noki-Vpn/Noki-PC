#!/usr/bin/env python3
"""Distro packaging from the same jpackage app image; never runs package installation."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEB_DEPENDS = 'python3 (>= 3.11), systemd (>= 249), polkitd, pkexec, nftables, iproute2, libsecret-tools, zenity, xdg-utils, libglib2.0-bin, libgtk-3-0, libgl1, libegl1, libx11-6, xwayland, libstdc++6, libc6 (>= 2.35)'
RPM_DEPENDS = 'python3 >= 3.11, systemd >= 249, polkit, nftables, iproute, libsecret, zenity, xdg-utils, glib2, gtk3, libglvnd-glx, libglvnd-egl, libX11, xorg-x11-server-Xwayland, libstdc++, glibc >= 2.35'
ARCH_DEPENDS = ('python', 'systemd', 'polkit', 'nftables', 'iproute2', 'libsecret', 'zenity', 'xdg-utils', 'glib2', 'gtk3', 'libglvnd', 'libx11', 'xorg-xwayland', 'gcc-libs', 'glibc')
POST_INSTALL = '''#!/bin/sh
set -e
systemctl daemon-reload
systemctl enable noki-guard-restore.service >/dev/null
'''
PRE_REMOVE = '''#!/bin/sh
set -e
case "$1" in
  remove) /usr/libexec/noki/core-helper uninstall
     systemctl disable noki-guard-restore.service >/dev/null ;;
  *) /usr/libexec/noki/core-helper upgrade ;;
esac
'''
POST_REMOVE = '''#!/bin/sh
systemctl daemon-reload
'''


def app_version():
    text = (ROOT / 'build.gradle.kts').read_text()
    version = re.search(r'^version = "([0-9]+\.[0-9]+\.[0-9]+)"$', text, re.M)
    if not version:
        raise ValueError('Invalid desktop version')
    return version[1]


def copy_file(source, target, mode=0o644):
    if not source.is_file() or source.is_symlink():
        raise ValueError(f'Missing regular input file: {source}')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    target.chmod(mode)


def stage_payload(destination, image, runtime):
    if not (image / 'bin/Noki').is_file() or not (image / 'lib/runtime').is_dir():
        raise ValueError('Missing Linux jpackage app image; run createDistributable on Linux')
    for item in image.rglob('*'):
        if item.suffix.lower() in ('.exe', '.dll', '.sys'):
            raise ValueError(f'Windows payload in Linux image: {item.name}')
        if item.is_symlink() and not item.resolve().is_relative_to(image.resolve()):
            raise ValueError('App image contains an external symlink')
    # jpackage runtime may contain internal relative links; preserve them without dereferencing.
    shutil.copytree(image, destination / 'usr/lib/noki/app', symlinks=True)
    for name in ('sing-box', 'libcronet.so', 'ciadpi'):
        copy_file(runtime / name, destination / 'usr/lib/noki' / name, 0o755)
    copy_file(runtime / 'byedpi-linux.properties', destination / 'usr/lib/noki/byedpi-linux.properties')
    for name in ('LICENSE-sing-box', 'COPYING-GPLv3', 'NOTICE-native', 'LICENSE-ByeDPI'):
        copy_file(runtime / name, destination / 'usr/share/licenses/noki' / name)
    for name in ('LICENSE', 'COPYING'):
        copy_file(ROOT / name, destination / 'usr/share/licenses/noki' / name)
    for directory in (ROOT / 'src/main/resources/licenses', ROOT / 'vendor/licenses'):
        shutil.copytree(directory, destination / 'usr/share/licenses/noki', dirs_exist_ok=True)
    assets = ROOT / 'packaging/linux'
    copy_file(assets / 'core-helper.py', destination / 'usr/libexec/noki/core-helper', 0o755)
    copy_file(assets / 'noki', destination / 'usr/bin/noki', 0o755)
    copy_file(assets / 'noki.desktop', destination / 'usr/share/applications/noki.desktop')
    copy_file(assets / 'tech.ykino.noki.core.policy', destination / 'usr/share/polkit-1/actions/tech.ykino.noki.core.policy')
    copy_file(assets / 'noki-guard-restore.service', destination / 'usr/lib/systemd/system/noki-guard-restore.service')
    copy_file(ROOT / 'src/main/resources/noki/desktop/app.png', destination / 'usr/share/icons/hicolor/256x256/apps/noki.png')
    for path in destination.rglob('*'):
        if path.is_symlink():
            continue
        path.chmod(0o755 if path.is_dir() or os.access(path, os.X_OK) else 0o644)


def script(path, body):
    path.write_text(body, encoding='utf-8', newline='\n')
    path.chmod(0o755)


def arch_recipe(version, sha):
    return f'''pkgname=noki
pkgver={version}
pkgrel=1
pkgdesc='Noki VPN'
arch=('x86_64')
url='https://noki.ykino.tech'
license=('GPL-3.0-or-later')
depends=({' '.join(repr(item) for item in ARCH_DEPENDS)})
install=noki.install
source=("noki-$pkgver-linux-x86_64.tar.gz")
sha256sums=('{sha}')
options=('!strip' '!debug')
package() {{
  cp -a "$srcdir/payload/." "$pkgdir/"
}}
'''

def rpm_files(payload):
    rows = []
    owned = ('usr/lib/noki', 'usr/libexec/noki', 'usr/share/licenses/noki')
    for path in sorted(payload.rglob('*')):
        name = path.relative_to(payload).as_posix()
        directory = path.is_dir() and not path.is_symlink()
        if directory and not any(name == root or name.startswith(root + '/') for root in owned):
            continue
        rows.append(('%dir ' if directory else '') + '"/' + name.replace('%', '%%').replace('"', '\\"') + '"')
    return '\n'.join(rows)


def pack(format, payload, output, version, scratch):
    if format == 'deb':
        control = payload / 'DEBIAN'
        control.mkdir()
        (control / 'control').write_text(f'Package: noki\nVersion: {version}\nArchitecture: amd64\nMaintainer: Noki <noki_support@ykino.tech>\nSection: net\nPriority: optional\nDepends: {DEB_DEPENDS}\nDescription: Noki VPN\n')
        script(control / 'postinst', POST_INSTALL)
        # dpkg also calls prerm for upgrades: stop the old core before replacing its files.
        script(control / 'prerm', PRE_REMOVE)
        script(control / 'postrm', POST_REMOVE)
        subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(payload), str(output / f'Noki-{version}-linux-amd64.deb')], check=True)
    elif format == 'rpm':
        top = scratch / 'rpm'
        for name in ('BUILD', 'BUILDROOT', 'SOURCES', 'SPECS', 'RPMS', 'SRPMS'):
            (top / name).mkdir(parents=True)
        spec = top / 'SPECS/noki.spec'
        files = rpm_files(payload)
        spec.write_text(f'''Name: noki
Version: {version}
Release: 1
Summary: Noki VPN
License: GPL-3.0-or-later
URL: https://noki.ykino.tech
BuildArch: x86_64
AutoReqProv: no
Requires: {RPM_DEPENDS}
%global debug_package %{{nil}}
%global __os_install_post %{{nil}}
%description
Noki VPN
%install
mkdir -p "%{{buildroot}}"
cp -a {shlex.quote(str(payload) + '/.').replace('%', '%%')} "%{{buildroot}}/"
%pre
if [ "$1" -gt 1 ]; then /usr/libexec/noki/core-helper upgrade || exit 1; fi
%post
{POST_INSTALL.splitlines()[2]}
systemctl enable noki-guard-restore.service >/dev/null
%preun
if [ "$1" -eq 0 ]; then
  /usr/libexec/noki/core-helper uninstall || exit 1
  systemctl disable noki-guard-restore.service >/dev/null
fi
%postun
systemctl daemon-reload
%files
{files}
''')
        subprocess.run(['rpmbuild', '--define', f'_topdir {top}', '--define', '_build_id_links none', '-bb', str(spec)], check=True)
        for package in (top / 'RPMS').rglob('*.rpm'):
            shutil.copy2(package, output)
    else:
        archive = output / f'noki-{version}-linux-x86_64.tar.gz'
        with tarfile.open(archive, 'w:gz', dereference=False) as packed:
            def root_owner(entry):
                entry.uid = entry.gid = 0
                entry.uname = entry.gname = 'root'
                return entry
            packed.add(payload, arcname='payload', filter=root_owner)
        sha = hashlib.sha256(archive.read_bytes()).hexdigest()
        (output / 'PKGBUILD').write_text(arch_recipe(version, sha))
        (output / 'noki.install').write_text('''post_install() { systemctl daemon-reload; systemctl enable noki-guard-restore.service; }
pre_upgrade() { /usr/libexec/noki/core-helper upgrade; }
post_upgrade() { post_install; }
pre_remove() { /usr/libexec/noki/core-helper uninstall; systemctl disable noki-guard-restore.service; }
post_remove() { systemctl daemon-reload; }
''')
        subprocess.run(['makepkg', '--force', '--cleanbuild', '--noconfirm'], cwd=output, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('format', choices=['deb', 'rpm', 'arch'])
    args = parser.parse_args()
    if sys.platform != 'linux' or os.getuid() == 0:
        raise SystemExit('Package on Linux as a regular user')
    version = app_version()
    images = [path.parent.parent for path in (ROOT / 'build/compose/binaries/main/app').glob('*/bin/Noki')]
    if len(images) != 1:
        raise ValueError('Expected exactly one Noki jpackage app image')
    output = ROOT.parent / 'realeses/linux' / args.format
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='noki-package-', dir=ROOT / 'build') as directory:
        scratch = Path(directory)
        payload = scratch / 'payload'
        stage_payload(payload, images[0], ROOT / 'build/runtime/linux-x64')
        pack(args.format, payload, output, version, scratch)
    print(f'Linux packages: {output}')


if __name__ == '__main__':
    main()
