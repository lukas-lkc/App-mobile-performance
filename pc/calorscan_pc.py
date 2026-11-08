"""CalorScan PC — programa do notebook.

Abre uma janela (Edge em modo app, ou o navegador padrao) com duas partes:
- Computador: painel ao vivo, processos, apps, inicializacao, esfriar, discos, seguranca e
  relatorio .md para IA (modulos pc_api, monitor, inventario, discos, seguranca, relatorio).
- Celular: conectar o celular, instalar e liberar o app CalorScan, aplicar os ajustes
  escolhidos no app, medir o aquecimento desde o boot e ver o resultado.

So usa a biblioteca padrao do Python. Rode com: pythonw calorscan_pc.py
"""
import base64
import io
import json
import os
import platform
import re
import secrets
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
sys.path.insert(0, str(HERE))
import analisar  # noqa: E402,F401  (mesmo repositorio)
import diagnostico  # noqa: E402

TOKEN = secrets.token_hex(16)  # so a janela do CalorScan conhece: bloqueia outros sites de mandar acoes
try:
    import pc_api  # noqa: E402
except Exception:  # noqa: BLE001  (fora do Windows a parte Computador fica desligada)
    pc_api = None

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

    serial = os.environ.get("CALORSCAN_SERIAL")  # aparelho escolhido (com 2 no cabo, o ADB recusa sem isso)
    GLOBAL = ("devices", "kill-server", "start-server", "reconnect")

    def run(self, *args, timeout=30, check=False, both=False):
        if not self.path:
            raise RuntimeError("ADB não encontrado")
        if self.serial and args and args[0] not in self.GLOBAL:
            args = ("-s", self.serial, *args)
        p = subprocess.run([self.path, *args], capture_output=True, text=True, timeout=timeout,
                           creationflags=NO_WINDOW, encoding="utf-8", errors="replace")
        if check and p.returncode != 0:
            raise RuntimeError((p.stderr or p.stdout).strip() or f"adb {' '.join(args)} falhou")
        out = p.stdout + ("\n" + p.stderr if both else "")  # o "adb install" manda o motivo da falha no stderr
        return out.replace("\r", "")

    def shell(self, cmd, timeout=30):
        return self.run("shell", cmd, timeout=timeout)

    last_fix = 0.0

    def recover(self, force=False):
        """Destrava a conexao depois que o celular reinicia: o ADB do Windows as vezes fica com o aparelho
        'offline' ou some com ele, mesmo o celular ligado. 1o tenta reconectar; se nao der, reinicia o servidor."""
        if not force and time.time() - self.last_fix < 20:
            return False
        self.last_fix = time.time()
        try:
            self.run("reconnect", "offline", timeout=15)
            time.sleep(2)
            if any(d["state"] == "device" for d in self.devices()):
                return True
            self.run("kill-server", timeout=15)
            time.sleep(1)
            self.run("start-server", timeout=30)
            time.sleep(3)
            return any(d["state"] == "device" for d in self.devices())
        except Exception:  # noqa: BLE001
            return False

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
        self.seen_device = False

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
            # ja vimos um celular nesta sessao e agora ele sumiu/ficou offline: tenta destravar o ADB sozinho
            if self.seen_device and not self.busy and not any(d["state"] == "device" for d in devs) \
                    and not any(d["state"] == "unauthorized" for d in devs):
                if self.adb.recover():
                    devs = self.adb.devices()
        except Exception as e:  # noqa: BLE001
            info["error"] = str(e)
            return info
        if not devs:
            if self.scan.get("phase") in ("rebooting", "waiting"):
                info["device"] = "rebooting"
            return info
        forced = os.environ.get("CALORSCAN_SERIAL")
        pool = [x for x in devs if x["serial"] == forced] if forced else devs
        if not pool:
            return info
        # prefere o mesmo aparelho de antes; senao um celular de verdade (nao emulador) pronto para uso
        d = next((x for x in pool if x["serial"] == self.adb.serial and x["state"] == "device"), None) or \
            next((x for x in pool if x["state"] == "device" and not x["serial"].startswith("emulator-")), None) or \
            next((x for x in pool if x["state"] == "device"), pool[0])
        if self.adb.serial != d["serial"]:
            self.adb.serial = d["serial"]
            self.phone = {}
        info["device"] = d["state"]  # device | unauthorized | offline
        if d["state"] == "device":
            self.seen_device = True
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
        out = self.adb.shell(f"dumpsys package {PKG} | grep -E 'versionCode|versionName|WRITE_SECURE_SETTINGS|MODIFY_QUIET_MODE'")
        local_name, local_code = apk_version(), apk_code()
        if "versionName" not in out:
            return {"installed": False, "available": local_name, "need_reinstall": self.app.get("need_reinstall")}
        ver = re.search(r"versionName=(\S+)", out)
        code = re.search(r"versionCode=(\d+)", out)
        granted = "WRITE_SECURE_SETTINGS: granted=true" in out
        info = {"installed": True, "version": ver.group(1) if ver else "?", "code": int(code.group(1)) if code else 0,
                "granted": granted, "available": local_name, "available_code": local_code,
                "need_reinstall": self.app.get("need_reinstall")}
        # versao antiga no celular (numero menor) = atualizacao. O aviso "reinstalar" so some quando instalar der certo.
        info["update"] = bool(local_code and info["code"] < local_code)
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
import celular_live  # noqa: E402
LIVE = celular_live.PhoneLive(S.adb)


RAW = "https://github.com/lukas-lkc/App-mobile-performance/raw/ccr-e3a2eeb0-ekv3ov/apk/"


def _read_int(f):
    try:
        return int(f.read_text().strip())
    except (OSError, ValueError):
        return 0


def _downloaded_is_newer():
    """O APK baixado do GitHub (em %LOCALAPPDATA%) e mais novo que o da pasta do projeto?"""
    if not (DATA_DIR / "CalorScan.apk").exists():
        return False
    return not APK.exists() or _read_int(DATA_DIR / "VERSION_CODE") > _read_int(ROOT / "apk" / "VERSION_CODE")


def apk_version():
    """versionName do APK que sera instalado."""
    f = (DATA_DIR if _downloaded_is_newer() else ROOT / "apk") / "VERSION"
    return f.read_text().strip() if f.exists() else None


def apk_code():
    """versionCode do APK que sera instalado (numero que so aumenta)."""
    return _read_int((DATA_DIR if _downloaded_is_newer() else ROOT / "apk") / "VERSION_CODE") or None


def check_github_update():
    """Baixa do GitHub se la existir versao mais nova que a da pasta. Sem internet, segue com a local."""
    try:
        with urllib.request.urlopen(RAW + "VERSION_CODE", timeout=8) as r:
            remote = int(r.read().decode().strip())
    except Exception:  # noqa: BLE001
        return
    if remote <= (apk_code() or 0) and (APK.exists() or (DATA_DIR / "CalorScan.apk").exists()):
        return
    S.say(f"Baixando a versão nova do app (nº {remote}) do GitHub…")
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = DATA_DIR / "CalorScan.apk.part"
    urllib.request.urlretrieve(RAW + "CalorScan.apk", tmp)
    tmp.replace(DATA_DIR / "CalorScan.apk")
    (DATA_DIR / "VERSION_CODE").write_text(str(remote))
    try:
        with urllib.request.urlopen(RAW + "VERSION", timeout=8) as r:
            (DATA_DIR / "VERSION").write_text(r.read().decode().strip())
    except Exception:  # noqa: BLE001
        pass


def apk_path():
    return DATA_DIR / "CalorScan.apk" if _downloaded_is_newer() or not APK.exists() else APK


# ----------------------------------------------------------------------------- acoes
def act_download_adb():
    download_adb(S.say)
    S.adb.find()
    S.say("ADB pronto ✓")


SIG_ERRORS = ("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "signatures do not match", "INSTALL_FAILED_VERSION_DOWNGRADE",
              "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")


def act_install():
    """Instala ou atualiza o app no celular conectado (mantem os dados do app)."""
    check_github_update()
    apk = apk_path()
    if not apk.exists():
        raise RuntimeError("Não achei o app (CalorScan.apk). Conecte o notebook à internet e tente de novo.")
    S.say("Instalando no celular… (se o celular perguntar, toque em Permitir/Instalar)")
    out = S.adb.run("install", "-r", "-g", str(apk), timeout=300, both=True)
    if "Success" in out:
        S.app["need_reinstall"] = None
        S.say("App instalado e atualizado ✓")
        return
    if any(e.lower() in out.lower() for e in SIG_ERRORS):
        # versao antiga feita com outra chave: o Android nao deixa instalar por cima
        S.app["need_reinstall"] = "assinatura"
        raise RuntimeError("A versão que está no celular é antiga e foi feita de outro jeito: o Android não deixa "
                           "atualizar por cima. Use “Reinstalar” no passo 2.")
    if "INSTALL_FAILED_USER_RESTRICTED" in out or "INSTALL_FAILED_VERIFICATION_FAILURE" in out:
        raise RuntimeError("O celular bloqueou a instalação. Samsung: desligue Configurações → Segurança e privacidade → "
                           "Bloqueador automático, e toque em Instalar se aparecer o aviso.")
    if "INSTALL_FAILED_INSUFFICIENT_STORAGE" in out:
        raise RuntimeError("O celular está sem espaço. Libere uns 50 MB e tente de novo.")
    raise RuntimeError("A instalação falhou: " + out.strip()[-200:])


def act_reinstall():
    """Remove a versao antiga (que nao aceita atualizacao) e instala a nova. Apaga o historico do app."""
    S.say("Removendo a versão antiga do app…")
    S.adb.run("uninstall", PKG, timeout=60)
    S.app["need_reinstall"] = None
    act_install()
    S.say("App reinstalado ✓ Faça o passo 3 (Liberar) de novo.")


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


def wait_phone_back(limit_s):
    """Espera o celular voltar depois de reiniciar, destravando o ADB do Windows quando ele 'perde' o aparelho."""
    t0 = time.time()
    told = set()
    while time.time() - t0 < limit_s:
        try:
            devs = S.adb.devices()
        except Exception:  # noqa: BLE001
            devs = []
        if S.adb.serial:  # so interessa o aparelho que esta sendo medido
            devs = [d for d in devs if d["serial"] == S.adb.serial]
        states = {d["state"] for d in devs}
        if "device" in states:
            try:
                ready = S.adb.shell(f"getprop sys.boot_completed; test -f {REMOTE}/thermal_scan.sh && echo ok",
                                    timeout=10).split()
                if ready[:2] == ["1", "ok"]:
                    return
            except Exception:  # noqa: BLE001
                pass
        elif "unauthorized" in states:
            if "auth" not in told:
                told.add("auth")
                S.say("Olhe o celular: desbloqueie e toque em Permitir (depuração USB).")
        elif time.time() - t0 > 45:
            # celular sumido ou "offline" depois de ligar: reconecta / reinicia o ADB (no maximo a cada 20 s)
            if S.adb.recover() and "fix" not in told:
                told.add("fix")
                S.say("Reconectei o ADB ✓")
            elif time.time() - t0 > 120 and "cable" not in told:
                told.add("cable")
                S.say("Ainda não achei o celular. Desbloqueie o celular; se não aparecer, tire e coloque o cabo.")
        time.sleep(3)
    raise RuntimeError(f"O celular não voltou em {limit_s // 60} minutos. Se ele já ligou, clique em "
                       "“Reconectar” no passo 1 e depois em “Medir agora”.")


def act_reconnect():
    S.say("Reconectando o celular…")
    if S.adb.recover(force=True):
        S.say("Celular encontrado de novo ✓")
    else:
        devs = S.adb.devices()
        if any(d["state"] == "unauthorized" for d in devs):
            S.say("Olhe o celular: desbloqueie e toque em Permitir.")
        else:
            raise RuntimeError("Não achei o celular. Desbloqueie, tire e coloque o cabo e tente de novo.")


def act_boot(minutes=10):
    push_script()
    S.adb.shell(f"rm -f {REMOTE}/state {REMOTE}/pid")
    S.scan = {"phase": "rebooting"}
    S.say("Reiniciando o celular…")
    try:
        S.adb.run("reboot", timeout=20)
    except subprocess.TimeoutExpired:
        pass  # o "adb reboot" as vezes fica esperando o aparelho sumir; o pedido de reiniciar ja foi enviado
    time.sleep(8)
    S.scan = {"phase": "waiting"}
    S.say("Esperando o celular ligar. Desbloqueie quando pedir.")
    wait_phone_back(600)
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
    "reinstall": ("Reinstalando o app", act_reinstall),
    "reconnect": ("Reconectando", act_reconnect),
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

    def local_only(self):
        """Recusa pedidos que nao vieram da janela do CalorScan (outros sites / DNS rebinding)."""
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost")

    def do_GET(self):
        S.last_ping = time.time()
        if not self.local_only():
            return self.send(403, "{}")
        path, _, qs = self.path.partition("?")
        if path in ("/", "/index.html"):
            html = (HERE / "ui.html").read_text(encoding="utf-8").replace("%%TOKEN%%", TOKEN)
            html = html.replace("%%PC%%", "1" if pc_api else "0")
            return self.send(200, html, "text/html")
        if path == "/ui_pc.js":
            return self.send(200, (HERE / "ui_pc.js").read_text(encoding="utf-8"), "text/javascript")
        if path.startswith("/api/pc/"):
            if not pc_api:
                return self.send(404, "{}")
            try:
                res = pc_api.get(path, qs)
            except Exception as e:  # noqa: BLE001
                return self.send(500, json.dumps({"error": str(e)}))
            return self.send(200 if res is not None else 404, pc_api.dumps(res if res is not None else {}))
        if self.path == "/api/status":
            phone = S.refresh() if not S.busy or S.scan.get("phase") == "running" else S.__dict__.get("_last", {})
            S._last = phone
            return self.send(200, json.dumps({
                "phone": phone, "app": S.app if phone.get("device") == "device" else {},
                "busy": S.busy, "log": S.log, "scan": S.scan,
                "result": {k: v for k, v in (S.result or {}).items() if k != "markdown"} if S.result else None,
                "has_md": bool(S.result), "driver_url": SAMSUNG_DRIVER,
            }))
        if self.path == "/api/phone/live":
            last = S.__dict__.get("_last", {})
            if last.get("device") != "device" or S.scan.get("phase") in ("rebooting", "waiting", "collecting"):
                return self.send(200, json.dumps({"now": None, "off": True}))
            return self.send(200, json.dumps(LIVE.snapshot(), ensure_ascii=False))
        if self.path == "/api/md":
            return self.send(200, (S.result or {}).get("markdown", ""), "text/markdown")
        return self.send(404, "{}")

    def do_POST(self):
        S.last_ping = time.time()
        if not self.local_only() or self.headers.get("X-Token") != TOKEN:
            return self.send(403, json.dumps({"ok": False, "msg": "Pedido recusado"}))
        if self.path.startswith("/api/pc/") and pc_api:
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}") if n else {}
            except ValueError:
                body = {}
            code, res = pc_api.post(self.path, body)
            return self.send(code, pc_api.dumps(res))
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
        subprocess.Popen([edge, f"--app={url}", "--window-size=1280,900"], creationflags=NO_WINDOW)
    else:
        webbrowser.open(url)


def main():
    load_last_result()
    if pc_api:
        pc_api.REPORTS = REPORTS
        pc_api.startup()
    srv = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("CALORSCAN_PORT", 0))), Handler)
    url = f"http://127.0.0.1:{srv.server_port}/"
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    if not os.environ.get("CALORSCAN_NO_WINDOW"):
        open_window(url)
    print(url, flush=True)
    # fecha sozinho quando a janela fica fechada por um tempo
    while time.time() - S.last_ping < 45 or S.busy or (pc_api and pc_api.busy()):
        if pc_api and pc_api.JOBS.quit:
            time.sleep(2)
            break
        time.sleep(3)
    if pc_api:
        pc_api.MON.stop()
    srv.shutdown()


if __name__ == "__main__":
    main()
