# Noki

VPN client for Windows and Linux.

[Website](https://noki.ykino.tech) · [Downloads](https://github.com/Noki-Vpn/Noki-PC/releases/latest) · [Support](https://t.me/NokiSupport)

## Installation

### Windows

Download the installer or portable version from the [Noki website](https://noki.ykino.tech). Install Noki or extract the portable archive, then launch the app.

### Arch Linux and derivatives (pacman)

```bash
curl -fLO https://github.com/Noki-Vpn/Noki-PC/releases/download/v1.7.4/noki-1.7.4-1-x86_64.pkg.tar.zst
sudo pacman -U ./noki-1.7.4-1-x86_64.pkg.tar.zst
```

### Debian 12+, Ubuntu 24.04+ and derivatives (APT)

```bash
curl -fLO https://github.com/Noki-Vpn/Noki-PC/releases/download/v1.7.4/Noki-1.7.4-linux-amd64.deb
sudo apt install ./Noki-1.7.4-linux-amd64.deb
```

### Fedora (DNF)

```bash
curl -fLO https://github.com/Noki-Vpn/Noki-PC/releases/download/v1.7.4/noki-1.7.4-1.x86_64.rpm
sudo dnf install ./noki-1.7.4-1.x86_64.rpm
```

On Linux, open Noki from the application menu or run:

```bash
noki
```

## Building from source

Windows source code is in `windows/`. Linux source code is in `linux/`.

```bash
git clone https://github.com/Noki-Vpn/Noki-PC.git
cd Noki-PC
```

### Linux

Install JDK 21, Python 3.11+, and the GTK 3, OpenGL/EGL and X11 libraries. Packaging also requires `dpkg-deb` for DEB, `rpmbuild` for RPM, or `base-devel` for Arch Linux.

Gradle downloads the JVM dependencies automatically. The native-core build script downloads its pinned Go toolchain.

```bash
cd linux
bash scripts/build-linux.sh
```

The script selects the package format for your distribution. Packages are saved to `realeses/linux/` at the repository root.

### Windows

Install JDK 21, Python 3, .NET Runtime 10, Inno Setup 6.7.3, and Visual Studio Build Tools with C++ tools and the Windows SDK. Set `JAVA_HOME` to your JDK 21 installation.

Open Visual Studio Developer PowerShell at the repository root:

```powershell
cd windows
.\scripts\build.ps1
```

The installer and portable archive are saved to `realeses/windows/` at the repository root.
