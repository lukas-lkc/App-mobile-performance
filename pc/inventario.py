"""Inventario do computador e acoes reversiveis.

- Informacoes do sistema (modelo, processador, memoria, placas de video, discos fisicos, Windows).
- Apps instalados (programas classicos + apps da Microsoft Store) e desinstalacao.
- O que abre com o Windows, tarefas agendadas e servicos de outros fabricantes (com desligar/religar).
- Plano de energia e "modo fresco" (limitar o turbo do processador).
Toda acao que muda o sistema e anotada em undo.json para o botao "Desfazer tudo".
"""
import datetime as dt
import json
import os
import re
import subprocess
import threading
import time
import winreg
from pathlib import Path

import ps
import suspeitos as sus
import winapi

DATA = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "CalorScan"
UNDO = DATA / "pc_undo.json"
BALANCED = "381b4222-f694-41f0-9685-ff5bb260df2e"
APPROVED = r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved"


# ----------------------------------------------------------------------------- desfazer
def undo_load():
    try:
        return json.loads(UNDO.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def undo_save(d):
    DATA.mkdir(parents=True, exist_ok=True)
    UNDO.write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")


def undo_remember(key, original):
    """Guarda o valor ORIGINAL so na primeira mudanca (desfazer volta ao estado de antes do CalorScan)."""
    d = undo_load()
    if key not in d:
        d[key] = original
        undo_save(d)


def undo_forget(key):
    d = undo_load()
    if d.pop(key, None) is not None:
        undo_save(d)


# ----------------------------------------------------------------------------- sistema
SYS_SCRIPT = r"""
$o=@{}
try{ $c=Get-CimInstance Win32_ComputerSystem; $o.maker=$c.Manufacturer; $o.model=$c.Model; $o.ram=[long]$c.TotalPhysicalMemory }catch{}
try{ $p=Get-CimInstance Win32_Processor | Select-Object -First 1; $o.cpu=$p.Name.Trim(); $o.cores=$p.NumberOfCores; $o.threads=$p.NumberOfLogicalProcessors; $o.mhz=$p.MaxClockSpeed }catch{}
try{ $o.gpus=@(Get-CimInstance Win32_VideoController | %{ $_.Name }) }catch{}
try{ $s=Get-CimInstance Win32_OperatingSystem; $o.os=$s.Caption; $o.build=$s.BuildNumber; $o.ver=$s.Version; $o.boot=$s.LastBootUpTime.ToString('s'); $o.installed=$s.InstallDate.ToString('s') }catch{}
try{ $o.disks=@(Get-PhysicalDisk | %{ @{name=$_.FriendlyName;media="$($_.MediaType)";bus="$($_.BusType)";health="$($_.HealthStatus)";size=[long]$_.Size} }) }catch{}
try{ $o.ramsticks=@(Get-CimInstance Win32_PhysicalMemory | %{ @{gb=[math]::Round($_.Capacity/1GB);mhz=$_.ConfiguredClockSpeed} }) }catch{}
try{ $o.fastboot=(Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' -EA Stop).HiberbootEnabled }catch{}
try{ $b=Get-CimInstance Win32_Battery | Select-Object -First 1; if($b){ $o.battery=$true } }catch{}
try{ $o.bios=(Get-CimInstance Win32_BIOS).SMBIOSBIOSVersion }catch{}
$o | ConvertTo-Json -Depth 4 -Compress
"""


class Inventory:
    def __init__(self):
        self.lock = threading.Lock()
        self.sys = None
        self.apps = None
        self.apps_t = 0
        self.startup = None
        self.loading = set()

    # ------------------------------------------------------------------ sistema
    def system(self, refresh=False):
        if self.sys is None or refresh:
            try:
                self.sys = ps.run_json(SYS_SCRIPT, timeout=60) or {}
            except Exception as e:  # noqa: BLE001
                self.sys = {"error": str(e)}
            self.sys["power"] = power_plan()
            self.sys["admin"] = winapi.is_admin()
        return self.sys

    # ------------------------------------------------------------------ apps
    def load_apps(self):
        apps = []
        seen = set()
        for hive, base, scope in ((winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall", "todos"),
                                  (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall", "todos"),
                                  (winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Uninstall", "você")):
            try:
                root = winreg.OpenKey(hive, base)
            except OSError:
                continue
            with root:
                i = 0
                while True:
                    try:
                        sub = winreg.EnumKey(root, i)
                    except OSError:
                        break
                    i += 1
                    try:
                        with winreg.OpenKey(root, sub) as k:
                            v = _values(k)
                            last = winreg.QueryInfoKey(k)[2]
                    except OSError:
                        continue
                    name = (v.get("DisplayName") or "").strip()
                    if not name or v.get("SystemComponent") == 1 or v.get("ParentKeyName") or \
                            v.get("ReleaseType") in ("Update", "Hotfix", "Security Update"):
                        continue
                    unin = v.get("UninstallString") or v.get("QuietUninstallString")
                    if not unin:
                        continue
                    dedup = (name.lower(), str(v.get("DisplayVersion", "")))
                    if dedup in seen:
                        continue
                    seen.add(dedup)
                    loc = (v.get("InstallLocation") or "").strip().strip('"')
                    if not loc and v.get("DisplayIcon"):
                        icon = sus.exe_from_cmd(str(v["DisplayIcon"]).split(",")[0])
                        if icon and os.path.isabs(icon):
                            loc = os.path.dirname(icon)
                    date = _parse_date(v.get("InstallDate"))
                    exact = bool(date)
                    date = date or _filetime_date(last)
                    size = int(v.get("EstimatedSize") or 0) * 1024
                    app = {"id": f"reg:{'HKLM' if hive == winreg.HKEY_LOCAL_MACHINE else 'HKCU'}:{base}\\{sub}",
                           "name": name, "publisher": (v.get("Publisher") or "").strip(),
                           "version": str(v.get("DisplayVersion") or ""), "date": date, "size": size,
                           "location": loc, "uninstall": unin, "store": False, "scope": scope, "exact": exact}
                    apps.append(app)
        try:
            store = ps.run_json(r"""
@{items=@(Get-AppxPackage | ?{ -not $_.IsFramework -and -not $_.NonRemovable -and $_.SignatureKind -ne 'System' } | %{
  @{n=$_.Name;full=$_.PackageFullName;pub=$_.Publisher;v="$($_.Version)";loc=$_.InstallLocation;sk="$($_.SignatureKind)";
    d=$(try{ (Get-Item -LiteralPath $_.InstallLocation -EA Stop).CreationTime.ToString('yyyy-MM-dd') }catch{ '' })} })} | ConvertTo-Json -Depth 4 -Compress
""", timeout=90) or {}
            for a in store.get("items") or []:
                pub = re.sub(r"^CN=\"?([^,\"]+).*", r"\1", a.get("pub") or "")
                if re.fullmatch(r"[0-9A-Fa-f-]{30,}", pub):  # fabricante em codigo: usa o prefixo do pacote
                    pub = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", a["n"].split(".")[0])
                apps.append({"id": "appx:" + a["full"], "name": _pretty_appx(a["n"]), "publisher": pub,
                             "version": a.get("v", ""), "date": a.get("d") or None, "size": 0,
                             "location": a.get("loc") or "", "uninstall": None, "store": True, "exact": False,
                             "scope": "você", "store_kind": a.get("sk")})
        except Exception:  # noqa: BLE001
            pass
        today = dt.date.today()
        for a in apps:
            reasons = []
            text = f"{a['name']} {a['publisher']} {a['location']}"
            if sus.PIRACY.search(text):
                reasons.append(("medio", "nome ou pasta indicam programa pirata/crackeado (origem comum de vírus e mineradores)"))
            if sus.PUP.search(text):
                reasons.append(("medio", "programa conhecido como indesejado (propaganda/adware)"))
            if sus.MINER.search(text):
                reasons.append(("alto", "programa de mineração de criptomoeda"))
            if not a["publisher"] and not a["store"]:
                reasons.append(("baixo", "sem fabricante informado"))
            if a["location"]:
                reasons += [r for r in sus.path_reasons(os.path.join(a["location"], "x.exe"), "x.exe")
                            if "aleatório" not in r[1] and "pirata" not in r[1]]
            a["reasons"] = [r for _, r in reasons]
            a["risk"] = sus.level(reasons)
            try:
                a["days"] = (today - dt.date.fromisoformat(a["date"])).days if a["date"] else None
            except ValueError:
                a["days"] = None
        apps.sort(key=lambda a: a["name"].lower())
        with self.lock:
            self.apps = apps
            self.apps_t = time.time()
        return apps

    def get_apps(self, refresh=False):
        if self.apps is None or refresh:
            self.load_apps()
        return self.apps

    def app_of(self, path):
        """App instalado dono de um executavel (pela pasta de instalacao)."""
        if not path or not self.apps:
            return None
        p = path.lower()
        best = None
        for a in self.apps:
            loc = (a.get("location") or "").lower().rstrip("\\")
            if len(loc) > 3 and p.startswith(loc + "\\") and (not best or len(loc) > len(best["location"])):
                if sus.in_windows(loc + "\\"):
                    continue
                best = a
        return best

    def app(self, app_id):
        return next((a for a in (self.apps or []) if a["id"] == app_id), None)

    # ------------------------------------------------------------------ inicializacao
    def load_startup(self):
        items = []
        user_startup = Path(os.environ.get("APPDATA", "")) / r"Microsoft\Windows\Start Menu\Programs\Startup"
        all_startup = Path(os.environ.get("ProgramData", r"C:\ProgramData")) / r"Microsoft\Windows\Start Menu\Programs\StartUp"
        sources = [
            ("HKCU", r"Software\Microsoft\Windows\CurrentVersion\Run", "Run", "você"),
            ("HKLM", r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", "Run", "todos"),
            ("HKLM", r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run", "Run32", "todos"),
        ]
        for hive, key, appr, scope in sources:
            h = winreg.HKEY_CURRENT_USER if hive == "HKCU" else winreg.HKEY_LOCAL_MACHINE
            try:
                with winreg.OpenKey(h, key) as k:
                    for name, cmd in _values(k).items():
                        if not isinstance(cmd, str):
                            continue
                        items.append(self._startup_item(hive, appr, name, cmd, scope, "Registro"))
            except OSError:
                pass
        for folder, hive, scope in ((user_startup, "HKCU", "você"), (all_startup, "HKLM", "todos")):
            try:
                for f in folder.iterdir():
                    if f.name.lower() == "desktop.ini":
                        continue
                    items.append(self._startup_item(hive, "StartupFolder", f.name, str(f), scope, "Pasta Inicializar"))
            except OSError:
                pass
        tasks, services = [], []
        try:
            data = ps.run_json(r"""
$t=@(Get-ScheduledTask -EA SilentlyContinue | %{
  $a=(($_.Actions | %{ "$($_.Execute) $($_.Arguments)".Trim() }) -join ' ; ')
  if($_.TaskPath -notlike '\Microsoft\*' -or $a -match 'appdata|\\temp\\|programdata\\[^\\]+\.exe|-enc|hidden|mshta|wscript|http'){
    @{path=$_.TaskPath;name=$_.TaskName;state="$($_.State)";author="$($_.Author)";exec=$a;
      trig=(($_.Triggers | %{ $_.CimClass.CimClassName -replace '^MSFT_Task','' -replace 'Trigger$','' }) -join ',')} } })
$s=@(Get-CimInstance Win32_Service -EA SilentlyContinue | ?{ $_.PathName -and $_.PathName -notmatch '(?i)\\windows\\(system32|syswow64)\\(svchost|lsass|services)\.exe' } | %{
  @{name=$_.Name;disp=$_.DisplayName;state="$($_.State)";mode="$($_.StartMode)";path=$_.PathName;pid=[int]$_.ProcessId;desc="$($_.Description)"} })
@{tasks=$t;services=$s} | ConvertTo-Json -Depth 4 -Compress
""", timeout=120) or {}
            for t in data.get("tasks") or []:
                exe = sus.exe_from_cmd(t.get("exec", ""))
                reasons = sus.path_reasons(exe) + sus.cmd_reasons(t.get("exec", ""))
                if t["path"].lower().startswith("\\microsoft\\"):
                    # tarefa da Microsoft: so interessa se o .exe esta fora do Windows ou o comando e grave
                    reasons = [r for r in reasons if r[0] == "alto" or not sus.in_windows(exe)]
                    if not reasons:
                        continue
                tasks.append({**t, "id": t["path"] + t["name"], "enabled": t.get("state") != "Disabled",
                              "reasons": [r for _, r in reasons], "risk": sus.level(reasons),
                              "company": winapi.file_info(exe).get("company", "") if os.path.isfile(exe) else ""})
            for s in data.get("services") or []:
                exe = sus.exe_from_cmd(s.get("path", ""))
                if sus.in_windows(exe):
                    continue
                info = winapi.file_info(exe) if os.path.isfile(exe) else {}
                if "microsoft" in info.get("company", "").lower():
                    continue
                reasons = sus.path_reasons(exe) + sus.cmd_reasons(s.get("path", ""))
                services.append({**s, "id": s["name"], "exe": exe, "company": info.get("company", ""),
                                 "enabled": s.get("mode") != "Disabled",
                                 "reasons": [r for _, r in reasons], "risk": sus.level(reasons)})
        except Exception:  # noqa: BLE001
            pass
        tasks.sort(key=lambda t: (not t["risk"], t["name"].lower()))
        services.sort(key=lambda s: (not s["risk"], s.get("state") != "Running", s["disp"].lower()))
        items.sort(key=lambda i: (not i["risk"], not i["enabled"], i["name"].lower()))
        self.startup = {"run": items, "tasks": tasks, "services": services, "t": time.time()}
        return self.startup

    def _startup_item(self, hive, appr, name, cmd, scope, where):
        enabled = True
        h = winreg.HKEY_CURRENT_USER if hive == "HKCU" else winreg.HKEY_LOCAL_MACHINE
        try:
            with winreg.OpenKey(h, APPROVED + "\\" + appr) as k:
                val, _ = winreg.QueryValueEx(k, name)
                enabled = not (isinstance(val, bytes) and val and val[0] & 1)
        except OSError:
            pass
        exe = cmd if appr == "StartupFolder" else sus.exe_from_cmd(cmd)
        info = winapi.file_info(exe) if exe and os.path.isfile(exe) else {}
        reasons = sus.path_reasons(exe) + ([] if appr == "StartupFolder" else sus.cmd_reasons(cmd))
        return {"id": f"{hive}|{appr}|{name}", "name": name, "cmd": cmd, "exe": exe, "scope": scope, "where": where,
                "enabled": enabled, "desc": info.get("desc") or info.get("product") or "",
                "company": info.get("company", ""), "reasons": [r for _, r in reasons], "risk": sus.level(reasons),
                "admin": hive == "HKLM"}

    def get_startup(self, refresh=False):
        if self.startup is None or refresh:
            self.load_startup()
        return self.startup


INV = Inventory()


# ----------------------------------------------------------------------------- acoes
def set_startup(item_id, enable):
    hive, appr, name = item_id.split("|", 2)
    key = APPROVED + "\\" + appr
    h = winreg.HKEY_CURRENT_USER if hive == "HKCU" else winreg.HKEY_LOCAL_MACHINE
    original = None
    try:
        with winreg.OpenKey(h, key) as k:
            original = winreg.QueryValueEx(k, name)[0].hex()
    except OSError:
        pass
    if enable:
        data = bytes([2]) + bytes(11)
    else:
        ft = int((time.time() + 11644473600) * 1e7)
        data = bytes([3, 0, 0, 0]) + ft.to_bytes(8, "little")
    undo_remember("startup|" + item_id, original)
    try:
        with winreg.CreateKeyEx(h, key, 0, winreg.KEY_SET_VALUE) as k:
            winreg.SetValueEx(k, name, 0, winreg.REG_BINARY, data)
    except PermissionError:
        hexs = ",".join(str(b) for b in data)
        ok, out = ps.run_admin(f"New-Item -Path {ps.q('HKLM:' + chr(92) + key)} -Force | Out-Null; "
                               f"Set-ItemProperty -Path {ps.q('HKLM:' + chr(92) + key)} -Name {ps.q(name)} "
                               f"-Type Binary -Value ([byte[]]@({hexs})) -ErrorAction Stop", timeout=60)
        if not ok:
            raise RuntimeError("Não consegui mudar: " + out[:200])
    return f"{name}: {'vai abrir' if enable else 'não vai mais abrir'} com o Windows"


def restore_startup(item_id, original_hex):
    hive, appr, name = item_id.split("|", 2)
    key = APPROVED + "\\" + appr
    h = winreg.HKEY_CURRENT_USER if hive == "HKCU" else winreg.HKEY_LOCAL_MACHINE
    try:
        with winreg.CreateKeyEx(h, key, 0, winreg.KEY_SET_VALUE) as k:
            if original_hex is None:
                try:
                    winreg.DeleteValue(k, name)
                except OSError:
                    pass
            else:
                winreg.SetValueEx(k, name, 0, winreg.REG_BINARY, bytes.fromhex(original_hex))
    except PermissionError:
        path = ps.q("HKLM:" + chr(92) + key)
        if original_hex is None:
            script = f"Remove-ItemProperty -Path {path} -Name {ps.q(name)} -EA SilentlyContinue"
        else:
            vals = ",".join(str(b) for b in bytes.fromhex(original_hex))
            script = f"Set-ItemProperty -Path {path} -Name {ps.q(name)} -Type Binary -Value ([byte[]]@({vals}))"
        ps.run_admin(script)


def set_task(task_id, enable):
    t = next((t for t in (INV.startup or {}).get("tasks", []) if t["id"] == task_id), None)
    if not t:
        raise RuntimeError("Tarefa não encontrada. Atualize a lista.")
    undo_remember("task|" + task_id, {"path": t["path"], "name": t["name"], "enabled": t["enabled"]})
    _task(t["path"], t["name"], enable)
    return f"Tarefa {t['name']}: {'ligada' if enable else 'desligada'}"


def _task(path, name, enable):
    cmd = f"{'Enable' if enable else 'Disable'}-ScheduledTask -TaskPath {ps.q(path)} -TaskName {ps.q(name)} -ErrorAction Stop | Out-Null"
    ok, out = ps.run(cmd, timeout=60)
    if not ok:
        ok, out = ps.run_admin(cmd, timeout=120)
        if not ok:
            raise RuntimeError("Não consegui mudar a tarefa: " + out[:200])


MODE_PS = {"Auto": "Automatic", "Manual": "Manual", "Disabled": "Disabled", "Boot": "Automatic", "System": "Automatic"}


def set_service(name, enable):
    s = next((s for s in (INV.startup or {}).get("services", []) if s["name"] == name), None)
    if not s:
        raise RuntimeError("Serviço não encontrado. Atualize a lista.")
    undo_remember("service|" + name, {"mode": s["mode"], "running": s["state"] == "Running"})
    if enable:
        orig = undo_load().get("service|" + name) or {"mode": "Manual"}
        mode = MODE_PS.get(orig.get("mode"), "Manual")
        if mode == "Disabled":
            mode = "Manual"
        script = f"Set-Service -Name {ps.q(name)} -StartupType {mode} -ErrorAction Stop"
        if orig.get("running"):
            script += f"; Start-Service -Name {ps.q(name)} -ErrorAction SilentlyContinue"
    else:
        script = (f"Set-Service -Name {ps.q(name)} -StartupType Disabled -ErrorAction Stop; "
                  f"Stop-Service -Name {ps.q(name)} -Force -ErrorAction SilentlyContinue")
    ok, out = ps.run_admin(script, timeout=120)
    if not ok:
        raise RuntimeError("Não consegui mudar o serviço: " + out[:200])
    return f"Serviço {s['disp']}: {'religado' if enable else 'desligado'}"


# ----------------------------------------------------------------------------- energia
def power_plan():
    code, out, _ = winapi.run(["powercfg", "/getactivescheme"], timeout=15)
    m = re.search(r"([0-9a-f]{8}-[0-9a-f-]{27})\s*\((.+)\)", out, re.I)
    plan = {"guid": m.group(1), "name": m.group(2).strip()} if m else {}
    ac, dc = _throttle()
    plan["max_ac"], plan["max_dc"] = ac, dc
    plan["cool"] = ac is not None and ac < 100
    return plan


def _throttle():
    code, out, _ = winapi.run(["powercfg", "/query", "SCHEME_CURRENT", "SUB_PROCESSOR", "PROCTHROTTLEMAX"], timeout=15)
    vals = re.findall(r":\s*0x([0-9a-f]{8})", out, re.I)
    if len(vals) >= 2:
        return int(vals[-2], 16), int(vals[-1], 16)
    return None, None


def set_balanced(on):
    cur = power_plan()
    if on:
        undo_remember("power|plan", cur.get("guid"))
        target = BALANCED
    else:
        target = undo_load().get("power|plan") or BALANCED
        undo_forget("power|plan")
    code, out, err = winapi.run(["powercfg", "/setactive", target], timeout=20)
    if code != 0:
        raise RuntimeError("O Windows não aceitou trocar o plano de energia: " + (err or out)[:160])
    return "Plano de energia: " + power_plan().get("name", "?")


def set_cool(on):
    """Modo fresco: processador no maximo a 99% = sem turbo. Esquenta bem menos, perde ~10-25% de pico."""
    ac, dc = _throttle()
    if on:
        undo_remember("power|throttle", [ac, dc])
        vals = (99, 99)
    else:
        orig = undo_load().get("power|throttle") or [100, 100]
        vals = (orig[0] if orig[0] is not None else 100, orig[1] if orig[1] is not None else 100)
        if vals[0] < 100 and vals[1] < 100:
            vals = (100, 100)
        undo_forget("power|throttle")
    cmds = [["powercfg", "/setacvalueindex", "SCHEME_CURRENT", "SUB_PROCESSOR", "PROCTHROTTLEMAX", str(vals[0])],
            ["powercfg", "/setdcvalueindex", "SCHEME_CURRENT", "SUB_PROCESSOR", "PROCTHROTTLEMAX", str(vals[1])],
            ["powercfg", "/setactive", "SCHEME_CURRENT"]]
    for c in cmds:
        code, out, err = winapi.run(c, timeout=20)
        if code != 0:
            ok, o = ps.run_admin("; ".join(" ".join(x) for x in cmds))
            if not ok:
                raise RuntimeError("Não consegui mudar: " + o[:160])
            break
    return "Modo fresco ligado (sem turbo)" if on else "Modo fresco desligado (turbo liberado)"


def undo_all():
    d = undo_load()
    done = []
    for key, orig in list(d.items()):
        kind, _, ident = key.partition("|")
        try:
            if kind == "startup":
                restore_startup(ident, orig)
            elif kind == "task":
                _task(orig["path"], orig["name"], orig["enabled"])
            elif kind == "service":
                mode = MODE_PS.get(orig.get("mode"), "Manual")
                script = f"Set-Service -Name {ps.q(ident)} -StartupType {mode} -ErrorAction Stop"
                if orig.get("running"):
                    script += f"; Start-Service -Name {ps.q(ident)} -ErrorAction SilentlyContinue"
                ps.run_admin(script)
            elif key == "power|plan":
                winapi.run(["powercfg", "/setactive", orig or BALANCED])
            elif key == "power|throttle":
                ac, dc = orig
                winapi.run(["powercfg", "/setacvalueindex", "SCHEME_CURRENT", "SUB_PROCESSOR", "PROCTHROTTLEMAX", str(ac or 100)])
                winapi.run(["powercfg", "/setdcvalueindex", "SCHEME_CURRENT", "SUB_PROCESSOR", "PROCTHROTTLEMAX", str(dc or 100)])
                winapi.run(["powercfg", "/setactive", "SCHEME_CURRENT"])
            done.append(key)
        except Exception:  # noqa: BLE001
            continue
    d = undo_load()
    for k in done:
        d.pop(k, None)
    undo_save(d)
    return f"{len(done)} ajuste(s) desfeito(s)" + (f", {len(d)} não deu" if d else "")


# ----------------------------------------------------------------------------- desinstalar
def uninstall(app_id, say):
    a = INV.app(app_id)
    if not a:
        raise RuntimeError("App não encontrado. Atualize a lista.")
    if a["store"]:
        say(f"Removendo {a['name']}…")
        ok, out = ps.run(f"Remove-AppxPackage -Package {ps.q(app_id[5:])} -ErrorAction Stop", timeout=300)
        if not ok:
            raise RuntimeError("O Windows não deixou remover: " + out[:200])
    else:
        cmd = a["uninstall"].strip()
        m = re.match(r'(?i)^"?msiexec(\.exe)?"?\s+/[ix]\s*(\{[^}]+\})', cmd)
        if m:
            exe, params = "msiexec.exe", f"/x {m.group(2)}"
        elif cmd.startswith('"'):
            exe, _, params = cmd[1:].partition('"')
        else:
            mm = re.match(r"(?i)(.+?\.exe)\s*(.*)", cmd)
            exe, params = (mm.group(1), mm.group(2)) if mm else (cmd, "")
        say(f"Abrindo o desinstalador de {a['name']}… siga a janela dele.")
        winapi.shell_exec(os.path.expandvars(exe), params.strip(), wait=True, timeout=3600)
        time.sleep(2)
    INV.load_apps()
    still = any(x["name"] == a["name"] for x in INV.apps or [])
    return f"{a['name']} {'ainda aparece na lista (talvez você tenha cancelado)' if still else 'foi desinstalado ✓'}"


# ----------------------------------------------------------------------------- processos
def kill(pids, name):
    fails = []
    for pid in pids:
        code, out, err = winapi.run(["taskkill", "/PID", str(pid), "/T", "/F"], timeout=20)
        if code not in (0, 128):
            fails.append(pid)
    if fails:
        ok, out = ps.run_admin("; ".join(f"Stop-Process -Id {p} -Force -ErrorAction SilentlyContinue" for p in fails))
        if not ok:
            raise RuntimeError(f"Não consegui encerrar {name}: o Windows protege esse processo.")
    return f"{name} encerrado ✓"


# ----------------------------------------------------------------------------- util
def _values(k):
    out = {}
    i = 0
    while True:
        try:
            n, v, _ = winreg.EnumValue(k, i)
        except OSError:
            break
        out[n] = v
        i += 1
    return out


def _parse_date(s):
    s = str(s or "").strip()
    if re.fullmatch(r"\d{8}", s):
        try:
            return dt.date(int(s[:4]), int(s[4:6]), int(s[6:])).isoformat()
        except ValueError:
            return None
    return None


def _filetime_date(ft):
    try:
        return (dt.datetime(1601, 1, 1) + dt.timedelta(microseconds=ft // 10)).date().isoformat()
    except (OverflowError, ValueError):
        return None


def _pretty_appx(n):
    last = n.split(".")[-1] if "." in n else n
    last = re.sub(r"V\d+$", "", last)
    return re.sub(r"(?<=[a-z])(?=[A-Z])", " ", last) or n
