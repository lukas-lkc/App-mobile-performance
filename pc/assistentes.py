"""Assistentes de drivers dos fabricantes (Intel, NVIDIA, AMD, Acer, Lenovo, ASUS, Dell, HP).

Os sites dos fabricantes NAO deixam ser abertos dentro de outro programa (cabecalho frame-ancestors),
entao cada assistente abre:
- o programa do fabricante, se estiver instalado (pelo menu Iniciar: shell:AppsFolder\\<AppID>), ou
- o site oficial numa janela propria do Edge (--app: sem abas e sem barra de endereco, como o CalorScan).
So mostra o que combina com este PC (processador, placas de video e marca). Lista de enderecos fixa.
"""
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

import ps

ASSIST = [
    {"id": "intel", "name": "Intel Driver & Support Assistant", "hw": r"intel",
     "app": r"^Intel Driver & Support Assistant$", "prefer_web": True,
     "web": "https://www.intel.com.br/content/www/br/pt/support/intel-driver-support-assistant.html",
     "what": "Procura drivers novos de vídeo Intel, Wi-Fi, Bluetooth e chipset. Mostra a lista e instala com um clique.",
     "note": "Precisa do programa da Intel instalado (o site conversa com ele)."},
    {"id": "nvidia", "name": "NVIDIA App", "hw": r"nvidia|geforce|rtx|gtx",
     "app": r"^NVIDIA app$", "alt_app": r"^GeForce Experience$",
     "web": "https://www.nvidia.com/pt-br/software/nvidia-app/",
     "what": "Atualiza o driver da placa de vídeo NVIDIA (o mais importante para jogos e calor).",
     "old_note": "Você tem o GeForce Experience, que a NVIDIA aposentou. O NVIDIA App substitui ele."},
    {"id": "amd", "name": "AMD Software (Adrenalin)", "hw": r"\bamd\b|radeon|ryzen",
     "app": r"^AMD Software|^Radeon", "web": "https://www.amd.com/pt/support/download/drivers.html",
     "what": "Atualiza o driver de vídeo e do chipset AMD."},
    {"id": "acer", "name": "Acer Care Center", "maker": r"acer",
     "app": r"^Care Center$|^Acer Care Center", "web": "https://www.acer.com/br-pt/support/drivers-and-manuals",
     "what": "Drivers e BIOS feitos para o seu modelo de notebook Acer, mais limpeza e verificação do sistema."},
    {"id": "nitrosense", "name": "NitroSense", "maker": r"acer", "app": r"^NitroSense$|^PredatorSense$", "only_installed": True,
     "what": "Temperatura do processador e da placa de vídeo, e o modo das ventoinhas (use o máximo para jogar)."},
    {"id": "lenovo", "name": "Lenovo Vantage", "maker": r"lenovo", "app": r"^Lenovo Vantage$",
     "web": "https://pcsupport.lenovo.com/br/pt/", "what": "Drivers e BIOS do seu modelo Lenovo."},
    {"id": "asus", "name": "MyASUS", "maker": r"asus", "app": r"^MyASUS$",
     "web": "https://www.asus.com/br/support/download-center/", "what": "Drivers e BIOS do seu modelo ASUS."},
    {"id": "dell", "name": "Dell SupportAssist", "maker": r"dell", "app": r"SupportAssist",
     "web": "https://www.dell.com/support/home/pt-br", "what": "Drivers e BIOS do seu modelo Dell."},
    {"id": "hp", "name": "HP Support Assistant", "maker": r"\bhp\b|hewlett", "app": r"^HP Support Assistant$",
     "web": "https://support.hp.com/br-pt/drivers", "what": "Drivers e BIOS do seu modelo HP."},
]

_apps = {"t": 0, "list": []}


def start_apps(refresh=False):
    """Apps do menu Iniciar (nome + AppID). Guardado por 5 min."""
    if refresh or time.time() - _apps["t"] > 300:
        try:
            d = ps.run_json("@{a=@(Get-StartApps | %{ @{n=$_.Name;id=$_.AppID} })} | ConvertTo-Json -Depth 3 -Compress",
                            timeout=60) or {}
            _apps["list"] = d.get("a") or []
            _apps["t"] = time.time()
        except Exception:  # noqa: BLE001
            pass
    return _apps["list"]


def _find(rx):
    if not rx:
        return None
    return next((a for a in start_apps() if re.search(rx, a.get("n", ""), re.I)), None)


def listing(sysinfo, refresh=False):
    if refresh:
        start_apps(True)
    hw = " ".join([sysinfo.get("cpu", "")] + list(sysinfo.get("gpus") or []))
    maker = sysinfo.get("maker", "")
    out = []
    for a in ASSIST:
        if a.get("hw") and not re.search(a["hw"], hw, re.I):
            continue
        if a.get("maker") and not re.search(a["maker"], maker, re.I):
            continue
        app = _find(a.get("app"))
        old = None if app else _find(a.get("alt_app"))
        if a.get("only_installed") and not app:
            continue
        out.append({"id": a["id"], "name": a["name"], "what": a["what"], "note": a.get("note", ""),
                    "installed": bool(app), "app_name": (app or {}).get("n", ""),
                    "old": (old or {}).get("n", ""), "old_note": a.get("old_note", "") if old else "",
                    "web": bool(a.get("web")), "prefer_web": bool(a.get("prefer_web"))})
    return out


def open_assistant(aid, mode="auto"):
    """mode: auto (programa se tiver, senao site) | app | old | web."""
    a = next((x for x in ASSIST if x["id"] == aid), None)
    if not a:
        raise RuntimeError("Assistente desconhecido.")
    if mode in ("auto", "app") and not (mode == "auto" and a.get("prefer_web")):
        app = _find(a.get("app"))
        if app:
            launch(app["id"])
            return f"Abrindo {app['n']}…"
        if mode == "app":
            raise RuntimeError(f"{a['name']} não está instalado.")
    if mode == "old":
        app = _find(a.get("alt_app"))
        if not app:
            raise RuntimeError("Programa não encontrado.")
        launch(app["id"])
        return f"Abrindo {app['n']}…"
    if not a.get("web"):
        raise RuntimeError("Esse assistente só funciona com o programa instalado.")
    app_window(a["web"])
    return f"Abrindo {a['name']} numa janela própria…"


def launch(app_id):
    """Abre qualquer app do menu Iniciar (Store ou comum) pelo AppID."""
    if not re.fullmatch(r"[\w.\-!{}\\: ()&]+", app_id or ""):
        raise RuntimeError("Programa com nome estranho; não abri.")
    subprocess.Popen(["explorer.exe", "shell:AppsFolder\\" + app_id])


def app_window(url):
    """Site oficial numa janela do Edge sem abas/barra (parece parte do CalorScan)."""
    allowed = {a["web"] for a in ASSIST if a.get("web")}
    if url not in allowed:
        raise RuntimeError("Endereço fora da lista.")
    edge = next((p for p in (r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                             r"C:\Program Files\Microsoft\Edge\Application\msedge.exe") if Path(p).exists()),
                None) or shutil.which("msedge")
    if edge:
        subprocess.Popen([edge, f"--app={url}", "--window-size=1200,860"])
    else:
        os.startfile(url)
