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


SEC = Security()
