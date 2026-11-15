"""Tema da janela (claro / escuro / ao vivo) e o tempo para o tema "Ao vivo".

Igual ao app do celular:
- a escolha fica guardada em %LOCALAPPDATA%\\CalorScan\\pc_prefs.json (a porta da janela muda a cada vez, entao o
  navegador nao consegue lembrar sozinho);
- o tempo so e consultado no tema "Ao vivo" e so se a pessoa deixar usar a localizacao. A localizacao vem do
  Windows (Configuracoes > Privacidade > Localizacao), e arredondada para 1 casa (~10 km) e vai so para o
  open-meteo.com (gratis, sem cadastro), no maximo a cada 30 min. Sem localizacao: so o relogio.
"""
import json
import os
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

DATA_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".local" / "share")) / "CalorScan"
PREFS = DATA_DIR / "pc_prefs.json"
NO_WINDOW = 0x08000000 if os.name == "nt" else 0
THEMES = ("light", "dark", "live")
EVERY = 30 * 60          # tempo: no maximo a cada 30 min
LOC_EVERY = 6 * 3600     # localizacao: a cada 6 h (um notebook muda pouco de cidade)
_lock = threading.Lock()
_busy = False

# Pergunta ao servico de localizacao do Windows (o mesmo do app Clima). So devolve o que o Windows deixar.
_PS = r"""
Add-Type -AssemblyName System.Device
$w = New-Object System.Device.Location.GeoCoordinateWatcher
$w.Start()
$t = 0
while (($w.Status -ne 'Ready') -and ($w.Permission -ne 'Denied') -and $t -lt 120) { Start-Sleep -Milliseconds 100; $t++ }
$c = $w.Position.Location
$ci = [Globalization.CultureInfo]::InvariantCulture
"$($w.Permission)|$($w.Status)|$($c.Latitude.ToString($ci))|$($c.Longitude.ToString($ci))"
$w.Stop()
"""


def load():
    try:
        p = json.loads(PREFS.read_text(encoding="utf-8"))
        return p if isinstance(p, dict) else {}
    except (OSError, ValueError):
        return {}


def save(p):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = PREFS.with_suffix(".tmp")
    tmp.write_text(json.dumps(p, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, PREFS)


def theme():
    t = load().get("theme")
    return t if t in THEMES else "light"


def set_prefs(body):
    """Muda o tema e/ou a licenca de usar a localizacao. Devolve as preferencias publicas."""
    with _lock:
        p = load()
        if body.get("theme") in THEMES:
            p["theme"] = body["theme"]
        if "use_location" in body:
            p["use_location"] = bool(body["use_location"])
            if not p["use_location"]:   # tirou a licenca: esquece o lugar e o tempo guardados
                p.pop("loc", None)
                p.pop("wx", None)
        save(p)
        return public(p)


def public(p=None):
    p = p or load()
    return {"theme": p.get("theme") if p.get("theme") in THEMES else "light",
            "use_location": p.get("use_location"),   # None = ainda nao perguntou
            "loc_state": p.get("loc_state")}


def _windows_location():
    """(estado, lat, lon) com lat/lon ja arredondados para 1 casa. estado: ok | off | erro."""
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", _PS],
                           capture_output=True, text=True, timeout=25, creationflags=NO_WINDOW)
        perm, status, lat, lon = (r.stdout.strip().splitlines() or ["|||"])[-1].split("|")
        if perm != "Granted" or status != "Ready":
            return "off", None, None
        la, lo = float(lat), float(lon)
        if la != la or lo != lo:   # NaN = sem posicao
            return "off", None, None
        return "ok", round(la, 1), round(lo, 1)
    except (OSError, ValueError, subprocess.SubprocessError):
        return "erro", None, None


def _minutes(iso):
    hh, mm = iso.split("T")[1].split(":")[:2]
    return int(hh) * 60 + int(mm)


def _refresh():
    global _busy
    try:
        p = load()
        loc = p.get("loc") or {}
        if not loc or time.time() - loc.get("at", 0) > LOC_EVERY:
            state, la, lo = _windows_location()
            p = load()
            p["loc_state"] = state
            if state == "ok":
                p["loc"] = loc = {"lat": la, "lon": lo, "at": time.time()}
            save(p)
        if loc.get("lat") is None:
            return
        url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
               "&current=weather_code,cloud_cover,precipitation,wind_speed_10m&daily=sunrise,sunset"
               "&timezone=auto&forecast_days=1") % (loc["lat"], loc["lon"])
        req = urllib.request.Request(url, headers={"User-Agent": "CalorScanPC"})
        with urllib.request.urlopen(req, timeout=10) as r:  # noqa: S310 (endereco fixo, https)
            o = json.loads(r.read().decode("utf-8"))
        cur, daily = o["current"], o["daily"]
        wx = {"code": int(cur.get("weather_code") or 0), "cloud": int(cur.get("cloud_cover") or 0),
              "precip": float(cur.get("precipitation") or 0), "wind": float(cur.get("wind_speed_10m") or 0),
              "rise": _minutes(daily["sunrise"][0]), "set": _minutes(daily["sunset"][0]), "at": time.time()}
        with _lock:
            p = load()
            p["wx"] = wx
            save(p)
    except (OSError, ValueError, KeyError, IndexError):
        pass
    finally:
        _busy = False


def sky():
    """O que a janela precisa para desenhar o ceu Ao vivo. Atualiza o tempo em segundo plano quando vence."""
    global _busy
    p = load()
    if p.get("theme") != "live" or not p.get("use_location"):
        return {"wx": None, **public(p)}
    wx = p.get("wx")
    if (not wx or time.time() - wx.get("at", 0) > EVERY) and not _busy:
        _busy = True
        threading.Thread(target=_refresh, daemon=True).start()
    # tempo com mais de 6 h nao vale mais (so os horarios do sol, que mudam pouco)
    if wx and time.time() - wx.get("at", 0) > 6 * 3600:
        wx = {"code": 0, "cloud": 0, "precip": 0, "wind": 0, "rise": wx["rise"], "set": wx["set"], "old": True}
    return {"wx": wx, **public(p)}
