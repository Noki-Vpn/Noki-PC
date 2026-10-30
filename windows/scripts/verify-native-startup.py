import ctypes
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import struct
import zlib
import threading
from ctypes import wintypes

exe = Path(sys.argv[1]).resolve()
allow_locked = '--allow-locked-desktop' in sys.argv[2:]
output = Path(__file__).resolve().parents[1] / 'build' / 'package-preflight'
output.mkdir(parents=True, exist_ok=True)
profile = Path(tempfile.mkdtemp(prefix='gui-', dir=output))
env = dict(os.environ, LOCALAPPDATA=str(profile))
startup = subprocess.STARTUPINFO()
startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
startup.wShowWindow = 1
user32 = ctypes.WinDLL('user32', use_last_error=True)
callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
user32.GetClientRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
user32.IsWindowVisible.argtypes = [wintypes.HWND]
user32.SendMessageTimeoutW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM, wintypes.UINT, wintypes.UINT, ctypes.POINTER(ctypes.c_size_t)]
user32.SendMessageTimeoutW.restype = wintypes.LPARAM
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.GetDC.argtypes = [wintypes.HWND]
user32.GetDC.restype = wintypes.HDC
user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
user32.ClientToScreen.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.POINT)]
user32.WindowFromPoint.argtypes = [wintypes.POINT]
user32.WindowFromPoint.restype = wintypes.HWND
user32.SetThreadDpiAwarenessContext.argtypes = [wintypes.HANDLE]
user32.SetThreadDpiAwarenessContext.restype = wintypes.HANDLE
user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
user32.GetWindowLongPtrW.argtypes = [wintypes.HWND,ctypes.c_int]
user32.GetWindowLongPtrW.restype = ctypes.c_ssize_t
user32.GetDpiForWindow.argtypes = [wintypes.HWND]
user32.GetDpiForWindow.restype = wintypes.UINT
user32.IsWindowEnabled.argtypes = [wintypes.HWND]
user32.IsHungAppWindow.argtypes=[wintypes.HWND]
user32.GetClassNameW.argtypes=[wintypes.HWND,wintypes.LPWSTR,ctypes.c_int]
user32.EnumChildWindows.argtypes=[wintypes.HWND,callback_type,wintypes.LPARAM]
dwm=ctypes.WinDLL('dwmapi')
dwm.DwmGetWindowAttribute.argtypes=[wintypes.HWND,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD]
gdi = ctypes.WinDLL('gdi32', use_last_error=True)
gdi.CreateCompatibleDC.argtypes = [wintypes.HDC]
gdi.CreateCompatibleDC.restype = wintypes.HDC
gdi.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
gdi.CreateCompatibleBitmap.restype = wintypes.HBITMAP
gdi.SelectObject.argtypes = [wintypes.HDC, wintypes.HANDLE]
gdi.SelectObject.restype = wintypes.HANDLE
gdi.GetDIBits.argtypes = [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT, ctypes.c_void_p, ctypes.c_void_p, wintypes.UINT]
gdi.DeleteObject.argtypes = [wintypes.HANDLE]
gdi.DeleteDC.argtypes = [wintypes.HDC]
gdi.BitBlt.argtypes = [wintypes.HDC,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,wintypes.HDC,ctypes.c_int,ctypes.c_int,wintypes.DWORD]
kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
psapi = ctypes.WinDLL('psapi', use_last_error=True)
kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE,wintypes.DWORD,wintypes.LPWSTR,ctypes.POINTER(wintypes.DWORD)]
psapi.EnumProcessModulesEx.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE), wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.DWORD]
psapi.GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, wintypes.LPWSTR, wintypes.DWORD]
out = profile / 'stdout.txt'
err = profile / 'stderr.txt'

def responsive(hwnd):
    value = ctypes.c_size_t()
    return bool(user32.SendMessageTimeoutW(hwnd, 0, 0, 0, 3, 1000, ctypes.byref(value)))

def windows_lock_app(pid):
    handle=kernel32.OpenProcess(0x1000,False,pid)
    if not handle: return False
    try:
        path=ctypes.create_unicode_buffer(32768)
        length=wintypes.DWORD(len(path))
        if not kernel32.QueryFullProcessImageNameW(handle,0,path,ctypes.byref(length)): return False
        expected=Path(os.environ['WINDIR'])/'SystemApps'/'Microsoft.LockApp_cw5n1h2txyewy'/'LockApp.exe'
        return Path(path.value).resolve() == expected.resolve()
    finally: kernel32.CloseHandle(handle)

def capture(hwnd):
    user32.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))
    assert user32.SetWindowPos(hwnd,wintypes.HWND(-1),0,0,0,0,0x13)
    time.sleep(.5)
    rect = wintypes.RECT()
    assert user32.GetClientRect(hwnd, ctypes.byref(rect))
    width, height = rect.right, rect.bottom
    scale=(user32.GetDpiForWindow(hwnd) or 96)/96
    margin, body_y=round(30*scale),round(80*scale)
    origin = wintypes.POINT()
    assert user32.ClientToScreen(hwnd,ctypes.byref(origin))
    cloaked=wintypes.DWORD()
    dwm.DwmGetWindowAttribute(hwnd,14,ctypes.byref(cloaked),4)
    print('Capture window styles:',hex(user32.GetWindowLongPtrW(hwnd,-16)),hex(user32.GetWindowLongPtrW(hwnd,-20)), 'enabled:',bool(user32.IsWindowEnabled(hwnd)),'cloaked:',cloaked.value,'origin:',(origin.x,origin.y))
    hit=ctypes.c_size_t()
    user32.SendMessageTimeoutW(hwnd,0x84,0,((origin.y+body_y)<<16)|((origin.x+margin)&0xffff),3,1000,ctypes.byref(hit))
    print('Client WM_NCHITTEST result:',hit.value)
    assert hit.value == 1, 'Client hit-test must return HTCLIENT'
    children=[]
    @callback_type
    def child_info(child,_):
        r=wintypes.RECT();user32.GetClientRect(child,ctypes.byref(r))
        v=ctypes.c_size_t();user32.SendMessageTimeoutW(child,0x84,0,((origin.y+body_y)<<16)|((origin.x+margin)&0xffff),3,1000,ctypes.byref(v))
        children.append((r.right,r.bottom,ctypes.c_ssize_t(v.value).value,user32.GetWindowThreadProcessId(child,None)))
        return True
    user32.EnumChildWindows(hwnd,child_info,0)
    print('Child clients/hit-tests:',children,'hung:',bool(user32.IsHungAppWindow(hwnd)))
    assert not user32.IsHungAppWindow(hwnd), 'Windows marks the native window as hung'
    # Nucleus' GL child deliberately returns HTTRANSPARENT; the Tao parent owns input.
    parent_thread=user32.GetWindowThreadProcessId(hwnd,None)
    assert all(value == 1 or value == -1 and thread == parent_thread for w,h,value,thread in children if w >= width//2 and h >= height//2), 'Render child must accept or delegate client input to its Tao thread'
    for x,y in [(margin,body_y),(width-margin,body_y),(margin,height-margin),(width-margin,height-margin),(width//2,height//2)]:
        owner=wintypes.DWORD()
        found=user32.WindowFromPoint(wintypes.POINT(origin.x+x,origin.y+y))
        user32.GetWindowThreadProcessId(found,ctypes.byref(owner))
        if owner.value != process.pid:
            name=ctypes.create_unicode_buffer(256);user32.GetClassNameW(found,name,len(name))
            if allow_locked and (name.value == 'LockScreenBackstopFrame' or windows_lock_app(owner.value)):
                print('Screenshot NOT VERIFIED: Windows lock screen covers the client; startup/input-routing/resize checks passed')
                return False
        assert owner.value == process.pid, f'Test client is covered at {(origin.x+x,origin.y+y)} by PID {owner.value}; expected {process.pid}'
    source = user32.GetDC(None)
    dc = gdi.CreateCompatibleDC(source)
    bitmap = gdi.CreateCompatibleBitmap(source, width, height)
    previous = gdi.SelectObject(dc, bitmap)
    try:
        # Capture the DWM-composited client pixels, including the ANGLE child surface.
        assert gdi.BitBlt(dc,0,0,width,height,source,origin.x,origin.y,0x40CC0020)
        gdi.SelectObject(dc, previous)
        previous = None
        info = ctypes.create_string_buffer(struct.pack('<IiiHHIIiiII', 40, width, -height, 1, 32, 0, 0, 0, 0, 0, 0))
        pixels = ctypes.create_string_buffer(width * height * 4)
        assert gdi.GetDIBits(source, bitmap, 0, height, pixels, info, 0) == height
        data = pixels.raw
        rgb = bytearray(width * height * 3)
        rgb[0::3], rgb[1::3], rgb[2::3] = data[2::4], data[1::4], data[0::4]
        colors = {bytes(rgb[(y*width+x)*3:(y*width+x)*3+3]) for y in range(body_y, height-margin, 4) for x in range(margin, width-margin, 4)}
        def chunk(kind, payload):
            return struct.pack('>I',len(payload)) + kind + payload + struct.pack('>I',zlib.crc32(kind+payload))
        rows = b''.join(b'\0' + rgb[y*width*3:(y+1)*width*3] for y in range(height))
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0)) + chunk(b'IDAT',zlib.compress(rows)) + chunk(b'IEND',b'')
        (profile/'frame.png').write_bytes(png)
        assert len(colors) >= 32, 'Client content is blank or uniform'
        print('Rendered client frame:',profile/'frame.png','sampled colors:',len(colors))
        return True
    finally:
        if previous: gdi.SelectObject(dc,previous)
        gdi.DeleteObject(bitmap)
        gdi.DeleteDC(dc)
        user32.ReleaseDC(None,source)

with out.open('wb') as stdout, err.open('wb') as stderr:
    process = subprocess.Popen([str(exe)], cwd=exe.parent, env=env, startupinfo=startup, stdout=stdout, stderr=stderr)
    windows = []
    observed = {}
    @callback_type
    def visit(hwnd, _):
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value == process.pid:
            rect = wintypes.RECT()
            user32.GetClientRect(hwnd, ctypes.byref(rect))
            width, height = rect.right - rect.left, rect.bottom - rect.top
            observed[int(hwnd)] = (width, height, bool(user32.IsWindowVisible(hwnd)))
            if width >= 400 and height >= 300:
                if user32.IsWindowVisible(hwnd): windows.append(hwnd)
        return True
    try:
        deadline = time.monotonic() + 30
        stable = 0
        while process.poll() is None and time.monotonic() < deadline:
            windows.clear()
            user32.EnumWindows(visit, 0)
            stable = stable + 1 if windows and responsive(windows[0]) else 0
            if stable >= 3: break
            time.sleep(1)
        print('Startup process exit:', process.poll(), 'visible client windows:', len(windows), 'responsive samples:', stable)
        assert stable >= 3, 'Native window did not respond to WM_NULL'
        hwnd = windows[0]
        for width, height in [(900,600),(500,450),(1050,674)]:
            assert user32.SetWindowPos(hwnd,None,0,0,width,height,0x16)
            time.sleep(.5)
            assert responsive(hwnd), 'Native window hung during resize'
        failures=[]
        rendered=[]
        def capture_frame():
            try: rendered.append(capture(hwnd))
            except BaseException as error: failures.append(error)
        capture_thread=threading.Thread(target=capture_frame,daemon=True)
        capture_thread.start()
        capture_thread.join(5)
        assert not capture_thread.is_alive(), 'Native frame capture timed out'
        if failures: raise failures[0]
        if process.poll() is None:
            user32.EnumWindows(visit, 0)
            handle = kernel32.OpenProcess(0x0410, False, process.pid)
            assert handle, 'Cannot inspect smoke process modules'
            try:
                modules = (wintypes.HMODULE * 2048)()
                size = wintypes.DWORD()
                assert psapi.EnumProcessModulesEx(handle, modules, ctypes.sizeof(modules), ctypes.byref(size), 3)
                loaded = set()
                for module in modules[:size.value // ctypes.sizeof(wintypes.HMODULE)]:
                    path = ctypes.create_unicode_buffer(32768)
                    if psapi.GetModuleFileNameExW(handle, module, path, len(path)):
                        loaded.add(Path(path.value).name.lower())
            finally:
                kernel32.CloseHandle(handle)
        else:
            loaded = set()
        result = process.poll()
    except BaseException:
        print('Failed startup profile:', profile)
        print('Observed own HWNDs (client width, height, visible):', observed)
        print(err.read_text(encoding='utf-8', errors='replace')[-16000:])
        raise
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
text = err.read_text(encoding='utf-8', errors='replace')
bad = any(item in text for item in ['UnsatisfiedLinkError', 'Exception in thread', 'MissingReflectionRegistration', 'NoSuchMethodError', 'Exception:'])
print('Exit before cleanup:', result, 'responsive window and three resizes verified')
print('JDK compatibility modules:', sorted(loaded & {'awt.dll', 'jvm.dll', 'java.dll', 'jawt.dll'}))
print('Isolated profile:', profile)
print(text[-10000:])
assert result is None and windows and not bad, 'Native GUI startup failed; see isolated stderr'
print('Native GUI startup, input routing and resize passed; rendered frame verified:', rendered[0])
