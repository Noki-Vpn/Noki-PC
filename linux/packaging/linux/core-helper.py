#!/usr/bin/python3 -I
"""Fixed polkit entry point. The core runs as the caller, never as root."""
import json
import hashlib
import ipaddress
import fcntl
import os
from pathlib import Path, PurePosixPath
import pwd
import re
import stat
import subprocess
import sys
import time

CORE = PurePosixPath('/usr/lib/noki/sing-box')
BYEDPI = PurePosixPath('/usr/lib/noki/ciadpi')
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


def nft_rules(uid, scope=None, youtube_interface=None):
    if uid <= 0 or uid > 2**32 - 2:
        raise ValueError('Invalid UID')
    table = f'noki_{uid}'
    gui = ''
    if scope:
        scope = checked_scope(uid, '/' + scope.lstrip('/'))
        gui = f'    socket cgroupv2 level {len(scope.split("/"))} "{scope}" accept\n'
    youtube = ''
    if youtube_interface:
        if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,15}', youtube_interface) or youtube_interface in ('lo', INTERFACE):
            raise ValueError('Invalid physical interface')
        group = f'socket cgroupv2 level 2 "system.slice/noki-byedpi-{uid}.service"'
        youtube = (f'    {group} oifname "{youtube_interface}" meta l4proto tcp meta mark {MARK} accept\n'
                   f'    {group} reject\n')
    return (f'table inet {table} {{\n  chain output {{\n    type filter hook output priority -10; policy accept;\n'
            f'    oifname "lo" accept\n'
            + youtube + f'    meta mark {MARK} accept\n    oifname "{INTERFACE}" accept\n'
            + gui + f'    udp dport 53 reject\n    tcp dport 53 reject\n'
            f'    meta skuid != {uid} accept\n    reject\n  }}\n}}\n')


def guard_enabled(uid):
    return invoke([executable('nft'), 'list', 'table', 'inet', f'noki_{uid}'], check=False).returncode == 0


def save_state(uid, enabled, youtube_interface=None):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = STATE / f'{uid}.tmp'
    temporary.write_text(json.dumps({'guard': bool(enabled), 'youtube_interface': youtube_interface}))
    temporary.chmod(0o600)
    temporary.replace(STATE / str(uid))


def set_guard(uid, enabled, scope=None, youtube_interface=None):
    if youtube_interface is None:
        record = STATE / str(uid)
        if record.is_file() and Path(f'/sys/fs/cgroup/system.slice/noki-byedpi-{uid}.service').is_dir():
            youtube_interface = json.loads(record.read_text()).get('youtube_interface')
    table = f'noki_{uid}'
    prefix = f'delete table inet {table}\n' if guard_enabled(uid) else ''
    script = prefix + (nft_rules(uid, scope, youtube_interface) if enabled else '')
    if script:
        invoke([executable('nft'), '-f', '-'], input=script)
    save_state(uid, enabled, youtube_interface)


def checked_config(uid, name='vpn.json'):
    if name not in ('vpn.json', 'byedpi.json'):
        raise ValueError('Unsupported configuration')
    directory = Path(f'/run/user/{uid}/noki')
    config = directory / name
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

def stop_unit(uid, service='noki-vpn'):
    if service not in ('noki-vpn', 'noki-byedpi'):
        raise ValueError('Unsupported service')
    result = invoke([executable('systemctl'), 'stop', f'{service}-{uid}.service'], check=False)
    if result.returncode and 'not loaded' not in result.stderr and 'not found' not in result.stderr:
        raise ValueError('Failed to stop the VPN service')


def byedpi_arguments(config, parent):
    if set(config) != {'port', 'interface', 'source', 'parent'}:
        raise ValueError('Unexpected ByeDPI configuration')
    port, interface, source = config['port'], config['interface'], config['source']
    if (type(port) is not int or not 1 <= port <= 65535 or config['parent'] != parent or
            not isinstance(interface, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,15}', interface) or
            interface in ('lo', INTERFACE)):
        raise ValueError('Invalid ByeDPI physical binding')
    address = ipaddress.IPv4Address(source)
    if address.is_unspecified or address.is_loopback or address.is_link_local:
        raise ValueError('Invalid ByeDPI source')
    return ['--ip', '127.0.0.1', '--port', str(port), '--max-conn', '4096', '--conn-ip', '0.0.0.0',
            '--no-udp', '--no-domain', '--proto', 'tls,http', '--fake', '-1', '--md5sig',
            '--noki-interface', interface, '--noki-source', str(address), '--noki-mark', str(MARK),
            '--noki-parent', str(parent)]


def byedpi_start(uid, account, scope):
    for name in (str(BYEDPI), '/usr/lib/noki/byedpi-linux.properties'):
        path = Path(name)
        info = path.lstat()
        if path.resolve() != path or info.st_uid != 0 or info.st_mode & 0o022 or not stat.S_ISREG(info.st_mode):
            raise ValueError('ByeDPI runtime must be root-owned and immutable to users')
    manifest = dict(line.split('=', 1) for line in Path('/usr/lib/noki/byedpi-linux.properties').read_text().splitlines() if '=' in line)
    if (manifest.get('version') != '0.17.3' or manifest.get('profile') != 'linux-md5sig' or
            hashlib.sha256(Path(BYEDPI).read_bytes()).hexdigest() != manifest.get('ciadpi')):
        raise ValueError('ByeDPI runtime checksum mismatch')
    parent = int(invoke([executable('systemctl'), 'show', f'noki-vpn-{uid}.service', '-p', 'MainPID', '--value']).stdout.strip())
    core_identity(uid, parent)
    config = json.loads(checked_config(uid, 'byedpi.json').read_text())
    arguments = byedpi_arguments(config, parent)
    if not Path('/sys/class/net', config['interface'], 'device').is_dir():
        raise ValueError('ByeDPI requires a physical network interface')
    addresses = json.loads(invoke([executable('ip'), '-j', '-4', 'addr', 'show', 'dev', config['interface']]).stdout)
    if not any(row.get('local') == config['source'] for adapter in addresses for row in adapter.get('addr_info', [])):
        raise ValueError('ByeDPI source does not belong to the physical interface')
    stop_unit(uid, 'noki-byedpi')
    command = [executable('systemd-run'), '--quiet', '--collect', f'--unit=noki-byedpi-{uid}',
               f'--uid={uid}', f'--gid={account.pw_gid}', '--property=Type=exec', '--property=Restart=no',
               '--property=NoNewPrivileges=yes', '--property=AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW',
               '--property=CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW', '--property=ProtectSystem=strict',
               '--property=ProtectHome=yes', '--property=ProtectControlGroups=yes', '--property=PrivateTmp=yes',
               '--property=UMask=0077', '--property=KillMode=control-group', '--property=TimeoutStopSec=2s',
               f'--property=BindsTo=noki-vpn-{uid}.service', f'--property=After=noki-vpn-{uid}.service',
               '--property=RestrictAddressFamilies=AF_INET AF_UNIX', str(BYEDPI)] + arguments
    try:
        invoke(command)
        unit = f'noki-byedpi-{uid}.service'
        invocation = invoke([executable('systemctl'), 'show', unit, '-p', 'InvocationID', '--value']).stdout.strip()
        if not re.fullmatch('[a-f0-9]{32}', invocation):
            raise ValueError('Invalid ByeDPI invocation')
        expected = f"NOKI_READY port={config['port']} profile=linux-md5sig"
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            value = invoke([executable('systemctl'), 'show', unit, '-p', 'MainPID', '--value']).stdout.strip()
            if not value.isdecimal() or int(value) <= 1:
                raise ValueError('ByeDPI exited before readiness')
            process = Path(f'/proc/{value}')
            if (process / 'exe').readlink().as_posix() != str(BYEDPI) or f'0::/system.slice/{unit}' not in (process / 'cgroup').read_text().splitlines():
                raise ValueError('ByeDPI process identity mismatch')
            logs = invoke([executable('journalctl'), '--no-pager', '-o', 'cat', '-n', '20', f'_SYSTEMD_INVOCATION_ID={invocation}']).stdout
            if expected in logs.splitlines():
                if guard_enabled(uid):
                    set_guard(uid, True, scope, config['interface'])
                return {'pid': int(value), 'port': config['port']}
            time.sleep(0.05)
        raise ValueError('ByeDPI did not confirm the Linux MD5 profile')
    except BaseException:
        stop_unit(uid, 'noki-byedpi')
        raise

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
                stop_unit(uid, 'noki-byedpi')
                if json.loads(path.read_text()).get('guard') is True:
                    set_guard(uid, True)
            else:
                stop_unit(uid)
                stop_unit(uid, 'noki-byedpi')
                set_guard(uid, False)
                path.unlink(missing_ok=True)
        return {}
    if len(arguments) != 2 or arguments[0] not in ('identity', 'start', 'stop', 'dns-on', 'on', 'off', 'status', 'refresh', 'byedpi-start', 'byedpi-stop'):
        raise ValueError('Unsupported operation')
    uid = caller_uid(os.environ)
    account = pwd.getpwuid(uid)
    pid = int(arguments[1])
    action = arguments[0]
    scope = gui_scope(uid, pid)
    if action == 'byedpi-start':
        return byedpi_start(uid, account, scope)
    if action == 'byedpi-stop':
        stop_unit(uid, 'noki-byedpi')
        if guard_enabled(uid):
            set_guard(uid, True, scope, '')
        return {}
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
        stop_unit(uid, 'noki-byedpi')
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
