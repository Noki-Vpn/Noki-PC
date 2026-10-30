#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || { echo "Run on Linux x86_64" >&2; exit 1; }
[[ "$EUID" != 0 ]] || { echo "Build as a regular user" >&2; exit 1; }
format="${1:-}"
if [[ -z "$format" ]]; then
  . /etc/os-release
  case " $ID ${ID_LIKE:-} " in
    *" arch "*|*" cachyos "*) format=arch ;;
    *" fedora "*|*" rhel "*) format=rpm ;;
    *" debian "*|*" ubuntu "*) format=deb ;;
    *) echo "Specify deb, rpm or arch" >&2; exit 1 ;;
  esac
fi
case "$format" in deb|rpm|arch) ;; *) echo "Expected deb, rpm or arch" >&2; exit 1 ;; esac
python3 scripts/test_linux_helper.py
python3 scripts/test_linux_packaging.py
python3 scripts/prepare-runtime-linux.py
bash gradlew --no-daemon -PdesktopTarget=linux createDistributable
python3 scripts/package-linux.py "$format"
