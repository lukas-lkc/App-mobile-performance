"""Chamadas diretas da API do Windows (ctypes), sem dependencias externas.

Tudo aqui e leve: o monitor chama estas funcoes a cada 2 s sem pesar no PC.
"""
import ctypes
import os
import subprocess
import time
from ctypes import wintypes as W

IS_WIN = os.name == "nt"
NO_WINDOW = 0x08000000 if IS_WIN else 0
BELOW_NORMAL = 0x00004000 if IS_WIN else 0

if IS_WIN:
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    sh32 = ctypes.WinDLL("shell32", use_last_error=True)
    ver = ctypes.WinDLL("version", use_last_error=True)


class FILETIME(ctypes.Structure):
    _fields_ = [("lo", W.DWORD), ("hi", W.DWORD)]

    def value(self):
        return (self.hi << 32) | self.lo


class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [("dwLength", W.DWORD), ("dwMemoryLoad", W.DWORD), ("ullTotalPhys", ctypes.c_ulonglong),
                ("ullAvailPhys", ctypes.c_ulonglong), ("ullTotalPageFile", ctypes.c_ulonglong),
                ("ullAvailPageFile", ctypes.c_ulonglong), ("ullTotalVirtual", ctypes.c_ulonglong),
                ("ullAvailVirtual", ctypes.c_ulonglong), ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", W.DWORD), ("cntUsage", W.DWORD), ("th32ProcessID", W.DWORD),
                ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", W.DWORD), ("cntThreads", W.DWORD),
                ("th32ParentProcessID", W.DWORD), ("pcPriClassBase", W.LONG), ("dwFlags", W.DWORD),
                ("szExeFile", W.WCHAR * 260)]


class PROCESS_MEMORY_COUNTERS_EX(ctypes.Structure):
    _fields_ = [("cb", W.DWORD), ("PageFaultCount", W.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t), ("PrivateUsage", ctypes.c_size_t)]


class SHFILEOPSTRUCTW(ctypes.Structure):
    _fields_ = [("hwnd", W.HWND), ("wFunc", W.UINT), ("pFrom", W.LPCWSTR), ("pTo", W.LPCWSTR),
                ("fFlags", W.WORD), ("fAnyOperationsAborted", W.BOOL), ("hNameMappings", ctypes.c_void_p),
                ("lpszProgressTitle", W.LPCWSTR)]


class SHQUERYRBINFO(ctypes.Structure):
    _fields_ = [("cbSize", W.DWORD), ("i64Size", ctypes.c_longlong), ("i64NumItems", ctypes.c_longlong)]


class SHELLEXECUTEINFOW(ctypes.Structure):
    _fields_ = [("cbSize", W.DWORD), ("fMask", W.ULONG), ("hwnd", W.HWND), ("lpVerb", W.LPCWSTR),
                ("lpFile", W.LPCWSTR), ("lpParameters", W.LPCWSTR), ("lpDirectory", W.LPCWSTR),
                ("nShow", ctypes.c_int), ("hInstApp", W.HINSTANCE), ("lpIDList", ctypes.c_void_p),
                ("lpClass", W.LPCWSTR), ("hkeyClass", W.HKEY), ("dwHotKey", W.DWORD),
                ("hIconOrMonitor", W.HANDLE), ("hProcess", W.HANDLE)]


if IS_WIN:
    k32.GetSystemTimes.argtypes = [ctypes.POINTER(FILETIME)] * 3
    k32.GlobalMemoryStatusEx.argtypes = [ctypes.POINTER(MEMORYSTATUSEX)]
    k32.CreateToolhelp32Snapshot.argtypes = [W.DWORD, W.DWORD]
    k32.CreateToolhelp32Snapshot.restype = W.HANDLE
    k32.Process32FirstW.argtypes = [W.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    k32.Process32NextW.argtypes = [W.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
    k32.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
    k32.OpenProcess.restype = W.HANDLE
    k32.CloseHandle.argtypes = [W.HANDLE]
    k32.GetProcessTimes.argtypes = [W.HANDLE] + [ctypes.POINTER(FILETIME)] * 4
    k32.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, ctypes.POINTER(W.DWORD)]
    k32.K32GetProcessMemoryInfo.argtypes = [W.HANDLE, ctypes.POINTER(PROCESS_MEMORY_COUNTERS_EX), W.DWORD]
    k32.GetTickCount64.restype = ctypes.c_ulonglong
    k32.GetLogicalDrives.restype = W.DWORD
    k32.GetDriveTypeW.argtypes = [W.LPCWSTR]
    k32.GetVolumeInformationW.argtypes = [W.LPCWSTR, W.LPWSTR, W.DWORD, ctypes.POINTER(W.DWORD),
                                          ctypes.POINTER(W.DWORD), ctypes.POINTER(W.DWORD), W.LPWSTR, W.DWORD]
    k32.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
    k32.GetExitCodeProcess.argtypes = [W.HANDLE, ctypes.POINTER(W.DWORD)]
    ver.GetFileVersionInfoSizeW.argtypes = [W.LPCWSTR, ctypes.POINTER(W.DWORD)]
    ver.GetFileVersionInfoW.argtypes = [W.LPCWSTR, W.DWORD, W.DWORD, ctypes.c_void_p]
    ver.VerQueryValueW.argtypes = [ctypes.c_void_p, W.LPCWSTR, ctypes.POINTER(ctypes.c_void_p),
                                   ctypes.POINTER(W.UINT)]
    sh32.SHFileOperationW.argtypes = [ctypes.POINTER(SHFILEOPSTRUCTW)]
    sh32.SHQueryRecycleBinW.argtypes = [W.LPCWSTR, ctypes.POINTER(SHQUERYRBINFO)]
    sh32.SHEmptyRecycleBinW.argtypes = [W.HWND, W.LPCWSTR, W.DWORD]
    sh32.ShellExecuteExW.argtypes = [ctypes.POINTER(SHELLEXECUTEINFOW)]

INVALID_HANDLE = ctypes.c_void_p(-1).value
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


# ----------------------------------------------------------------------------- sistema
def is_admin():
    try:
        return bool(sh32.IsUserAnAdmin())
    except Exception:  # noqa: BLE001
        return False


def system_times():
    """(idle, kernel, user) em unidades de 100 ns. kernel inclui o idle."""
    i, k, u = FILETIME(), FILETIME(), FILETIME()
    k32.GetSystemTimes(ctypes.byref(i), ctypes.byref(k), ctypes.byref(u))
    return i.value(), k.value(), u.value()


def memory():
    m = MEMORYSTATUSEX()
    m.dwLength = ctypes.sizeof(m)
    k32.GlobalMemoryStatusEx(ctypes.byref(m))
    return {"load": m.dwMemoryLoad, "total": m.ullTotalPhys, "free": m.ullAvailPhys,
            "commit_total": m.ullTotalPageFile, "commit_free": m.ullAvailPageFile}


def uptime_s():
    return int(k32.GetTickCount64() // 1000)


# ----------------------------------------------------------------------------- processos
def list_processes():
    """[(pid, ppid, nome, threads)] de todos os processos (mesmo os protegidos)."""
    snap = k32.CreateToolhelp32Snapshot(2, 0)  # TH32CS_SNAPPROCESS
    if not snap or snap == INVALID_HANDLE:
        return []
    out = []
    try:
        e = PROCESSENTRY32W()
        e.dwSize = ctypes.sizeof(e)
        ok = k32.Process32FirstW(snap, ctypes.byref(e))
        while ok:
            out.append((e.th32ProcessID, e.th32ParentProcessID, e.szExeFile, e.cntThreads))
            ok = k32.Process32NextW(snap, ctypes.byref(e))
    finally:
        k32.CloseHandle(snap)
    return out


def proc_query(pid, want_path):
    """(criado, cpu_100ns, memoria_bytes, caminho) ou None se o processo e protegido."""
    h = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return None
    try:
        c, x, k, u = FILETIME(), FILETIME(), FILETIME(), FILETIME()
        if not k32.GetProcessTimes(h, ctypes.byref(c), ctypes.byref(x), ctypes.byref(k), ctypes.byref(u)):
            return None
        pm = PROCESS_MEMORY_COUNTERS_EX()
        pm.cb = ctypes.sizeof(pm)
        mem = pm.PrivateUsage if k32.K32GetProcessMemoryInfo(h, ctypes.byref(pm), pm.cb) else 0
        ws = pm.WorkingSetSize if mem else 0
        path = None
        if want_path:
            buf = ctypes.create_unicode_buffer(1024)
            n = W.DWORD(1024)
            if k32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
                path = buf.value
        return c.value(), k.value() + u.value(), max(ws, 0), path
    finally:
        k32.CloseHandle(h)


_ver_cache = {}


def file_info(path):
    """{'desc','company','product'} lidos dos detalhes do .exe (ou {})."""
    if not path:
        return {}
    if path in _ver_cache:
        return _ver_cache[path]
    info = {}
    try:
        size = ver.GetFileVersionInfoSizeW(path, None)
        if size:
            buf = ctypes.create_string_buffer(size)
            if ver.GetFileVersionInfoW(path, 0, size, buf):
                p, n = ctypes.c_void_p(), W.UINT()
                langs = []
                if ver.VerQueryValueW(buf, "\\VarFileInfo\\Translation", ctypes.byref(p), ctypes.byref(n)) \
                        and n.value >= 4:
                    w = (W.WORD * 2).from_address(p.value)
                    langs.append(f"{w[0]:04x}{w[1]:04x}")
                langs += ["040904b0", "041604b0", "040904e4", "000004b0"]
                for key, name in (("FileDescription", "desc"), ("CompanyName", "company"), ("ProductName", "product")):
                    for lang in langs:
                        if ver.VerQueryValueW(buf, f"\\StringFileInfo\\{lang}\\{key}", ctypes.byref(p),
                                              ctypes.byref(n)) and n.value > 1:
                            v = ctypes.wstring_at(p.value, n.value).strip("\0 ").strip()
                            if v:
                                info[name] = v
                                break
    except Exception:  # noqa: BLE001
        pass
    _ver_cache[path] = info
    return info


# ----------------------------------------------------------------------------- discos
DRIVE_TYPES = {2: "Removível", 3: "Disco interno", 4: "Rede", 5: "CD/DVD", 6: "Memória"}


def drives():
    out = []
    mask = k32.GetLogicalDrives()
    for i in range(26):
        if not mask & (1 << i):
            continue
        root = f"{chr(65 + i)}:\\"
        t = k32.GetDriveTypeW(root)
        if t not in (2, 3, 4, 6):
            continue
        label = ctypes.create_unicode_buffer(261)
        fs = ctypes.create_unicode_buffer(261)
        k32.GetVolumeInformationW(root, label, 261, None, None, None, fs, 261)
        try:
            import shutil
            u = shutil.disk_usage(root)
        except OSError:
            continue
        out.append({"root": root, "letter": root[:2], "type": DRIVE_TYPES.get(t, "?"), "label": label.value,
                    "fs": fs.value, "total": u.total, "used": u.used, "free": u.free})
    return out


def recycle(paths):
    """Manda arquivos/pastas para a Lixeira (da para restaurar). Devolve os que sumiram."""
    paths = [p for p in paths if p]
    if not paths:
        return []
    s = "\0".join(paths) + "\0\0"
    buf = ctypes.create_unicode_buffer(s, len(s) + 1)
    op = SHFILEOPSTRUCTW()
    op.wFunc = 3  # FO_DELETE
    op.pFrom = ctypes.cast(buf, W.LPCWSTR)
    op.fFlags = 0x0040 | 0x0010 | 0x0004 | 0x0400  # ALLOWUNDO NOCONFIRMATION SILENT NOERRORUI
    sh32.SHFileOperationW(ctypes.byref(op))
    return [p for p in paths if not os.path.exists(p)]


def recycle_bin_info():
    i = SHQUERYRBINFO()
    i.cbSize = ctypes.sizeof(i)
    if sh32.SHQueryRecycleBinW(None, ctypes.byref(i)) != 0:
        return 0, 0
    return i.i64Size, i.i64NumItems


def empty_recycle_bin():
    sh32.SHEmptyRecycleBinW(None, None, 0x7)  # sem confirmacao, sem progresso, sem som


# ----------------------------------------------------------------------------- executar
class Cancelled(RuntimeError):
    pass


def shell_exec(file, params="", verb="open", wait=True, timeout=3600, show=1):
    """ShellExecuteEx: 'runas' pede permissao de administrador (UAC). Devolve o codigo de saida."""
    i = SHELLEXECUTEINFOW()
    i.cbSize = ctypes.sizeof(i)
    i.fMask = 0x40 | 0x100  # NOCLOSEPROCESS | NOASYNC
    i.lpVerb = verb
    i.lpFile = file
    i.lpParameters = params or None
    i.nShow = show
    if not sh32.ShellExecuteExW(ctypes.byref(i)):
        err = ctypes.get_last_error()
        if err == 1223:
            raise Cancelled("Você cancelou a permissão de administrador.")
        raise RuntimeError(f"Não consegui abrir {os.path.basename(file)} (erro {err}).")
    if not i.hProcess:
        return 0
    try:
        if not wait:
            return None
        t0 = time.time()
        while k32.WaitForSingleObject(i.hProcess, 500) == 0x102:  # WAIT_TIMEOUT
            if time.time() - t0 > timeout:
                return None
        code = W.DWORD()
        k32.GetExitCodeProcess(i.hProcess, ctypes.byref(code))
        return code.value
    finally:
        k32.CloseHandle(i.hProcess)


def run(cmd, timeout=60):
    p = subprocess.run(cmd, capture_output=True, timeout=timeout, creationflags=NO_WINDOW)
    out = p.stdout.decode("utf-8", "replace") if p.stdout else ""
    if not out.strip() and p.stdout:
        out = p.stdout.decode("cp850", "replace")
    err = p.stderr.decode("cp850", "replace") if p.stderr else ""
    return p.returncode, out, err


def open_in_explorer(path):
    if os.path.isdir(path):
        subprocess.Popen(["explorer", path], creationflags=NO_WINDOW)
    else:
        subprocess.Popen(["explorer", "/select,", path], creationflags=NO_WINDOW)
