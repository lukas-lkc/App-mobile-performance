"""Backup do Windows: ponto de restauracao (rapido) e imagem completa do sistema (demorada).

- Ponto de restauracao: "foto" dos arquivos do sistema, drivers e registro. Volta o Windows como estava
  (fotos e documentos NAO sao mexidos; apps instalados depois somem). Ideal antes de instalar drivers.
  Liga a Protecao do Sistema no C: se estiver desligada (reserva ate 8 GB) e passa por cima do limite
  "1 ponto por dia" do Windows so durante a criacao (depois volta como estava).
- Imagem do sistema (wbadmin): copia inteira do C: para outro disco. Restaurar so pelo "modo de recuperacao".
- Restaurar SEMPRE pelo assistente oficial do Windows (rstrui.exe), depois de varios avisos na tela.

Quase tudo aqui pede administrador; a lista de pontos fica guardada em backup_state.json para a tela
mostrar sem pedir permissao toda hora.
"""
import json
import os
import re
import string
import time
from pathlib import Path

import ps
import winapi

DATA = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "CalorScan"
STATE = DATA / "backup_state.json"
SYS = os.environ.get("SystemDrive", "C:").upper()

LIST_PS = r"""
$o=@{}
try{ $v=(Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SPP\Clients' -EA Stop).'{09F7EDC5-294E-4180-AF6A-FB0E6A0E9513}'
     $o.on = [bool](@($v) -match [regex]::Escape(($env:SystemDrive -replace ':','%3A'))) }catch{ $o.on=$false }
$o.points=@(Get-ComputerRestorePoint -EA SilentlyContinue | %{ @{n=[int]$_.SequenceNumber;desc="$($_.Description)";
   when=([Management.ManagementDateTimeConverter]::ToDateTime($_.CreationTime)).ToString('s');type=[int]$_.RestorePointType} })
try{ $s=vssadmin list shadowstorage /for=$env:SystemDrive 2>$null | Out-String
     # 3 linhas "X GB (y%)": usado, alocado e maximo (o texto muda com o idioma; o formato nao)
     $m=[regex]::Matches($s, ':\s*([0-9][^\r\n(]*\(\d+%\))'); if($m.Count -ge 3){ $o.max=$m[2].Groups[1].Value.Trim() } }catch{}
"JSON:" + ($o | ConvertTo-Json -Depth 4 -Compress)
"""

CREATE_PS = r"""
$k='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore'
$old=(Get-ItemProperty $k -EA SilentlyContinue).SystemRestorePointCreationFrequency
Enable-ComputerRestore -Drive "$env:SystemDrive\" -EA Stop
$s=vssadmin list shadowstorage /for=$env:SystemDrive 2>$null | Out-String
if($s -notmatch '\d+%\)'){ vssadmin resize shadowstorage /for=$env:SystemDrive /on=$env:SystemDrive /maxsize=%MAX% | Out-Null }
Set-ItemProperty $k -Name SystemRestorePointCreationFrequency -Value 0 -Type DWord
try{ Checkpoint-Computer -Description %DESC% -RestorePointType MODIFY_SETTINGS -EA Stop }
finally{ if($old -eq $null){ Remove-ItemProperty $k -Name SystemRestorePointCreationFrequency -EA SilentlyContinue }
         else{ Set-ItemProperty $k -Name SystemRestorePointCreationFrequency -Value $old -Type DWord } }
""" + LIST_PS


def load_state():
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save(d):
    DATA.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")


def _parse(out):
    m = re.search(r"JSON:(\{.*\})", out or "", re.S)
    if not m:
        return None
    d = json.loads(m.group(1))
    pts = d.get("points") or []
    if isinstance(pts, dict):
        pts = [pts]
    d["points"] = sorted(pts, key=lambda p: p.get("when", ""), reverse=True)
    return d


def refresh(say=None):
    """Le a protecao e os pontos (pede administrador)."""
    if say:
        say("Lendo os pontos de restauração… (o Windows vai pedir permissão)")
    ok, out = ps.run_admin(LIST_PS, timeout=180)
    d = _parse(out)
    if not ok or d is None:
        raise RuntimeError("Não deu para ler os pontos: " + (out or "")[:160])
    st = load_state()
    st.update(on=d.get("on"), points=d["points"], max=d.get("max"), checked=time.time())
    _save(st)
    return f"{len(d['points'])} ponto(s) de restauração encontrado(s)" + ("" if d.get("on") else " · proteção do sistema desligada")


def create_point(desc, say):
    desc = re.sub(r"[^\w\sÀ-ú.,()+-]", "", desc or "").strip()[:60] or "manual"
    say("Criando o ponto de restauração… (1 a 3 minutos; o Windows vai pedir permissão)")
    free = winapi_free(SYS)
    maxsize = "8GB" if free > 30 * 1024 ** 3 else "5GB"
    script = CREATE_PS.replace("%DESC%", ps.q("CalorScan: " + desc)).replace("%MAX%", maxsize)
    t0 = time.time()
    ok, out = ps.run_admin(script, timeout=1800)
    d = _parse(out)
    if not ok and d is None:
        raise RuntimeError("Não deu para criar o ponto: " + (out or "")[:200])
    st = load_state()
    if d:
        st.update(on=d.get("on"), points=d["points"], max=d.get("max"), checked=time.time())
    newest = (st.get("points") or [{}])[0]
    try:
        made = newest.get("when") and time.mktime(time.strptime(newest["when"][:19], "%Y-%m-%dT%H:%M:%S")) >= t0 - 120
    except ValueError:
        made = False
    st["last_created"] = time.time() if made else st.get("last_created")
    _save(st)
    if not made:
        raise RuntimeError("O Windows não confirmou o ponto novo. Abra 'Criar ponto de restauração' no Windows para conferir.")
    return f"Ponto de restauração criado ✓ ({newest.get('desc', '')})"


# ----------------------------------------------------------------------------- imagem do sistema
def image_targets():
    """Discos onde da para guardar a imagem (nunca o proprio C:)."""
    need = used_bytes(SYS)
    out = []
    for d in winapi.drives():
        letter = d.get("letter", "").upper().rstrip("\\")
        if not letter or letter == SYS or d.get("fs", "").upper() != "NTFS":
            continue
        out.append({"letter": letter, "label": d.get("label", ""), "type": d.get("type", ""), "free": d.get("free", 0),
                    "fits": d.get("free", 0) > need * 1.15, "external": "remov" in d.get("type", "").lower() or
                    "extern" in d.get("type", "").lower() or "usb" in d.get("type", "").lower()})
    return {"targets": out, "need": need}


def images():
    """Imagens ja feitas (pasta WindowsImageBackup na raiz dos discos)."""
    out = []
    for c in string.ascii_uppercase:
        p = Path(f"{c}:\\WindowsImageBackup")
        try:
            if p.is_dir():
                out.append({"path": str(p), "letter": f"{c}:", "when": int(p.stat().st_mtime)})
        except OSError:
            pass
    return out


def create_image(target, say):
    target = (target or "").upper().rstrip("\\")
    if not re.fullmatch(r"[A-Z]:", target) or target == SYS:
        raise RuntimeError("Escolha outro disco (a imagem não pode ficar no próprio C:).")
    t = next((x for x in image_targets()["targets"] if x["letter"] == target), None)
    if not t:
        raise RuntimeError("Disco não encontrado ou não é NTFS.")
    if not t["fits"]:
        raise RuntimeError("Não cabe: libere espaço nesse disco ou use um HD externo.")
    say(f"Criando a imagem do sistema em {target}… pode levar de 30 min a 2 h. Pode usar o PC, mas ele fica mais lento.")
    ok, out = ps.run_admin(f"wbadmin start backup -backupTarget:{target} -include:{SYS} -allCritical -quiet", timeout=6 * 3600)
    low = (out or "").lower()
    if not ok or ("success" not in low and "êxito" not in low and "sucesso" not in low and "conclu" not in low):
        raise RuntimeError("A imagem não terminou: " + (out or "").strip().splitlines()[-1][:200] if out else "sem resposta")
    st = load_state()
    st["last_image"] = {"letter": target, "when": time.time()}
    _save(st)
    return f"Imagem do sistema criada em {target} ✓"


def state():
    st = load_state()
    return {**st, "images": images(), **image_targets(), "admin": winapi.is_admin()}


# ----------------------------------------------------------------------------- abrir telas do Windows
def open_restore_wizard():
    winapi.shell_exec(os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "rstrui.exe"), wait=False)


def open_recovery_settings():
    os.startfile("ms-settings:recovery")


# ----------------------------------------------------------------------------- util
def winapi_free(letter):
    d = next((x for x in winapi.drives() if x.get("letter", "").upper().rstrip("\\") == letter), None)
    return (d or {}).get("free", 0)


def used_bytes(letter):
    d = next((x for x in winapi.drives() if x.get("letter", "").upper().rstrip("\\") == letter), None)
    return (d or {}).get("used", 0)
