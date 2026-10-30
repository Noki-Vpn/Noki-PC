#!/usr/bin/python3 -I
"""Fixed polkit entry point. The core runs as the caller, never as root."""
import json
import fcntl
import os
from pathlib import Path, PurePosixPath
import pwd
import re
import stat
import subprocess
import sys

CORE = PurePosixPath('/usr/lib/noki/sing-box')
STATE = Path('/var/lib/noki/guard')
MARK = 0x4E4F
INTERFACE = 'Noki'


def caller_uid(environment):
    value = environment.get('PKEXEC_UID', '')
    if not re.fullmatch(r'[1-9][0-9]{0,9}', value):
        raise ValueError('Expected a non-root polkit caller')
    return int(value)


def invoke(arguments, *, input=None, check=True):
    return subprocess.run(arguments, input=input, capture_output=True, text=True,
                          timeout=20, check=check, env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})


def executable(name):
    for directory in ('/usr/bin', '/usr/sbin', '/bin', '/sbin'):
        path = Path(directory) / name
        if path.is_file():
            return str(path)
    raise ValueError(f'Required system tool is missing: {name}')


def gui_scope(uid, pid):
    if pid <= 1:
        raise ValueError('Invalid GUI process')
    status = Path(f'/proc/{pid}/status').read_text()
    match = re.search(r'^Uid:\s+(\d+)\s+(\d+)', status, re.M)
    if not match or int(match[1]) != uid or int(match[2]) != uid:
        raise ValueError('GUI process belongs to another user')
    groups = Path(f'/proc/{pid}/cgroup').read_text().splitlines()
    path = next((line[3:] for line in groups if line.startswith('0::')), '')
    return checked_scope(uid, path)


def checked_scope(uid, path):
    if not re.fullmatch(r'/user\.slice/user-' + str(uid) + r'\.slice/user@' + str(uid) +
                        r'\.service/app\.slice/noki-client-[0-9]+-[0-9]+\.scope', path):
        raise ValueError('Launch Noki with the installed noki launcher (systemd user scope required)')
    return path.strip('/')


def nft_rules(uid, scope=None):
    if uid <= 0 or uid > 2**32 - 2:
        raise ValueError('Invalid UID')
    table = f'noki_{uid}'
    gui = ''
    if scope:
        scope = checked_scope(uid, '/' + scope.lstrip('/'))
        gui = f'    socket cgroupv2 level {len(scope.split("/"))} "{scope}" accept\n'
    return (f'table inet {table} {{\n  chain output {{\n    type filter hook output priority -10; policy accept;\n'
            f'    oifname "lo" accept\n'
            f'    meta mark {MARK} accept\n    oifname "{INTERFACE}" accept\n'
            + gui + f'    udp dport 53 reject\n    tcp dport 53 reject\n'
            f'    meta skuid != {uid} accept\n    reject\n  }}\n}}\n')


def guard_enabled(uid):
    return invoke([executable('nft'), 'list', 'table', 'inet', f'noki_{uid}'], check=False).returncode == 0


def save_state(uid, enabled):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = STATE / f'{uid}.tmp'
    temporary.write_text(json.dumps({'guard': bool(enabled)}))
    temporary.chmod(0o600)
    temporary.replace(STATE / str(uid))


def set_guard(uid, enabled, scope=None):
    table = f'noki_{uid}'
    prefix = f'delete table inet {table}\n' if guard_enabled(uid) else ''
    script = prefix + (nft_rules(uid, scope) if enabled else '')
    if script:
        invoke([executable('nft'), '-f', '-'], input=script)
    save_state(uid, enabled)


def checked_config(uid):
    directory = Path(f'/run/user/{uid}/noki')
    config = directory / 'vpn.json'
    for path, directory_expected in ((directory, True), (config, False)):
        info = path.lstat()
        if (path.resolve() != path or info.st_uid != uid or info.st_mode & 0o077 or
                (not stat.S_ISDIR(info.st_mode) if directory_expected else not stat.S_ISREG(info.st_mode))):
            raise ValueError('VPN configuration must be private, caller-owned and not a link')
    if config.stat().st_size > 32 * 1024 * 1024:
        raise ValueError('VPN configuration is too large')
    return config


def core_identity(uid, pid, proc=Path('/proc')):
    process = proc / str(pid)
    owner = re.search(r'^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)',
                      (process / 'status').read_text(), re.M)
    scope = f'0::/system.slice/noki-vpn-{uid}.service'
    if (pid <= 1 or (process / 'exe').readlink().as_posix() != str(CORE) or
            not owner or any(int(value) != uid for value in owner.groups()) or
            scope not in (process / 'cgroup').read_text().splitlines()):
        raise ValueError('VPN service process identity does not match')
    return {'pid': pid}


def unit_command(uid, gid, home, config):
    if not PurePosixPath(home).is_absolute() or any(c in home for c in '\n\r\x00'):
        raise ValueError('Invalid account home')
    home = home.replace('%', '%%')
    return [executable('systemd-run'), '--quiet', '--collect', f'--unit=noki-vpn-{uid}',
            f'--uid={uid}', f'--gid={gid}', '--property=Type=exec', '--property=Restart=no',
            '--property=NoNewPrivileges=yes', '--property=AmbientCapabilities=CAP_NET_ADMIN',
            '--property=CapabilityBoundingSet=CAP_NET_ADMIN', '--property=ProtectSystem=strict',
            '--property=ProtectKernelTunables=yes', '--property=ProtectControlGroups=yes',
            '--property=PrivateTmp=yes', '--property=UMask=0077', '--property=KillMode=control-group',
            '--property=TimeoutStopSec=2s',
            f'--property=ReadWritePaths={json.dumps(home)} /run/user/{uid}/noki',
            '--working-directory=/usr/lib/noki', '--setenv=LD_LIBRARY_PATH=/usr/lib/noki',
            str(CORE), 'run', '-c', str(config)]

def stop_unit(uid):
    result = invoke([executable('systemctl'), 'stop', f'noki-vpn-{uid}.service'], check=False)
    if result.returncode and 'not loaded' not in result.stderr and 'not found' not in result.stderr:
        raise ValueError('Failed to stop the VPN service')

def check_guard_owner(uid):
    for path in STATE.glob('*'):
        if path.name.isdecimal() and int(path.name) != uid and path.is_file() and not path.is_symlink():
            if json.loads(path.read_text()).get('guard') is True:
                raise ValueError('Disconnect Noki protection in the other user session first')


def main(arguments):
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise ValueError('Linux polkit privileges are required')
    if len(arguments) == 1 and arguments[0] in ('restore', 'upgrade', 'uninstall'):
        if 'PKEXEC_UID' in os.environ:
            raise ValueError('This operation belongs to the system package manager')
        for path in STATE.glob('*'):
            if not path.name.isdecimal() or not path.is_file() or path.is_symlink():
                continue
            uid = int(path.name)
            if arguments[0] == 'restore':
                if json.loads(path.read_text()).get('guard') is True:
                    set_guard(uid, True)
            elif arguments[0] == 'upgrade':
                stop_unit(uid)
                if json.loads(path.read_text()).get('guard') is True:
                    set_guard(uid, True)
            else:
                stop_unit(uid)
                set_guard(uid, False)
                path.unlink(missing_ok=True)
        return {}
    if len(arguments) != 2 or arguments[0] not in ('identity', 'start', 'stop', 'dns-on', 'on', 'off', 'status', 'refresh'):
        raise ValueError('Unsupported operation')
    uid = caller_uid(os.environ)
    account = pwd.getpwuid(uid)
    pid = int(arguments[1])
    action = arguments[0]
    scope = gui_scope(uid, pid)
    if action in ('start', 'on'):
        check_guard_owner(uid)
    if action == 'identity':
        value = Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()
        if not re.fullmatch(r'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}', value):
            raise ValueError('SMBIOS UUID is unavailable')
        raw = bytes.fromhex(value.replace('-', ''))
        if len(set(raw)) == 1 and raw[0] in (0, 255):
            raise ValueError('SMBIOS UUID is unavailable')
        return {'identity': 'smbios:' + (raw[:4][::-1] + raw[4:6][::-1] + raw[6:8][::-1] + raw[8:]).hex()}
    if action == 'start':
        config = checked_config(uid)
        active = invoke([executable('systemctl'), 'list-units', '--type=service', '--state=running,activating',
                         '--no-legend', '--plain', 'noki-vpn-*.service']).stdout
        if active.strip():
            raise ValueError('A Noki VPN service is already running; disconnect it first')
        save_state(uid, guard_enabled(uid))
        invoke(unit_command(uid, account.pw_gid, account.pw_dir, config))
        value = invoke([executable('systemctl'), 'show', f'noki-vpn-{uid}.service', '-p', 'MainPID', '--value']).stdout.strip()
        if not value.isdecimal() or int(value) <= 1:
            raise ValueError('VPN service did not start')
        # CAP_NET_ADMIN prevents the unprivileged GUI from reading /proc/pid/exe.
        return core_identity(uid, int(value))
    if action == 'stop':
        # A collected transient unit may already be absent after an early startup failure.
        stop_unit(uid)
        return {}
    if action == 'dns-on':
        # Removing the TUN also removes its resolved link settings; physical adapters remain untouched.
        if Path('/run/systemd/resolve/stub-resolv.conf').exists():
            invoke([executable('resolvectl'), 'dns', INTERFACE, '172.19.0.2'])
            invoke([executable('resolvectl'), 'domain', INTERFACE, '~.'])
            invoke([executable('resolvectl'), 'default-route', INTERFACE, 'yes'])
        return {}
    if action in ('on', 'off'):
        set_guard(uid, action == 'on', scope)
    elif action == 'refresh' and guard_enabled(uid):
        set_guard(uid, True, scope)
    return {'guard': guard_enabled(uid)}


if __name__ == '__main__':
    try:
        # Serialize guard transactions and the check/start pair across users.
        STATE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = os.open(STATE.parent / 'control.lock', os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            print(json.dumps(dict(ok=True, **main(sys.argv[1:]))))
    except Exception as error:
        print(json.dumps({'ok': False, 'error': str(error)}))
        sys.exit(1)
