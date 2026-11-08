"""Rotas /api/pc/* do CalorScan PC (parte Computador)."""
import json
import os
import sys
import threading
import time
import urllib.parse
import webbrowser
from pathlib import Path

import discos
import inventario as invmod
import relatorio
import seguranca
import winapi
from discos import DISK
from inventario import INV
from monitor import MON
from seguranca import SEC

REPORTS = None  # definido por calorscan_pc


class Jobs:
    def __init__(self):
        self.busy = None
        self.log = []
        self.lock = threading.Lock()
        self.quit = False

    def say(self, msg):
        with self.lock:
            self.log = (self.log + [msg])[-6:]

    def start(self, label, fn, *args):
        if self.busy:
            return False

        def run():
            self.busy = label
            try:
                msg = fn(*args)
                if msg:
                    self.say(msg)
            except winapi.Cancelled as e:
                self.say("⚠️ " + str(e))
            except Exception as e:  # noqa: BLE001
                self.say("⚠️ " + str(e))
            finally:
                self.busy = None

        threading.Thread(target=run, daemon=True).start()
        return True


JOBS = Jobs()


def startup():
    MON.app_of = INV.app_of
    MON.start()

    def warm():
        INV.system()
        INV.get_apps()
        INV.get_startup()

    threading.Thread(target=warm, daemon=True).start()


def busy():
    return bool(JOBS.busy) or DISK.state.get("phase") in ("scanning", "hashing")


# ----------------------------------------------------------------------------- GET
def get(path, qs):
    q = {k: v[0] for k, v in urllib.parse.parse_qs(qs).items()}
    if path == "/api/pc/live":
        live = MON.live()
        fs = relatorio.findings(live, INV.sys, INV.apps, INV.startup, SEC.result, winapi.drives())
        score, label = relatorio.health(fs)
        live.update(findings=fs, score=score, score_label=label, busy=JOBS.busy, log=JOBS.log,
                    admin=winapi.is_admin(), sys=INV.sys, sec_running=SEC.running, sec_done=SEC.result is not None,
                    disk_phase=DISK.state.get("phase"), undo=len(invmod.undo_load()))
        return live
    if path == "/api/pc/system":
        return INV.system(q.get("refresh") == "1")
    if path == "/api/pc/apps":
        apps = INV.get_apps(q.get("refresh") == "1")
        use = {}
        for g in MON.live()["procs"]:
            if g.get("app"):
                u = use.setdefault(g["app"], {"cpu": 0.0, "mem": 0, "n": 0, "self": False})
                u["cpu"] += g["cpu"]
                u["mem"] += g["mem"]
                u["n"] += g["count"]
                u["self"] = u["self"] or bool(set(g["pids"]) & MON.my_pids)
        sess = {}
        for r in MON.session_top(500):
            a = INV.app_of(r["path"])
            if a:
                sess[a["id"]] = sess.get(a["id"], 0) + r["cpu_s"]
        out = []
        for a in apps:
            u = use.get(a["id"], {})
            out.append({k: v for k, v in a.items() if k != "uninstall"} |
                       {"cpu": round(u.get("cpu", 0), 1), "mem": u.get("mem", 0), "running": u.get("n", 0),
                        "self": u.get("self", False),
                        "session_cpu": round(sess.get(a["id"], 0), 1)})
        return {"apps": out, "loading": INV.apps is None}
    if path == "/api/pc/startup":
        st = INV.get_startup(q.get("refresh") == "1")
        return {**st, "undo": invmod.undo_load(), "power": (INV.sys or {}).get("power") or invmod.power_plan()}
    if path == "/api/pc/power":
        p = invmod.power_plan()
        if INV.sys:
            INV.sys["power"] = p
        return {"power": p, "undo": invmod.undo_load()}
    if path == "/api/pc/security":
        return {"result": SEC.result, "running": SEC.running}
    if path == "/api/pc/disks":
        return {"drives": winapi.drives(), "state": DISK.state, "summary": DISK.summary()}
    if path == "/api/pc/disk/tree":
        return DISK.children(q.get("path")) or {}
    if path == "/api/pc/disk/files":
        return DISK.files(sort=q.get("sort", "size"), cat=q.get("cat", ""), q=q.get("q", ""),
                          min_mb=int(q.get("min", 1) or 1), offset=int(q.get("offset", 0) or 0),
                          older_days=int(q.get("older", 0) or 0))
    if path == "/api/pc/disk/dups":
        return {"groups": (DISK.dups or [])[:300], "done": DISK.dups is not None}
    if path == "/api/pc/cleanup":
        return {"items": discos.cleanup_info(), "admin": winapi.is_admin()}
    if path == "/api/pc/report":
        live = MON.live()
        md, fs, score, label = relatorio.markdown(live, INV.system(), INV.get_apps(), INV.get_startup(), SEC.result,
                                                  winapi.drives(), MON.session_top(), DISK.summary())
        dest = REPORTS / "pc"
        dest.mkdir(parents=True, exist_ok=True)
        f = dest / f"{time.strftime('%Y%m%d-%H%M%S')}_PC_RESUMO_IA.md"
        f.write_text(md, encoding="utf-8")
        return {"md": md, "file": str(f), "score": score, "label": label, "minutes": live.get("session_min")}
    return None


# ----------------------------------------------------------------------------- POST
def post(path, body):
    a = body.get("a")
    if path != "/api/pc/do" or not a:
        return 404, {"ok": False}

    def ok(msg="", **kw):
        if msg:
            JOBS.say(msg)
        return 200, {"ok": True, "msg": msg, **kw}

    def job(label, fn, *args):
        started = JOBS.start(label, fn, *args)
        return (200, {"ok": True, "job": True}) if started else (409, {"ok": False, "msg": "Espere terminar: " + JOBS.busy})

    try:
        if a == "kill":
            g = next((g for g in MON.groups if g["key"] == body.get("key")), None)
            if not g:
                return ok("Esse programa já fechou.")
            if g["critical"]:
                return 400, {"ok": False, "msg": "Esse é essencial para o Windows (ou para o CalorScan) e não pode ser encerrado."}
            return ok(invmod.kill(g["pids"], g["name"]))
        if a == "open":
            p = body.get("path") or ""
            if p and os.path.exists(p):
                winapi.open_in_explorer(p)
            return ok()
        if a == "web":
            webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote(f"{body.get('name', '')} o que é é seguro"))
            return ok()
        if a == "uninstall":
            return job("Desinstalando", invmod.uninstall, body.get("id"), JOBS.say)
        if a == "startup":
            return ok(invmod.set_startup(body["id"], bool(body.get("on"))), reload=True)
        if a == "task":
            return job("Mudando tarefa agendada", invmod.set_task, body["id"], bool(body.get("on")))
        if a == "service":
            return job("Mudando serviço", invmod.set_service, body["id"], bool(body.get("on")))
        if a == "undo_all":
            return job("Desfazendo ajustes", invmod.undo_all)
        if a == "balanced":
            return ok(invmod.set_balanced(bool(body.get("on"))))
        if a == "cool":
            return ok(invmod.set_cool(bool(body.get("on"))))
        if a == "refresh":
            w = body.get("what")
            fn = {"apps": INV.load_apps, "startup": INV.load_startup, "system": lambda: INV.system(True)}.get(w)
            return job("Atualizando a lista", lambda: (fn(), "Lista atualizada ✓")[1]) if fn else (400, {"ok": False})
        if a == "sec_scan":
            return job("Verificação de segurança", lambda: (INV.get_startup(), SEC.scan(MON, INV, JOBS.say), None)[2])
        if a == "defender":
            return job("Antivírus do Windows", seguranca.defender_scan, body.get("kind", "quick"), JOBS.say)
        if a == "disk_scan":
            root = body.get("root") or ""
            if not os.path.isdir(root):
                return 400, {"ok": False, "msg": "Pasta não encontrada."}
            if DISK.state.get("phase") in ("scanning", "hashing"):
                return 409, {"ok": False, "msg": "Já tem uma análise em andamento."}
            threading.Thread(target=DISK.scan, args=(root,), daemon=True).start()
            return ok()
        if a == "disk_cancel":
            DISK.cancel = True
            return ok()
        if a == "dups":
            if DISK.state.get("phase") in ("scanning", "hashing"):
                return 409, {"ok": False, "msg": "Espere a análise terminar."}
            threading.Thread(target=DISK.find_dups, daemon=True).start()
            return ok()
        if a == "recycle":
            gone = discos.to_recycle(list(body.get("paths") or []))
            n = len(body.get("paths") or [])
            return ok(f"{len(gone)} de {n} item(ns) foram para a Lixeira ✓" if gone else "Nada foi movido (arquivo em uso?)",
                      gone=gone)
        if a == "cleanup":
            ids = list(body.get("ids") or [])
            return job("Limpando", lambda: f"Limpeza concluída ✓ {discos.cleanup(ids, JOBS.say) / 1024 ** 2:.0f} MB liberados")
        if a == "admin":
            if winapi.is_admin():
                return ok("Já está como administrador.")
            script = str(Path(__file__).resolve().parent / "calorscan_pc.py")
            exe = sys.executable
            if exe.lower().endswith("python.exe") and os.path.exists(exe[:-10] + "pythonw.exe"):
                exe = exe[:-10] + "pythonw.exe"
            winapi.shell_exec(exe, f'"{script}"', verb="runas", wait=False)
            JOBS.quit = True
            return ok("Abrindo uma nova janela como administrador… pode fechar esta.")
    except winapi.Cancelled as e:
        return 400, {"ok": False, "msg": str(e)}
    except Exception as e:  # noqa: BLE001
        return 400, {"ok": False, "msg": str(e)}
    return 404, {"ok": False, "msg": "Ação desconhecida"}


def dumps(o):
    return json.dumps(o, ensure_ascii=False, default=str)
