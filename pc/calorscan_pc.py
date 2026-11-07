"""CalorScan PC — programa do notebook.

Abre uma janela (Edge em modo app, ou o navegador padrao) com botoes para:
conectar o celular, instalar e liberar o app CalorScan, aplicar os ajustes
escolhidos no app, medir o aquecimento desde o boot e ver o resultado.

So usa a biblioteca padrao do Python. Rode com: pythonw calorscan_pc.py
"""
import base64
import io
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
import analisar  # noqa: E402  (mesmo repositorio)
import diagnostico  # noqa: E402

PKG = "br.calorscan"
REMOTE = "/data/local/tmp/thermal_scan"
APK = ROOT / "apk" / "CalorScan.apk"
APK_URL = "https://github.com/lukas-lkc/App-mobile-performance/raw/ccr-e3a2eeb0-ekv3ov/apk/CalorScan.apk"
REPORTS = Path(os.environ.get("CALORSCAN_REPORTS", ROOT / "relatorios"))
IS_WIN = platform.system() == "Windows"
NO_WINDOW = 0x08000000 if IS_WIN else 0
DATA_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".local" / "share")) / "CalorScan"
SAMSUNG_DRIVER = "https://developer.samsung.com/android-usb-driver"
PT_URL = {
    "Windows": "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
    "Darwin": "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
    "Linux": "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
}

# Comandos que o app do celular pode pedir para o notebook executar (lista fechada).
ALLOWED = [
    r"cmd appops set [\w.]+ RUN_ANY_IN_BACKGROUND (ignore|allow)",
    r"am set-standby-bucket [\w.]+ (restricted|active)",
    r"pm disable-user --user 0 (com\.samsung\.android\.bixby\.wakeup|com\.samsung\.android\.smartsuggestions)",
    r"pm enable (com\.samsung\.android\.bixby\.wakeup|com\.samsung\.android\.smartsuggestions)",
    r"pm grant br\.calorscan android\.permission\.[A-Z_]+",
    r"(cmd )?appops set br\.calorscan [A-Z_]+ allow",
    r"dumpsys deviceidle whitelist \+br\.calorscan",
    r"settings put global (wifi_scan_always_enabled|ble_scan_always_enabled) [01]",
]


def allowed(cmd: str) -> bool:
    return any(re.fullmatch(p, cmd) for p in ALLOWED)


# ----------------------------------------------------------------------------- adb
class Adb:
    def __init__(self):
        self.path = None
        self.find()

    def find(self):
        exe = "adb.exe" if IS_WIN else "adb"
        if os.environ.get("CALORSCAN_ADB"):  # usado nos testes
            self.path = os.environ["CALORSCAN_ADB"]
            return True
        for c in [ROOT / exe, HERE / exe, ROOT / "platform-tools" / exe, DATA_DIR / "platform-tools" / exe]:
            if c.exists():
                self.path = str(c)
                return True
        w = shutil.which("adb")
        if w:
            self.path = w
            return True
        return False

    def run(self, *args, timeout=30, check=False):
        if not self.path:
            raise RuntimeError("ADB não encontrado")
        p = subprocess.run([self.path, *args], capture_output=True, text=True, timeout=timeout,
                           creationflags=NO_WINDOW, encoding="utf-8", errors="replace")
        if check and p.returncode != 0:
            raise RuntimeError((p.stderr or p.stdout).strip() or f"adb {' '.join(args)} falhou")
        return p.stdout.replace("\r", "")

    def shell(self, cmd, timeout=30):
        return self.run("shell", cmd, timeout=timeout)

    def devices(self):
        out = self.run("devices", "-l", timeout=10)
        devs = []
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                model = next((p.split(":", 1)[1] for p in parts if p.startswith("model:")), "")
                devs.append({"serial": parts[0], "state": parts[1], "model": model.replace("_", " ")})
        return devs


def download_adb(progress):
    url = PT_URL.get(platform.system())
    if not url:
        raise RuntimeError("Sistema não suportado para baixar o ADB")
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    progress("Baixando o ADB do Google…")
    with urllib.request.urlopen(url, timeout=60) as r:
        data = r.read()
    progress("Descompactando…")
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        z.extractall(DATA_DIR)
    if not IS_WIN:
        os.chmod(DATA_DIR / "platform-tools" / "adb", 0o755)


# ----------------------------------------------------------------------------- estado
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.adb = Adb()
        self.busy = None          # texto da acao em andamento
        self.log = []             # mensagens recentes
        self.last_ping = time.time()
        self.scan = {"phase": "idle"}  # idle | rebooting | waiting | running | done
        self.result = None
        self.phone = {}
        self.app = {}
        self.plan = None

    def say(self, msg):
        with self.lock:
            self.log = (self.log + [msg])[-6:]

    # .......................................................... leitura do celular
    def refresh(self):
        info = {"adb": bool(self.adb.path), "device": "none"}
        if not self.adb.path:
            return info
        try:
            devs = self.adb.devices()
        except Exception as e:  # noqa: BLE001
            info["error"] = str(e)
            return info
        if not devs:
            if self.scan.get("phase") in ("rebooting", "waiting"):
                info["device"] = "rebooting"
            return info
        d = devs[0]
        info["device"] = d["state"]  # device | unauthorized | offline
        info["model"] = d["model"]
        if d["state"] != "device":
            return info
        try:
            batt = self.adb.shell("dumpsys battery", timeout=10)
            m = re.search(r"temperature: (\d+)", batt)
            info["temp"] = int(m.group(1)) / 10 if m else None
            info["charging"] = bool(re.search(r"(AC|USB|Wireless) powered: true", batt))
            th = self.adb.shell("dumpsys thermalservice | grep -m1 'Thermal Status'", timeout=10)
            m = re.search(r"(\d+)", th)
            info["thermal"] = int(m.group(1)) if m else None
            if not self.phone.get("model_full"):
                self.phone["model_full"] = self.adb.shell("getprop ro.product.model").strip()
                self.phone["android"] = self.adb.shell("getprop ro.build.version.release").strip()
            info.update(self.phone)
            info["dev_enabled"] = self.adb.shell("settings get global development_settings_enabled").strip() == "1"
            info["uptime"] = int(float(self.adb.shell("cat /proc/uptime").split()[0]))
            st = self.adb.shell(f"cat {REMOTE}/state 2>/dev/null").strip()
            if st.startswith("running"):
                parts = st.split()
                done_s, total_s = (parts[2].split("/") + ["0"])[:2] if len(parts) > 2 else ("0", "0")
                self.scan = {"phase": "running", "elapsed": int(done_s), "total": int(total_s)}
            elif st.startswith("done") and self.scan.get("phase") != "collecting":
                self.scan = {"phase": "done"}
            elif self.scan.get("phase") == "waiting" and not st:
                pass
            self.app = self.app_info()
        except Exception as e:  # noqa: BLE001
            info["error"] = str(e)
        return info

    def app_info(self):
        out = self.adb.shell(f"dumpsys package {PKG} | grep -E 'versionName|WRITE_SECURE_SETTINGS|MODIFY_QUIET_MODE'")
        if "versionName" not in out:
            return {"installed": False}
        ver = re.search(r"versionName=(\S+)", out)
        granted = "WRITE_SECURE_SETTINGS: granted=true" in out
        info = {"installed": True, "version": ver.group(1) if ver else "?", "granted": granted}
        local = apk_version()
        info["update"] = bool(local and info["version"] != local)
        try:
            self.plan = self.fetch_plan()
            info["pending"] = len(self.plan["commands"]) if self.plan else 0
        except Exception:  # noqa: BLE001
            info["pending"] = 0
        return info

    def fetch_plan(self):
        out = self.adb.shell(f"am broadcast -f 32 -a br.calorscan.PLAN -n {PKG}/.BridgeReceiver", timeout=15)
        m = re.search(r'data="([^"]*)"', out)
        if not m or not m.group(1):
            return None
        return json.loads(base64.b64decode(m.group(1)).decode())


S = State()


def apk_version():
    """versionName do APK que esta na pasta (lido do arquivo de versao ao lado)."""
    f = ROOT / "apk" / "VERSION"
    return f.read_text().strip() if f.exists() else None


# ----------------------------------------------------------------------------- acoes
def act_download_adb():
    download_adb(S.say)
    S.adb.find()
    S.say("ADB pronto ✓")


def act_install():
    apk = APK
    if not apk.exists():
        S.say("Baixando o app do GitHub…")
        apk = DATA_DIR / "CalorScan.apk"
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(APK_URL, apk)
    S.say("Instalando no celular… (aceite no celular se pedir)")
    out = S.adb.run("install", "-r", "-g", str(apk), timeout=180)
    if "Success" not in out:
        raise RuntimeError("A instalação falhou: " + out.strip()[-200:])
    S.say("App instalado ✓")


def run_plan(plan):
    cmds = plan.get("commands", [])
    bad = [c for c in cmds if not allowed(c)]
    if bad:
        raise RuntimeError("Comando não permitido recusado: " + bad[0])
    for c in cmds:
        S.say("→ " + c)
        out = S.adb.shell(c, timeout=30)
        if "Exception" in out or "Error" in out:
            S.say("  aviso: " + out.strip()[:160])
    S.adb.shell(f"am broadcast -f 32 -a br.calorscan.APPLIED -n {PKG}/.BridgeReceiver --es id {plan.get('id', '')}")


def act_grant():
    plan = S.fetch_plan()
    if not plan:
        raise RuntimeError("O app não respondeu. Abra o CalorScan no celular uma vez e tente de novo.")
    run_plan(plan)
    S.say("App liberado e ajustes aplicados ✓")


def act_apply():
    act_grant()


def push_script():
    S.adb.shell(f"mkdir -p {REMOTE}")
    S.adb.run("push", str(ROOT / "coletor" / "thermal_scan.sh"), f"{REMOTE}/thermal_scan.sh", check=True)


def launch_scan(minutes, boot):
    # comando simples em segundo plano com TODAS as saidas redirecionadas: o adb volta na hora
    S.adb.shell(f"setsid nohup sh {REMOTE}/thermal_scan.sh {minutes * 60} 10 {REMOTE}/out {1 if boot else 0} "
                f"< /dev/null > {REMOTE}/log.txt 2>&1 &", timeout=20)


def act_boot(minutes=10):
    push_script()
    S.adb.shell(f"rm -f {REMOTE}/state {REMOTE}/pid")
    S.scan = {"phase": "rebooting"}
    S.say("Reiniciando o celular…")
    S.adb.run("reboot", timeout=20)
    time.sleep(8)
    S.scan = {"phase": "waiting"}
    S.say("Esperando o celular ligar. Desbloqueie quando pedir.")
    t0 = time.time()
    while time.time() - t0 < 300:
        try:
            if S.adb.shell(f"test -f {REMOTE}/thermal_scan.sh && echo ok", timeout=8).strip() == "ok":
                break
        except Exception:  # noqa: BLE001
            pass
        time.sleep(2)
    else:
        raise RuntimeError("O celular não voltou em 5 minutos. Confira o cabo e tente de novo.")
    launch_scan(minutes, True)
    up = S.adb.shell("cut -d. -f1 /proc/uptime").strip()
    S.scan = {"phase": "running", "elapsed": 0, "total": minutes * 60}
    S.say(f"Medição começou {up} s depois de ligar ✓ Pode tirar o cabo.")


def act_now(minutes=10):
    push_script()
    S.adb.shell(f"rm -f {REMOTE}/state {REMOTE}/pid")
    launch_scan(minutes, False)
    S.scan = {"phase": "running", "elapsed": 0, "total": minutes * 60}
    S.say("Medição iniciada ✓ Pode tirar o cabo e usar o celular.")


def act_collect():
    S.scan = {"phase": "collecting"}
    model = re.sub(r"[^A-Za-z0-9]+", "-", S.phone.get("model_full", "android")) or "android"
    dest = REPORTS / f"{time.strftime('%Y%m%d-%H%M%S')}_{model}"
    REPORTS.mkdir(parents=True, exist_ok=True)
    S.say("Copiando a medição do celular…")
    S.adb.run("pull", f"{REMOTE}/out", str(dest), timeout=180, check=True)
    S.adb.shell(f"rm -rf {REMOTE}/out {REMOTE}/state {REMOTE}/pid")
    S.result = diagnostico.analyze(dest)
    (dest / "RESUMO_IA.md").write_text(S.result["markdown"], encoding="utf-8")
    S.scan = {"phase": "idle"}
    S.say("Resultado pronto ✓")


def act_devoff():
    S.adb.shell("settings put global development_settings_enabled 0")
    S.adb.shell("settings put global adb_enabled 0")
    S.say("Modo desenvolvedor desligado ✓ Pode desconectar.")


ACTIONS = {
    "download_adb": ("Baixando o ADB", act_download_adb),
    "install": ("Instalando o app", act_install),
    "grant": ("Liberando o app", act_grant),
    "apply": ("Aplicando ajustes", act_apply),
    "boot": ("Medindo desde o boot", act_boot),
    "now": ("Iniciando medição", act_now),
    "collect": ("Buscando resultado", act_collect),
    "devoff": ("Desligando modo desenvolvedor", act_devoff),
}


def start_action(name, arg=None):
    if S.busy:
        return False
    label, fn = ACTIONS[name]

    def run():
        S.busy = label
        try:
            fn(int(arg)) if arg else fn()
        except Exception as e:  # noqa: BLE001
            S.say("⚠️ " + str(e))
            if S.scan.get("phase") in ("rebooting", "waiting", "collecting"):
                S.scan = {"phase": "idle"}
        finally:
            S.busy = None

    threading.Thread(target=run, daemon=True).start()
    return True


def load_last_result():
    if not REPORTS.exists():
        return
    dirs = sorted([d for d in REPORTS.iterdir() if (d / "samples.txt").exists()])
    if dirs:
        try:
            S.result = diagnostico.analyze(dirs[-1])
        except Exception:  # noqa: BLE001
            pass


# ----------------------------------------------------------------------------- http
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        S.last_ping = time.time()
        if self.path in ("/", "/index.html"):
            return self.send(200, (HERE / "ui.html").read_text(encoding="utf-8"), "text/html")
        if self.path == "/api/status":
            phone = S.refresh() if not S.busy or S.scan.get("phase") == "running" else S.__dict__.get("_last", {})
            S._last = phone
            return self.send(200, json.dumps({
                "phone": phone, "app": S.app if phone.get("device") == "device" else {},
                "busy": S.busy, "log": S.log, "scan": S.scan,
                "result": {k: v for k, v in (S.result or {}).items() if k != "markdown"} if S.result else None,
                "has_md": bool(S.result), "driver_url": SAMSUNG_DRIVER,
            }))
        if self.path == "/api/md":
            return self.send(200, (S.result or {}).get("markdown", ""), "text/markdown")
        return self.send(404, "{}")

    def do_POST(self):
        S.last_ping = time.time()
        m = re.fullmatch(r"/api/action/(\w+)(?:/(\d+))?", self.path)
        if m and m.group(1) in ACTIONS:
            ok = start_action(m.group(1), m.group(2))
            return self.send(200 if ok else 409, json.dumps({"ok": ok}))
        if self.path == "/api/open-reports":
            REPORTS.mkdir(parents=True, exist_ok=True)
            if IS_WIN:
                os.startfile(REPORTS)  # noqa: S606
            return self.send(200, "{}")
        return self.send(404, "{}")


def open_window(url):
    edge = None
    if IS_WIN:
        for p in [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                  r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"]:
            if Path(p).exists():
                edge = p
    edge = edge or shutil.which("msedge") or shutil.which("google-chrome") or shutil.which("chromium")
    if edge:
        subprocess.Popen([edge, f"--app={url}", "--window-size=1060,860"], creationflags=NO_WINDOW)
    else:
        webbrowser.open(url)


def main():
    load_last_result()
    srv = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("CALORSCAN_PORT", 0))), Handler)
    url = f"http://127.0.0.1:{srv.server_port}/"
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    if not os.environ.get("CALORSCAN_NO_WINDOW"):
        open_window(url)
    print(url, flush=True)
    # fecha sozinho quando a janela fica fechada por um tempo
    while time.time() - S.last_ping < 45 or S.busy:
        time.sleep(3)
    srv.shutdown()


if __name__ == "__main__":
    main()
