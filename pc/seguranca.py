"""Verificacao de seguranca: antivirus, firewall, ameacas, assinaturas dos programas em uso,
arquivo hosts, programas piratas e rastros de sequestro de arquivos (ransomware)."""
import json
import os
import re
import threading
import time
from pathlib import Path

import ps
import suspeitos as sus
import winapi
from virustotal import VT, Limited

SEC_SCRIPT = r"""
$o=@{}
try{ $s=Get-MpComputerStatus -EA Stop; $o.def=@{mode="$($s.AMRunningMode)";av=$s.AntivirusEnabled;rt=$s.RealTimeProtectionEnabled;
     sigAge=$s.AntivirusSignatureAge;quickAge=$s.QuickScanAge;fullAge=$s.FullScanAge;tamper=$s.IsTamperProtected;
     quickEnd=$(if($s.QuickScanEndTime){$s.QuickScanEndTime.ToString('s')}else{''})} }catch{}
try{ $p=Get-MpPreference -EA Stop; $o.excl=@(@($p.ExclusionPath)+@($p.ExclusionProcess)+@($p.ExclusionExtension) | ?{ $_ }) }catch{}
try{ $n=@{}; Get-MpThreat -EA Stop | %{ $n["$($_.ThreatID)"]=@{name=$_.ThreatName;sev=[int]$_.SeverityID} }
     $o.threats=@(Get-MpThreatDetection -EA Stop | Sort-Object InitialDetectionTime -Descending | Select-Object -First 25 | %{
       @{id="$($_.ThreatID)";when=$_.InitialDetectionTime.ToString('s');res=@($_.Resources | Select-Object -First 3);ok=$_.ActionSuccess;
         name=$n["$($_.ThreatID)"].name;sev=$n["$($_.ThreatID)"].sev} }) }catch{}
try{ $o.fw=@(Get-NetFirewallProfile -EA Stop | %{ @{n="$($_.Name)";on=[bool]$_.Enabled} }) }catch{}
try{ $o.av=@(Get-CimInstance -Namespace root/SecurityCenter2 AntiVirusProduct -EA Stop | %{ @{n=$_.displayName;state=[int]$_.productState} }) }catch{}
try{ $o.uac=(Get-ItemProperty HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System -EA Stop).EnableLUA }catch{}
$paths = '%PATHS%' | ConvertFrom-Json
$o.sig=@{}
foreach($x in $paths){ try{ $s=Get-AuthenticodeSignature -LiteralPath $x -EA Stop
  $by=''; if($s.SignerCertificate){ $by=($s.SignerCertificate.Subject -replace '^CN=("?)([^,"]+).*','$2') }
  $o.sig[$x]=@{st="$($s.Status)";by=$by} }catch{} }
$o | ConvertTo-Json -Depth 5 -Compress
"""


class Security:
    def __init__(self):
        self.result = None
        self.running = False
        self.defender_job = None   # texto da verificacao do Defender em andamento
        self.quick_result = None   # verificacao rapida de ameacas (lista com botao Remover)
        self.quick_running = False
        self.lock = threading.Lock()

    def scan(self, mon, inv, say):
        """Roda tudo (10-60 s). mon/inv: monitor e inventario para cruzar os dados."""
        self.running = True
        try:
            say("Verificando antivírus, firewall e assinaturas dos programas…")
            # apps da Microsoft Store sao assinados pelo pacote (o arquivo em si aparece "sem assinatura")
            paths = sorted({g["path"] for g in mon.groups if g.get("path") and not sus.in_windows(g["path"])
                            and "\\windowsapps\\" not in g["path"].lower()})
            st = inv.startup or {}
            for i in st.get("run", []):
                if i.get("exe") and os.path.isfile(i["exe"]) and i["exe"].lower().endswith(".exe"):
                    paths.append(i["exe"])
            paths = sorted(set(paths))[:250]
            data = ps.run_json(SEC_SCRIPT.replace("%PATHS%", json.dumps(paths).replace("'", "''")), timeout=240) or {}
            sigs = data.get("sig") or {}
            mon.signatures.update(sigs)
            say("Procurando programas piratas e rastros de vírus nas suas pastas…")
            files = find_risky_files()
            hosts = check_hosts()
            excl = data.get("excl")
            excl_admin = bool(excl) and any("must be an administrator" in str(e).lower() for e in excl)
            self.result = {
                "t": time.time(), "defender": data.get("def") or {}, "av": data.get("av") or [],
                "firewall": data.get("fw") or [], "uac": data.get("uac"),
                "exclusions": [] if excl_admin else (excl or []), "exclusions_need_admin": excl_admin,
                "threats": data.get("threats") or [],
                "unsigned": sorted([{"path": p, "st": s.get("st")} for p, s in sigs.items()
                                    if s.get("st") not in ("Valid",)], key=lambda x: x["path"]),
                "files": files, "hosts": hosts, "checked": len(paths),
            }
            say("Verificação de segurança concluída ✓")
        finally:
            self.running = False
        return self.result

    def quick_scan(self, mon, inv, say):
        """Verificacao rapida (10-60 s): lista o que e suspeito com botao Remover."""
        return quick_scan_impl(self, mon, inv, say)


def find_risky_files(limit=300):
    """Procura nomes de piratas/cracks e bilhetes de resgate (_readme.txt do STOP/Djvu)."""
    home = Path(os.environ.get("USERPROFILE", Path.home()))
    roots = [(home / d, 4) for d in ("Desktop", "Downloads", "Documents", "Área de Trabalho", "OneDrive")]
    roots += [(home / "AppData" / "Local" / "Temp", 2)]
    sysdrive = os.environ.get("SystemDrive", "C:").upper()
    for i in range(26):
        d = f"{chr(65 + i)}:"
        if d != sysdrive and os.path.exists(d + "\\"):
            roots.append((Path(d + "\\"), 3))
    roots.append((Path(sysdrive + "\\"), 1))
    found, ransom = [], []
    seen = set()
    newest_note = 0
    for root, maxd in roots:
        if not root.exists():
            continue
        stack = [(str(root), 0)]
        while stack and len(found) < limit:
            d, depth = stack.pop()
            try:
                it = os.scandir(d)
            except OSError:
                continue
            with it:
                for e in it:
                    try:
                        low = e.name.lower()
                        is_dir = e.is_dir(follow_symlinks=False)
                        if is_dir and low in ("windows", "program files", "program files (x86)", "$recycle.bin",
                                              "system volume information", "node_modules", ".git", "appdata"):
                            continue
                        if sus.RANSOM.search(low) and e.path not in seen:
                            seen.add(e.path)
                            ransom.append(e.path)
                            if low == "_readme.txt":
                                newest_note = max(newest_note, int(e.stat(follow_symlinks=False).st_mtime))
                        elif sus.PIRACY.search(low) or sus.MINER.search(low):
                            if e.path not in seen:
                                seen.add(e.path)
                                st = e.stat(follow_symlinks=False)
                                found.append({"path": e.path, "dir": is_dir, "size": 0 if is_dir else st.st_size,
                                              "mtime": int(st.st_mtime),
                                              "why": "mineração" if sus.MINER.search(low) else "pirata/crack"})
                            continue
                        if is_dir and depth + 1 <= maxd and not e.is_symlink():
                            stack.append((e.path, depth + 1))
                    except OSError:
                        continue
    return {"piracy": found, "ransom_notes": ransom[:50], "ransom_count": len(ransom), "ransom_newest": newest_note}


def check_hosts():
    p = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "drivers" / "etc" / "hosts"
    try:
        lines = [l.replace("﻿", "").strip() for l in p.read_text(encoding="utf-8-sig", errors="replace").splitlines()]
    except OSError:
        return {"entries": 0, "bad": []}
    entries = [l for l in lines if l and not l.startswith("#")]
    bad = [l for l in entries if re.search(r"microsoft|windowsupdate|defender|avast|kaspersky|norton|eset|"
                                           r"malwarebytes|virustotal|google|bank|caixa|itau|bradesco|nubank|santander", l, re.I)]
    return {"entries": len(entries), "bad": bad[:20]}


def defender_scan(kind, say):
    """kind: quick | full | update | offline."""
    if kind == "update":
        say("Atualizando a proteção do Windows…")
        ok, out = ps.run("Update-MpSignature -ErrorAction Stop", timeout=600)
        if not ok:
            ok, out = ps.run_admin("Update-MpSignature -ErrorAction Stop", timeout=600)
        return "Proteção atualizada ✓" if ok else "Não deu para atualizar: " + out[:160]
    if kind == "offline":
        ok, out = ps.run_admin("Start-MpWDOScan -ErrorAction Stop", timeout=120)
        return "O PC vai reiniciar para a verificação offline." if ok else "Não deu: " + out[:160]
    t = "QuickScan" if kind == "quick" else "FullScan"
    say(f"Verificação {'rápida' if kind == 'quick' else 'completa'} do Windows Defender rodando… (pode usar o PC)")
    ok, out = ps.run(f"Start-MpScan -ScanType {t} -ErrorAction Stop", timeout=6 * 3600)
    if not ok:
        ok, out = ps.run_admin(f"Start-MpScan -ScanType {t} -ErrorAction Stop", timeout=6 * 3600)
    return "Verificação concluída ✓ Veja as ameaças encontradas abaixo." if ok else "A verificação não terminou: " + out[:160]


# ----------------------------------------------------------------------------- verificacao rapida (estilo antivirus)
DATA = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "CalorScan"
IGNORE = DATA / "sec_ignore.json"
KIND_LABEL = {"proc": "Rodando agora", "startup": "Abre com o Windows", "task": "Tarefa agendada",
              "service": "Serviço", "app": "App instalado", "file": "Arquivo"}

DEF_SCAN = r"""
$paths = '%PATHS%' | ConvertFrom-Json
$t0 = Get-Date
foreach($p in $paths){ try{ Start-MpScan -ScanType CustomScan -ScanPath $p -EA Stop }catch{} }
$hits=@(Get-MpThreatDetection -EA SilentlyContinue | ?{ $_.InitialDetectionTime -ge $t0.AddSeconds(-5) } | %{
  $n=(Get-MpThreat -ThreatID $_.ThreatID -EA SilentlyContinue).ThreatName
  @{name=$n;res=@($_.Resources)} })
@{hits=$hits} | ConvertTo-Json -Depth 4 -Compress
"""


def ignore_load():
    try:
        return json.loads(IGNORE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def ignore(key, on=True):
    if not key:
        raise RuntimeError("Item desconhecido.")
    lst = [k for k in ignore_load() if k != key] + ([key] if on else [])
    DATA.mkdir(parents=True, exist_ok=True)
    IGNORE.write_text(json.dumps(lst, ensure_ascii=False, indent=1), encoding="utf-8")
    res = SEC.quick_result or {}
    for t in res.get("items", []):
        if t["key"] == key:
            t["ignored"] = on
    return "Não vou mais avisar sobre esse item." if on else "Voltei a avisar sobre esse item."


def _key(exe, fallback):
    return (exe or fallback or "").lower()


def quick_scan_impl(sec, mon, inv, say):
    sec.quick_running = True
    try:
        say("Procurando ameaças: programas abertos, inicialização, tarefas, serviços e apps…")
        ign = set(ignore_load())
        items, seen = [], set()

        def add(kind, ident, name, exe, reasons, risk, **extra):
            if risk not in ("alto", "medio"):
                return
            k = _key(exe, ident)
            sid = f"{kind}:{ident}"
            if sid in seen:
                return
            seen.add(sid)
            items.append({"id": sid, "key": k, "kind": kind, "kind_label": KIND_LABEL[kind], "name": name,
                          "exe": exe or "", "reasons": list(reasons), "risk": risk, "ignored": k in ign, **extra})

        for g in mon.groups:
            if g.get("critical"):
                continue
            add("proc", g["key"], g["name"], g.get("path"), g.get("reasons") or [], g.get("risk"), pids=g["pids"])
        st = inv.startup or {}
        for i in st.get("run", []):
            add("startup", i["id"], i.get("desc") or i["name"], i.get("exe"), i["reasons"], i["risk"],
                ref=i["id"], enabled=i["enabled"])
        for t in st.get("tasks", []):
            add("task", t["id"], t["name"], sus.exe_from_cmd(t.get("exec", "")), t["reasons"], t["risk"],
                ref=t["id"], enabled=t["enabled"])
        for s in st.get("services", []):
            add("service", s["id"], s["disp"], s.get("exe"), s["reasons"], s["risk"], ref=s["id"], enabled=s["enabled"])
        for a in inv.apps or []:
            add("app", a["id"], a["name"], a.get("location"), a["reasons"], a["risk"], ref=a["id"], publisher=a["publisher"])
        for f in ((sec.result or {}).get("files") or {}).get("piracy", []):
            add("file", f["path"], os.path.basename(f["path"]), f["path"], [f"nome de {f['why']}"],
                "alto" if f["why"] == "mineração" else "medio", dir=f["dir"])

        # segunda opiniao: o proprio antivirus do Windows olha os arquivos .exe suspeitos
        exes = sorted({t["exe"] for t in items if t["exe"].lower().endswith(".exe") and os.path.isfile(t["exe"])
                       and not t["ignored"]})[:30]
        defender_ok = None
        if exes:
            say(f"Pedindo ao antivírus do Windows para conferir {len(exes)} arquivo(s)…")
            try:
                data = ps.run_json(DEF_SCAN.replace("%PATHS%", json.dumps(exes).replace("'", "''")), timeout=900) or {}
                defender_ok = True
                for h in data.get("hits") or []:
                    for r in h.get("res") or []:
                        low = str(r).lower()
                        for t in items:
                            if t["exe"] and t["exe"].lower() in low:
                                t["defender"] = h.get("name") or "ameaça"
                                t["risk"] = "alto"
            except Exception:  # noqa: BLE001
                defender_ok = False
        for t in items:
            # arquivo que da para conferir: o proprio .exe, ou o programa principal dentro da pasta do app
            t["file"] = t["exe"] if t["exe"] and os.path.isfile(t["exe"]) else \
                main_exe(t["exe"]) if t["kind"] in ("app", "file") and t["exe"] and os.path.isdir(t["exe"]) else ""
            apply_vt(t, VT.by_path(t["file"]) if t["file"] else None)  # resultado ja guardado, sem consultar
        rk = {"alto": 0, "medio": 1}
        items.sort(key=lambda t: (t["ignored"], "defender" not in t, rk.get(t["risk"], 2), t["kind"] != "proc", t["name"].lower()))
        sec.quick_result = {"t": time.time(), "items": items, "checked_exes": len(exes), "defender_ok": defender_ok,
                            "counts": {"procs": len(mon.groups), "startup": len(st.get("run", [])),
                                       "tasks": len(st.get("tasks", [])), "services": len(st.get("services", [])),
                                       "apps": len(inv.apps or [])}}
        n = sum(1 for t in items if not t["ignored"])
        return f"Verificação rápida concluída: {n} item(ns) suspeito(s)" if n else "Verificação rápida concluída: nada suspeito ✓"
    finally:
        sec.quick_running = False


def main_exe(folder):
    """O .exe principal de uma pasta: o maior, ignorando desinstaladores e ajudantes (olha 2 niveis)."""
    skip = re.compile(r"unins|uninst|setup|install|update|crash|helper|redist|vc_?redist|dxsetup|unitycrash", re.I)
    best, size = "", 0
    try:
        for root, dirs, files in os.walk(folder):
            if root[len(folder):].count(os.sep) >= 2:
                dirs[:] = []
            for f in files:
                if f.lower().endswith(".exe") and not skip.search(f):
                    try:
                        s = os.path.getsize(os.path.join(root, f))
                    except OSError:
                        continue
                    if s > size and s <= 400 * 1024 ** 2:
                        best, size = os.path.join(root, f), s
    except OSError:
        pass
    return best


def apply_vt(t, res):
    if not res:
        return
    t["vt"] = {k: res.get(k) for k in ("verdict", "text", "malicious", "suspicious", "total", "label", "link", "t")}
    if res.get("verdict") == "virus":
        t["risk"] = "alto"


def vt_check(item_id, force=False):
    """Confere um item no VirusTotal (so a impressao digital sai do PC)."""
    t = next((x for x in (SEC.quick_result or {}).get("items", []) if x["id"] == item_id), None)
    if not t or not t.get("file") or not os.path.isfile(t["file"]):
        raise RuntimeError("Esse item não tem um arquivo para conferir.")
    res = VT.check(t["file"], force)
    apply_vt(t, res)
    return t["vt"]


def vt_check_all(say):
    """Confere todos os itens com arquivo, respeitando 4 por minuto da conta gratuita."""
    todo = [t for t in (SEC.quick_result or {}).get("items", [])
            if not t["ignored"] and t.get("file") and os.path.isfile(t["file"]) and "vt" not in t]
    if not todo:
        return "Nada novo para conferir no VirusTotal."
    VT.batch = {"done": 0, "total": len(todo)}
    bad = 0
    try:
        for i, t in enumerate(todo, 1):
            say(f"VirusTotal: conferindo {i} de {len(todo)} ({t['name']})… (4 por minuto na conta gratuita)")
            try:
                apply_vt(t, VT.check(t["file"]))
                bad += t["vt"]["verdict"] in ("virus", "suspeito")
            except Limited as e:
                return "⚠️ " + str(e)
            VT.batch["done"] = i
    finally:
        VT.batch = None
    return f"VirusTotal: {len(todo)} conferido(s), {bad} com alerta" if bad else f"VirusTotal: {len(todo)} conferido(s), nenhum vírus ✓"


def remove_threat(item_id, mon, inv, say):
    """Remove uma ameaca: encerra, tira da inicializacao, desinstala ou manda o arquivo para a Lixeira.

    Tudo o que desliga entra no "Desfazer tudo"; arquivo vai para a Lixeira (da para restaurar).
    """
    import inventario as invmod  # evita import circular

    res = SEC.quick_result or {}
    t = next((x for x in res.get("items", []) if x["id"] == item_id), None)
    if not t:
        raise RuntimeError("Item não encontrado. Rode a verificação de novo.")
    exe = sus.norm(t["exe"])
    done, fails = [], []

    def step(label, fn, *a):
        try:
            fn(*a)
            done.append(label)
        except Exception as e:  # noqa: BLE001
            fails.append(f"{label}: {e}")

    say(f"Removendo {t['name']}…")
    # 1) encerra o que estiver rodando desse arquivo
    for g in list(mon.groups):
        if g.get("critical"):
            continue
        if (t["kind"] == "proc" and g["key"] == t["id"][5:]) or (exe and sus.norm(g.get("path")) == exe):
            step(f"encerrei {g['name']}", invmod.kill, g["pids"], g["name"])
    # 2) desliga inicializacao / tarefas / servicos que apontam para ele
    st = inv.startup or {}
    for i in st.get("run", []):
        if i["enabled"] and ((t["kind"] == "startup" and i["id"] == t.get("ref")) or (exe and sus.norm(i.get("exe")) == exe)):
            step("tirei da inicialização", invmod.set_startup, i["id"], False)
    for k in st.get("tasks", []):
        if k["enabled"] and ((t["kind"] == "task" and k["id"] == t.get("ref")) or
                             (exe and sus.norm(sus.exe_from_cmd(k.get("exec", ""))) == exe)):
            step(f"desliguei a tarefa {k['name']}", invmod.set_task, k["id"], False)
    for s in st.get("services", []):
        if s["enabled"] and ((t["kind"] == "service" and s["id"] == t.get("ref")) or (exe and sus.norm(s.get("exe")) == exe)):
            step(f"desliguei o serviço {s['disp']}", invmod.set_service, s["id"], False)
    # 3) apaga de vez: desinstala o app dono, ou manda o arquivo solto para a Lixeira
    app = inv.app(t["ref"]) if t["kind"] == "app" else (inv.app_of(t["exe"]) if t["exe"] else None)
    if app:
        step(f"abri o desinstalador de {app['name']}", invmod.uninstall, app["id"], say)
    elif t["exe"] and os.path.exists(t["exe"]) and not sus.in_windows(t["exe"]) and \
            (not sus.in_program_files(t["exe"]) or t["kind"] == "file"):
        gone = winapi.recycle([t["exe"]])
        (done if gone else fails).append("mandei o arquivo para a Lixeira" if gone else "o arquivo está em uso ou protegido")
    elif t["exe"] and sus.in_program_files(t["exe"]):
        done.append("o arquivo fica em Arquivos de Programas: só foi desligado (desinstale pela aba Apps se quiser)")
    res["items"] = [x for x in res.get("items", []) if x["id"] != item_id or fails]
    if fails and not done:
        raise RuntimeError("Não deu para remover: " + "; ".join(fails)[:200])
    msg = f"{t['name']}: " + ", ".join(done) + " ✓"
    return msg + (" (falhou: " + "; ".join(fails)[:120] + ")" if fails else "")


# ----------------------------------------------------------------------------- Windows Defender ligado/desligado
def defender_status():
    try:
        d = ps.run_json(r"""$s=Get-MpComputerStatus -EA Stop
@{rt=$s.RealTimeProtectionEnabled;av=$s.AntivirusEnabled;tamper=$s.IsTamperProtected;mode="$($s.AMRunningMode)";
  sigAge=$s.AntivirusSignatureAge} | ConvertTo-Json -Compress""", timeout=60) or {}
        return {"ok": True, **d}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "err": str(e)[:200]}


def set_realtime(on, say):
    """Liga/desliga a protecao em tempo real. A "Protecao contra adulteracao" do Windows bloqueia
    qualquer programa de desligar (inclusive o CalorScan): nesse caso abrimos a tela certa do Windows."""
    say(("Ligando" if on else "Desligando") + " a proteção em tempo real… (o Windows vai pedir permissão)")
    ok, out = ps.run_admin(f"Set-MpPreference -DisableRealtimeMonitoring ${'false' if on else 'true'} -ErrorAction Stop",
                           timeout=120)
    time.sleep(2)
    st = defender_status()
    if st.get("ok") and bool(st.get("rt")) == on:
        return "Proteção em tempo real ligada ✓" if on else \
            "Proteção em tempo real desligada. O Windows religa sozinha depois de um tempo ou ao reiniciar."
    if st.get("tamper"):
        open_defender("threat")
        return ("O Windows não deixou: a \"Proteção contra adulteração\" está ligada (isso é bom, protege contra vírus). "
                "Abri a tela do antivírus: mude a chave \"Proteção em tempo real\" por lá.")
    if not ok:
        raise RuntimeError("Não deu: " + out[:160])
    open_defender("threat")
    return "O Windows não mudou a proteção. Abri a tela do antivírus para você mudar por lá."


def open_defender(page="threat"):
    url = {"threat": "windowsdefender://threatsettings", "home": "windowsdefender://threat",
           "exclusions": "windowsdefender://threatsettings"}.get(page, "windowsdefender://threat")
    os.startfile(url)


SEC = Security()
