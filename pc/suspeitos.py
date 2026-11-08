"""Regras simples (e explicadas) para marcar processos, apps e itens de inicializacao suspeitos.

Nada aqui e "certeza de virus": cada regra devolve um motivo em portugues claro para a pessoa
decidir (e para a IA avaliar no relatorio).
"""
import os
import re

SYSROOT = os.environ.get("SystemRoot", r"C:\Windows").lower().rstrip("\\") + "\\"
PFILES = [p.lower().rstrip("\\") + "\\" for p in {
    os.environ.get("ProgramFiles", r"C:\Program Files"),
    os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
    os.environ.get("ProgramW6432", r"C:\Program Files")} if p]
PDATA = os.environ.get("ProgramData", r"C:\ProgramData").lower().rstrip("\\") + "\\"
PROFILE = os.environ.get("USERPROFILE", "").lower().rstrip("\\") + "\\"

MINER = re.compile(r"xmrig|xmr-stak|nicehash|nanominer|phoenixminer|t-rex|lolminer|ethminer|cgminer|bfgminer|"
                   r"nbminer|gminer|teamredminer|cpuminer|minerd|srbminer|claymore|excavator|minergate|"
                   r"cryptonight|(^|[\\/_.\- ])miner([\\/_.\- ]|\.exe|$)", re.I)
PIRACY = re.compile(r"crack|keygen|kmspico|kms[-_ ]?auto|kms[-_ ]?tools|activator|ativador|\brepack|rsload|"
                    r"fitgirl|dodi|elamigos|skidrow|pirat|warez|\bpatcher\b|igg-?games|steamunlocked|"
                    r"\bcodex\b|\bempress\b|\bloader\.exe", re.I)
RANSOM = re.compile(r"stop_?djvu|_readme\.txt$|decrypt_?stop", re.I)
SYSNAMES = {"svchost.exe", "csrss.exe", "lsass.exe", "winlogon.exe", "services.exe", "smss.exe", "wininit.exe",
            "explorer.exe", "dwm.exe", "taskhostw.exe", "conhost.exe", "rundll32.exe", "dllhost.exe",
            "spoolsv.exe", "sihost.exe", "ctfmon.exe", "searchindexer.exe", "runtimebroker.exe",
            "wmiprvse.exe", "audiodg.exe", "fontdrvhost.exe", "lsaiso.exe", "taskmgr.exe", "regsvr32.exe",
            "msiexec.exe", "wuauclt.exe", "mshta.exe"}
LOOKALIKE = re.compile(r"^(svhost|scvhost|svchosts|svch0st|svchots|svcshost|svchost32|csrsss|scrss|crss|csrs|"
                       r"lsasss|lssas|lsas|isass|winlogin|winlogon32|wininit32|explorer32|exp1orer|explore|"
                       r"iexplore32|taskhost32|taskmgr32|rundl32|rundll|services32|smss32|dwm32|conhost32|"
                       r"wuauclt32|windowsupdate|windows update|system32|win32|winservice|ctfmon32)\.exe$", re.I)
BAD_CMD = [
    (re.compile(r"-e(nc|ncodedcommand)?\s+[A-Za-z0-9+/=]{20,}", re.I), "alto", "comando do PowerShell escondido (codificado)"),
    (re.compile(r"-w(indowstyle)?\s+hid", re.I), "medio", "abre uma janela escondida"),
    (re.compile(r"\bmshta(\.exe)?\b", re.I), "alto", "usa o mshta (truque comum de vírus)"),
    (re.compile(r"\b(wscript|cscript)(\.exe)?\b.*\.(js|jse|vbs|vbe)\b", re.I), "medio", "roda um script .js/.vbs"),
    (re.compile(r"\bregsvr32\b.*(/i:|scrobj)", re.I), "alto", "usa o regsvr32 de forma suspeita"),
    (re.compile(r"\bcertutil\b.*-urlcache", re.I), "alto", "baixa arquivos da internet com o certutil"),
    (re.compile(r"\bbitsadmin\b.*/transfer", re.I), "medio", "baixa arquivos com o bitsadmin"),
    (re.compile(r"https?://", re.I), "medio", "acessa um endereço da internet ao iniciar"),
]
# Programas conhecidos como indesejados (adware/PUP)
PUP = re.compile(r"onelaunch|wave browser|webdiscover|segurazo|santivirus|pc app store|search protect|conduit|"
                 r"babylon|ask toolbar|mywebsearch|shift browser|reimage|bytefence|pc accelerate|"
                 r"driver support|webcompanion|mindspark|clara browser|kiwi search|safe finder", re.I)

RANK = {"alto": 3, "medio": 2, "baixo": 1}


def norm(p):
    return (p or "").replace("/", "\\").lower()


def in_windows(p):
    return norm(p).startswith(SYSROOT)


def in_program_files(p):
    p = norm(p)
    return any(p.startswith(x) for x in PFILES)


def path_reasons(path, name=None):
    """Lista de (nivel, motivo) para um executavel."""
    r = []
    p = norm(path)
    n = (name or os.path.basename(p)).lower()
    if MINER.search(n) or MINER.search(p):
        r.append(("alto", "nome de programa de mineração de criptomoeda (usa o processador ao máximo)"))
    if LOOKALIKE.match(n):
        r.append(("alto", f"nome imitando um programa do Windows ({n})"))
    if p:
        if n in SYSNAMES and not in_windows(p) and "\\windowsapps\\" not in p:
            r.append(("alto", f"tem o nome de um programa do Windows ({n}) mas está fora da pasta do Windows"))
        if PIRACY.search(p):
            r.append(("medio", "veio de um programa pirata/crackeado (origem comum de vírus e mineradores)"))
        if "\\appdata\\local\\temp\\" in p or p.startswith(SYSROOT + "temp\\"):
            r.append(("medio", "roda de dentro de uma pasta temporária"))
        if "\\users\\public\\" in p or "\\$recycle.bin\\" in p:
            r.append(("alto", "roda de uma pasta pública ou da Lixeira"))
        if p.startswith(PDATA) and p.count("\\") <= 3 and "\\microsoft\\" not in p and "\\package cache\\" not in p:
            r.append(("medio", "roda direto da pasta ProgramData (lugar comum de vírus)"))
        if "\\appdata\\roaming\\" in p:
            rest = p.split("\\appdata\\roaming\\", 1)[1]
            if "\\" not in rest or rest.startswith("microsoft\\") and "\\teams\\" not in rest:
                r.append(("medio", "roda solto dentro do AppData\\Roaming (lugar comum de vírus)"))
        base = os.path.splitext(n)[0]
        if re.fullmatch(r"(?=.*\d)[a-f0-9]{10,}|[a-z]\d{0,2}|(?=(.*\d){4})[a-z0-9]{12,}", base) and not in_windows(p):
            r.append(("baixo", "nome de arquivo aleatório"))
    return r


def cmd_reasons(cmd):
    r = []
    for rx, lvl, why in BAD_CMD:
        if rx.search(cmd or ""):
            r.append((lvl, why))
    return r


def level(reasons):
    """Nivel final: dois motivos medios viram alto."""
    if not reasons:
        return None
    best = max(RANK[lv] for lv, _ in reasons)
    if best < 3 and sum(1 for lv, _ in reasons if lv == "medio") >= 2:
        best = 3
    return {3: "alto", 2: "medio", 1: "baixo"}[best]


def exe_from_cmd(cmd):
    """Extrai o .exe de uma linha de comando ('"C:\\x\\a.exe" -b' -> 'C:\\x\\a.exe')."""
    c = os.path.expandvars((cmd or "").strip())
    if c.startswith('"'):
        return c[1:].split('"', 1)[0]
    m = re.match(r"(.+?\.(exe|bat|cmd|com|vbs|js|ps1|lnk|scr))\b", c, re.I)
    return m.group(1) if m else c.split(" ")[0]


def hide_user(text):
    """Troca a pasta do usuario por %USERPROFILE% (privacidade no relatorio)."""
    prof = os.environ.get("USERPROFILE")
    if not text or not prof:
        return text
    return re.sub(re.escape(prof), "%USERPROFILE%", str(text), flags=re.I)
