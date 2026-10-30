"""Check effective Win32 metadata and external native libraries against the application JAR."""
import json
import mmap
from pathlib import Path
import re
import sys
import zipfile


def verify_native_libraries(arguments, jar, package_root):
    prefix = 'nucleus/native/win32-x64/'
    nucleus = [name for name in jar.namelist() if name.startswith(prefix) and name.endswith('.dll')]
    filenames = [name.rsplit('/', 1)[-1] for name in nucleus]
    assert len(filenames) == len({name.casefold() for name in filenames}), 'Colliding Windows Nucleus native library names'
    required = {'libEGL.dll', 'libGLESv2.dll', 'nucleus_tao.dll', 'nucleus_tao_dnd.dll',
                'nucleus_tao_gl.dll', 'nucleus_tao_windows_deco.dll', 'nucleus_tao_windows_native_view.dll'}
    assert required <= set(filenames), 'Missing Windows Tao native libraries'
    patterns = [argument.split('=', 1)[1] for argument in arguments if argument.startswith('-H:ExcludeResources=')]
    native_exe = Path(arguments[arguments.index('-o') + 1]).with_suffix('.exe')
    with native_exe.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as image:
        for name in sorted(set(nucleus) | {'skiko-windows-x64.dll', 'icudtl.dat'}):
            assert any(re.fullmatch(pattern, name) for pattern in patterns), f'Native payload not excluded: {name}'
            payload = jar.read(name)
            sidecar = package_root / 'bin' / name.rsplit('/', 1)[-1]
            assert sidecar.read_bytes() == payload, f'External native payload differs from its dependency: {sidecar}'
            assert image.find(payload) < 0, f'Native payload is still embedded in the EXE: {name}'
    print(f'External native libraries verified: {len(nucleus) + 2} payloads, none duplicated in the EXE')


def verify(arguments_file):
    arguments = [json.loads(line) for line in arguments_file.read_text(encoding='utf-8').splitlines() if line.strip()]
    entries = []
    sources = []

    def read_config(name, content):
        document = json.loads(content)
        if name.endswith('reachability-metadata.json'):
            entries.extend(document.get('reflection', []))
        elif name.endswith('reflect-config.json'):
            entries.extend(dict(item, type=item['name']) for item in document)
        elif name.endswith('proxy-config.json'):
            entries.extend({'type': {'proxy': item}} if isinstance(item, list) else
                           dict(item, type={'proxy': item['interfaces']}) for item in document)
        else:
            return
        sources.append(name)

    for argument in arguments:
        if argument.startswith('-H:ConfigurationFileDirectories='):
            for directory in argument.split('=', 1)[1].split(','):
                for name in ('reachability-metadata.json', 'reflect-config.json', 'proxy-config.json'):
                    file = Path(directory) / name
                    if file.is_file():
                        read_config(str(file), file.read_text(encoding='utf-8'))
    archive = Path(arguments[arguments.index('-jar') + 1])
    with zipfile.ZipFile(archive) as jar:
        assert 'noki/desktop/earth/countries.bin' in jar.namelist(), 'Packed country geometry is missing'
        assert 'noki/desktop/earth/relief-4k.png' in jar.namelist(), 'Window Earth atlas is missing'
        for authoring in ('relief.png', 'countries.json'):
            assert 'noki/desktop/earth/' + authoring not in jar.namelist(), f'Authoring resource in Native Image: {authoring}'
        package_root = Path(__file__).resolve().parents[1] / 'build/compose/binaries/main/graalvm-app/Noki'
        verify_native_libraries(arguments, jar, package_root)
        for name in jar.namelist():
            if name.startswith('META-INF/native-image/') and name.endswith(('reachability-metadata.json', 'reflect-config.json', 'proxy-config.json')):
                read_config(name, jar.read(name).decode('utf-8'))

    def registrations(type_name):
        # Conditional metadata alone does not guarantee these startup bindings are available.
        return [item for item in entries if item.get('type') == type_name and not item.get('condition')]

    prefix = 'com.sun.jna.platform.win32.'
    handles = ('WinUser$HMONITOR', 'WinNT$HANDLE', 'WinDef$HWND', 'WinDef$HINSTANCE', 'WinDef$HMODULE',
               'WinDef$HICON', 'WinDef$HCURSOR', 'WinDef$HBRUSH', 'WinDef$ATOM', 'WinDef$WPARAM',
               'WinDef$LPARAM', 'WinDef$LRESULT', 'WinDef$BOOL', 'WinDef$WORD', 'WinDef$DWORD', 'WinReg$HKEY', 'WinNT$HRESULT',
               'WinDef$POINT$ByValue')
    for name in handles:
        registered = registrations(prefix + name)
        assert any(item.get('allDeclaredConstructors') or item.get('allPublicConstructors') or
                   any(method.get('name') == '<init>' and method.get('parameterTypes') == []
                       for method in item.get('methods', [])) for item in registered), f'Missing public no-arg constructor: {name}'

    fields = {
        'ShellAPI': {'STRUCTURE_ALIGNMENT', 'TYPE_MAPPER'},
        'WinBase$FILETIME': {'dwLowDateTime', 'dwHighDateTime'},
        'WinBase$STARTUPINFO': {'cb', 'lpReserved', 'lpDesktop', 'lpTitle', 'dwX', 'dwY',
                              'dwXSize', 'dwYSize', 'dwXCountChars', 'dwYCountChars', 'dwFillAttribute',
                              'dwFlags', 'wShowWindow', 'cbReserved2', 'lpReserved2', 'hStdInput', 'hStdOutput', 'hStdError'},
        'WinBase$PROCESS_INFORMATION': {'hProcess', 'hThread', 'dwProcessId', 'dwThreadId'},
        'IPHlpAPI$MIB_IFROW': {'wszName', 'dwIndex', 'dwType', 'dwMtu', 'dwSpeed', 'dwPhysAddrLen', 'bPhysAddr',
                             'dwAdminStatus', 'dwOperStatus', 'dwLastChange', 'dwInOctets', 'dwInUcastPkts',
                             'dwInNUcastPkts', 'dwInDiscards', 'dwInErrors', 'dwInUnknownProtos', 'dwOutOctets',
                             'dwOutUcastPkts', 'dwOutNUcastPkts', 'dwOutDiscards', 'dwOutErrors', 'dwOutQLen',
                             'dwDescrLen', 'bDescr'},
        'WinUser$MONITORINFO': {'cbSize', 'rcMonitor', 'rcWork', 'dwFlags'},
        'WinUser$WNDCLASSEX': {'cbSize', 'style', 'lpfnWndProc', 'cbClsExtra', 'cbWndExtra', 'hInstance',
                             'hIcon', 'hCursor', 'hbrBackground', 'lpszMenuName', 'lpszClassName', 'hIconSm'},
        'WinUser$MSG': {'hWnd', 'message', 'wParam', 'lParam', 'time', 'pt'},
    }
    for name, required in fields.items():
        registered = registrations(prefix + name)
        actual = {field['name'] for item in registered for field in item.get('fields', [])}
        assert any(item.get('allDeclaredFields') or item.get('allPublicFields') for item in registered) or required <= actual, f'Missing JNA fields: {name}: {required - actual}'

    proxies = {tuple(item['type']['proxy']) for item in entries if isinstance(item.get('type'), dict) and 'proxy' in item['type'] and not item.get('condition')}
    for name in ('User32', 'Kernel32', 'Shell32', 'Advapi32', 'Ole32', 'Crypt32', 'IPHlpAPI'):
        assert (prefix + name,) in proxies, f'Missing Win32 proxy: {name}'
    for name in ('Wfp', 'Security', 'IpHelper'):
        assert ('com.noki.desktop.WindowsNetworkGuard$' + name,) in proxies, f'Missing application proxy: {name}'
    for callback in ('VelopackProgress',):
        assert any(item.get('allDeclaredMethods') or item.get('allPublicMethods') or
                   any(method.get('name') == 'invoke' for method in item.get('methods', []))
                   for item in registrations('com.noki.desktop.' + callback)), f'Missing native callback: {callback}'
    print(f'Win32 native metadata verified: {len(sources)} configs, {len(handles)} constructors, {len(fields)} field sets, 10 proxies')


if __name__ == '__main__':
    default = Path(__file__).resolve().parents[1] / 'build/compose/tmp/main/graalvm/nativeCompile/native-image-args.txt'
    verify(Path(sys.argv[1]) if len(sys.argv) > 1 else default)
