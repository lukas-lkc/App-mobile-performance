"""Drivers: lista os drivers instalados (com idade), procura atualizacoes no Windows Update e instala.

- Instalados: Win32_PnPSignedDriver (so os de outros fabricantes; os da Microsoft vem com o Windows).
- "Antigo": driver com mais de 2 anos. Placa de video com mais de 1 ano (jogos dependem muito dele).
- Atualizacoes: a mesma busca do Windows Update (Atualizacoes opcionais). Instalar pede administrador.
- Driver que o Windows Update nao oferece: botao abre o site do fabricante (NVIDIA, Intel, AMD, notebook).
"""
import datetime as dt
import json
import re
import threading
import time
import webbrowser

import ps

LIST_SCRIPT = r"""
$o=@(Get-CimInstance Win32_PnPSignedDriver -EA SilentlyContinue | ?{ $_.DeviceName -and $_.DriverVersion -and
     $_.DriverProviderName -and $_.DriverProviderName -notmatch '^(Microsoft|\(Standard)' } | %{
  @{dev=$_.DeviceName;cls="$($_.DeviceClass)";prov=$_.DriverProviderName;ver=$_.DriverVersion;inf=$_.InfName;
    date=$(if($_.DriverDate){$_.DriverDate.ToString('yyyy-MM-dd')}else{''});maker="$($_.Manufacturer)"} })
@{items=$o} | ConvertTo-Json -Depth 4 -Compress
"""

WU_SEARCH = r"""
$s=New-Object -ComObject Microsoft.Update.Session; $q=$s.CreateUpdateSearcher(); $q.Online=$true
$r=$q.Search("IsInstalled=0 and Type='Driver' and IsHidden=0")
$u=@($r.Updates | %{ @{id=$_.Identity.UpdateID;title=$_.Title;model="$($_.DriverModel)";prov="$($_.DriverProvider)";
   cls="$($_.DriverClass)";date=$(try{$_.DriverVerDate.ToString('yyyy-MM-dd')}catch{''});size=[long]$_.MaxDownloadSize} })
@{items=$u} | ConvertTo-Json -Depth 4 -Compress
"""

WU_INSTALL = r"""
$ids = '%IDS%' | ConvertFrom-Json
$s=New-Object -ComObject Microsoft.Update.Session; $q=$s.CreateUpdateSearcher(); $q.Online=$true
$r=$q.Search("IsInstalled=0 and Type='Driver' and IsHidden=0")
$c=New-Object -ComObject Microsoft.Update.UpdateColl
foreach($u in $r.Updates){ if($ids -contains $u.Identity.UpdateID){ if(-not $u.EulaAccepted){ $u.AcceptEula() }; [void]$c.Add($u) } }
if($c.Count -eq 0){ 'NADA'; return }
$d=$s.CreateUpdateDownloader(); $d.Updates=$c; [void]$d.Download()
$i=$s.CreateUpdateInstaller(); $i.Updates=$c; $res=$i.Install()
$ok=0; for($k=0;$k -lt $c.Count;$k++){ if($res.GetUpdateResult($k).ResultCode -eq 2){ $ok++ } }
"RES $ok $($c.Count) $($res.RebootRequired)"
"""

# classes que importam para quem usa (o resto vira "outros")
CLASSES = {"DISPLAY": "Placa de vídeo", "MEDIA": "Som", "NET": "Rede e Wi-Fi", "BLUETOOTH": "Bluetooth",
           "SYSTEM": "Placa-mãe e chipset", "HDC": "Discos", "SCSIADAPTER": "Discos", "USB": "USB",
           "HIDCLASS": "Teclado, mouse e touchpad", "MOUSE": "Teclado, mouse e touchpad",
           "KEYBOARD": "Teclado, mouse e touchpad", "CAMERA": "Câmera", "IMAGE": "Câmera",
           "BIOMETRIC": "Leitor de digital", "FIRMWARE": "BIOS / firmware", "EXTENSION": "Extensões de drivers",
           "SOFTWARECOMPONENT": "Componentes de drivers", "PRINTER": "Impressora", "MONITOR": "Monitor"}

# so estes viram "antigo" (driver velho aqui da problema de verdade: calor, travadas, som/Wi-Fi caindo)
IMPORTANT = {"MEDIA", "NET", "BLUETOOTH", "CAMERA", "IMAGE", "BIOMETRIC", "HDC", "SCSIADAPTER"}

VIRTUAL = re.compile(r"virtual|streaming|tunnel|wireguard|\btap\b|vpn|hyper-v|vmware|virtualbox|broadcast", re.I)

SITES = [
    (re.compile(r"nvidia", re.I), "NVIDIA", "https://www.nvidia.com/pt-br/drivers/"),
    (re.compile(r"\bamd\b|advanced micro|radeon", re.I), "AMD", "https://www.amd.com/pt/support/download/drivers.html"),
    (re.compile(r"intel", re.I), "Intel", "https://www.intel.com.br/content/www/br/pt/support/detect.html"),
]
MAKER_SITES = {"acer": "https://www.acer.com/br-pt/support/drivers-and-manuals",
               "asus": "https://www.asus.com/br/support/download-center/",
               "lenovo": "https://pcsupport.lenovo.com/br/pt/",
               "dell": "https://www.dell.com/support/home/pt-br",
               "hp": "https://support.hp.com/br-pt/drivers",
               "samsung": "https://www.samsung.com/br/support/",
               "msi": "https://br.msi.com/support/download",
               "gigabyte": "https://www.gigabyte.com/br/Support",
               "positivo": "https://www.meupositivo.com.br/panel/drivers"}


def nvidia_version(v):
    """'27.21.14.5167' -> '451.67' (versao que aparece no site da NVIDIA)."""
    digits = "".join(v.split(".")[-2:])
    if len(digits) < 5 or not digits.isdigit():
        return None
    d = digits[-5:]
    return f"{int(d[:3])}.{d[3:]}"


class Drivers:
    def __init__(self):
        self.items = None          # drivers instalados
        self.updates = None        # atualizacoes oferecidas pelo Windows Update
        self.t_list = 0
        self.t_wu = 0
        self.searching = False
        self.wu_error = ""
        self.lock = threading.Lock()

    def load(self, maker=""):
        self._maker = maker or self._maker
        maker = self._maker
        data = ps.run_json(LIST_SCRIPT, timeout=120) or {}
        today = dt.date.today()
        seen, out = set(), []
        for d in data.get("items") or []:
            key = (d.get("prov", ""), d.get("ver", ""), d.get("dev", ""))
            if key in seen:
                continue
            seen.add(key)
            cls = (d.get("cls") or "").upper()
            try:
                day = dt.date.fromisoformat(d["date"]) if d.get("date") else None
            except ValueError:
                day = None
            # a Intel usa a data falsa 1968-07-18 nos drivers de chipset (so registram o nome): sem idade
            age = (today - day).days if day and day.year >= 2000 else None
            if age is None:
                d["date"] = None
            limit = 365 if cls == "DISPLAY" else 3 * 365 if cls in IMPORTANT else None
            if VIRTUAL.search(f"{d.get('dev', '')} {d.get('prov', '')}"):
                limit = None  # aparelho de mentira (Steam, VPN, NVIDIA Broadcast…): idade nao importa
            text = f"{d.get('prov', '')} {d.get('dev', '')} {d.get('maker', '')}"
            site = next(((n, u) for rx, n, u in SITES if rx.search(text)), None)
            if not site:
                m = next((k for k in MAKER_SITES if k in (maker or "").lower()), None)
                site = (f"fabricante do {maker.split()[0].title()}" if maker else "fabricante", MAKER_SITES[m]) if m else None
            pretty = d.get("ver", "")
            if cls == "DISPLAY" and re.search(r"nvidia", text, re.I):
                nv = nvidia_version(pretty)
                if nv:
                    pretty = f"{nv} ({d['ver']})"
            out.append({"id": d.get("inf") or "|".join(key), "dev": d.get("dev", ""), "cls": cls,
                        "group": CLASSES.get(cls, "Outros"), "prov": d.get("prov", ""), "ver": pretty,
                        "date": d.get("date") or None, "age": age,
                        "old": bool(limit and age is not None and age > limit),
                        "site": site[1] if site else None, "site_name": site[0] if site else None})
        # placa de video e mais antigos primeiro
        out.sort(key=lambda x: (x["cls"] != "DISPLAY", not x["old"], -(x["age"] or 0)))
        with self.lock:
            self.items = out
            self.t_list = time.time()
        return out

    def search(self, say):
        """Busca no Windows Update (30 s a 3 min). Funciona sem administrador."""
        self.searching = True
        self.wu_error = ""
        try:
            say("Procurando drivers novos no Windows Update… (pode levar alguns minutos)")
            try:
                data = ps.run_json(WU_SEARCH, timeout=600) or {}
                ups = data.get("items") or []
            except Exception as e:  # noqa: BLE001
                self.wu_error = str(e)[:200]
                ups = []
            if self.items is None:
                self.load_safe()
            for u in ups:
                self._classify(u)
            ups.sort(key=lambda u: (u["kind"] != "novo", u.get("model", "").lower()))
            with self.lock:
                self.updates = ups
                self.t_wu = time.time()
            if self.wu_error:
                return "Não deu para falar com o Windows Update: " + self.wu_error
            return f"{len(ups)} driver(s) novo(s) encontrado(s) no Windows Update" if ups else \
                "O Windows Update não tem drivers novos para este PC ✓"
        finally:
            self.searching = False

    def _classify(self, u):
        """kind: novo | velho (mais antigo que o instalado) | firmware (BIOS: so na tomada) | sem_data."""
        if (u.get("date") or "").startswith("1970") or (u.get("date") or "") < "2000":
            u["date"] = None
        model = (u.get("model") or "").lower()
        inst = next((i for i in self.items or [] if i["dev"] and
                     (i["dev"].lower() in model or model in i["dev"].lower())), None)
        u["installed_ver"] = inst["ver"] if inst else None
        u["installed_date"] = inst["date"] if inst else None
        if (u.get("cls") or "").lower() == "firmware":
            u["kind"] = "firmware"
        elif not u["date"]:
            u["kind"] = "sem_data"
        elif inst and inst["date"] and inst["date"] >= u["date"]:
            u["kind"] = "velho"
        else:
            u["kind"] = "novo"

    def install(self, ids, say):
        ids = [i for i in ids if re.fullmatch(r"[0-9a-fA-F-]{36}", i or "")]
        if not ids:
            raise RuntimeError("Nada selecionado.")
        say(f"Baixando e instalando {len(ids)} driver(s)… a tela pode piscar e o som/rede podem cair por alguns segundos.")
        ok, out = ps.run_admin(WU_INSTALL.replace("%IDS%", json.dumps(ids)), timeout=3600)
        if not ok:
            raise RuntimeError("Não deu para instalar: " + out[:200])
        if "NADA" in out:
            return "Esses drivers não estão mais disponíveis (talvez já tenham sido instalados)."
        m = re.search(r"RES (\d+) (\d+) (\w+)", out)
        self.updates = [u for u in (self.updates or []) if u["id"] not in ids] if m and m.group(1) == m.group(2) else self.updates
        self.load_safe()
        if not m:
            return "Instalação terminada."
        done, total, reboot = int(m.group(1)), int(m.group(2)), m.group(3).lower() == "true"
        msg = f"{done} de {total} driver(s) instalado(s) ✓"
        return msg + (" Reinicie o PC para terminar." if reboot else "")

    def load_safe(self):
        try:
            self.load(self._maker)
        except Exception:  # noqa: BLE001
            pass

    _maker = ""

    def state(self):
        items = self.items or []
        return {"items": self.items, "updates": self.updates, "searching": self.searching, "wu_error": self.wu_error,
                "t_list": self.t_list, "t_wu": self.t_wu, "old": sum(1 for i in items if i["old"])}


def open_site(url):
    if url and url.startswith("https://") and (any(url == u for _, _, u in SITES) or url in MAKER_SITES.values()):
        webbrowser.open(url)
        return True
    return False


DRV = Drivers()
