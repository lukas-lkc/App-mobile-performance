"""Painel ao vivo do celular conectado no cabo (via ADB).

Um unico "adb shell" a cada ~3 s junta: bateria (temperatura, carga, corrente, tensao), estado termico e
temperaturas dos sensores, memoria, processador total (/proc/stat), frequencias, GPU (se o aparelho expoe),
app na tela e os processos que mais usam o processador (top). So roda enquanto a aba Celular esta aberta.
"""
import re
import threading
import time
from collections import deque

import diagnostico as dg

SCRIPT = r"""
echo ==BAT; dumpsys battery | grep -E '^  (level|temperature|status|voltage|current now):|powered: true'
echo ==CUR; cat /sys/class/power_supply/battery/current_now 2>/dev/null; cat /sys/class/power_supply/battery/voltage_now 2>/dev/null
echo ==THS; dumpsys thermalservice 2>/dev/null | grep -m1 'Thermal Status'
echo ==TH; dumpsys thermalservice 2>/dev/null | sed -n '/Current temperatures from HAL/,/Current cooling devices/p' | grep 'Temperature{' | head -40
echo ==MEM; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo
echo ==NCPU; ls -d /sys/devices/system/cpu/cpu[0-9]* | wc -l
echo ==STAT1; head -1 /proc/stat
echo ==TOP; top -b -n 2 -d 1 -m 25 -s 3 -o PID,USER,%CPU,RES,ARGS 2>/dev/null
echo ==STAT2; head -1 /proc/stat
echo ==FREQ; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null | tr '\n' ' '; echo
echo ==MAXF; cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null | tr '\n' ' '; echo
echo ==GPU; cat /sys/kernel/gpu/gpu_busy /sys/kernel/gpu/gpu_clock 2>/dev/null; cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null
echo ==FG; dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'
echo ==SCR; dumpsys power 2>/dev/null | grep -m1 -E 'mWakefulness='
echo ==END
"""
HISTORY = 100  # ~5 min


def _sections(out):
    sec, cur = {}, None
    for line in out.replace("\r", "").splitlines():
        if line.startswith("==") and line[2:].isupper():
            cur = line[2:].strip()
            sec[cur] = []
        elif cur:
            sec[cur].append(line)
    return sec


def _cpu_total(a, b):
    try:
        x = [int(v) for v in a.split()[1:]]
        y = [int(v) for v in b.split()[1:]]
        idle = (y[3] + y[4]) - (x[3] + x[4])
        tot = sum(y) - sum(x)
        return round(max(0.0, min(100.0, 100.0 * (tot - idle) / tot)), 1) if tot > 0 else None
    except (IndexError, ValueError):
        return None


def parse(out):
    s = _sections(out)
    d = {"t": time.time()}
    bat = "\n".join(s.get("BAT", []))
    m = re.search(r"temperature: (\d+)", bat)
    d["bat_temp"] = int(m.group(1)) / 10 if m else None
    m = re.search(r"level: (\d+)", bat)
    d["level"] = int(m.group(1)) if m else None
    d["charging"] = "powered: true" in bat
    cur = [l.strip() for l in s.get("CUR", []) if re.fullmatch(r"-?\d+", l.strip())]
    mc = re.search(r"current now: (-?\d+)", bat)          # Samsung: mA (o arquivo do sistema e bloqueado)
    mv = re.search(r"^\s*voltage: (\d+)", bat, re.M)      # mV
    if mc:
        ma = int(mc.group(1))
        d["current_ma"] = ma
        if mv:
            d["watts"] = round(abs(ma) / 1000 * int(mv.group(1)) / 1000, 2)
    elif cur:
        ua = int(cur[0])
        # a maioria dos aparelhos informa em microampere; alguns (Samsung antigos) em miliampere
        ma = ua / 1000 if abs(ua) > 20000 else ua
        d["current_ma"] = round(ma)
        if len(cur) > 1:
            uv = int(cur[1])
            v = uv / 1e6 if uv > 100000 else uv / 1000
            d["watts"] = round(abs(ma) / 1000 * v, 2)
    m = re.search(r"(\d+)", " ".join(s.get("THS", [])))
    d["thermal"] = int(m.group(1)) if m else None
    temps = []
    for l in s.get("TH", []):
        mv = re.search(r"mValue=([-\d.]+)", l)
        mn = re.search(r"mName=([^,]+)", l)
        mt = re.search(r"mType=(\d+)", l)
        if mv and mn:
            v = float(mv.group(1))
            if -20 < v < 150:
                temps.append({"name": mn.group(1).strip(), "c": round(v, 1), "type": int(mt.group(1)) if mt else -1})
    d["sensors"] = temps
    # tipos do Android: 0 CPU, 1 GPU, 2 bateria, 3 pele (carcaca)
    for key, typ in (("cpu_temp", 0), ("gpu_temp", 1), ("skin_temp", 3)):
        vals = [x["c"] for x in temps if x["type"] == typ]
        d[key] = max(vals) if vals else None
    mem = dict(re.findall(r"^(\w+):\s+(\d+)", "\n".join(s.get("MEM", [])), re.M))
    if mem.get("MemTotal"):
        tot, av = int(mem["MemTotal"]) * 1024, int(mem.get("MemAvailable", 0)) * 1024
        d["mem_total"], d["mem_used"] = tot, tot - av
        d["mem_pct"] = round(100 * (tot - av) / tot)
        if mem.get("SwapTotal"):
            d["swap_used"] = (int(mem["SwapTotal"]) - int(mem.get("SwapFree", 0))) * 1024
    try:
        ncpu = int("".join(s.get("NCPU", [])).strip())
    except ValueError:
        ncpu = 8
    d["ncpu"] = ncpu
    d["cpu"] = _cpu_total("".join(s.get("STAT1", [])), "".join(s.get("STAT2", [])))
    freqs = [int(x) for x in " ".join(s.get("FREQ", [])).split() if x.isdigit()]
    maxf = [int(x) for x in " ".join(s.get("MAXF", [])).split() if x.isdigit()]
    if freqs:
        d["freq_ghz"] = round(max(freqs) / 1e6, 2)
        if maxf and len(maxf) == len(freqs):
            d["freq_pct"] = round(100 * sum(freqs) / sum(maxf))
    g = [l.strip() for l in s.get("GPU", []) if l.strip()]
    if g:
        m = re.search(r"(\d+)", g[0])
        d["gpu"] = min(100, int(m.group(1))) if m else None
    m = re.search(r"u\d+ ([\w.]+)/", " ".join(s.get("FG", [])))
    d["fg"] = m.group(1) if m else None
    d["fg_name"] = dg.nice(d["fg"]) if d["fg"] else None
    d["screen"] = "Awake" in " ".join(s.get("SCR", []))
    # processos
    procs = {}
    top = s.get("TOP", [])
    # o 1o quadro do top nao tem uso calculado (tudo 0%): usa so o ultimo quadro
    heads = [i for i, l in enumerate(top) if re.match(r"^\s*PID\s+USER\b", l)]
    if heads:
        top = top[heads[-1] + 1:]
    for l in top:
        p = l.split(None, 4)
        if len(p) < 5 or not p[0].isdigit():
            continue
        try:
            cpu = float(p[2])
        except ValueError:
            continue
        name = p[4].split()[0] if p[4] else "?"
        name = name.split("/")[-1] if name.startswith("/") else name
        if name in ("top", "sh", "grep", "sed", "cat", "head", "dumpsys") or name.startswith("toybox"):
            continue  # os proprios comandos da leitura
        if name.startswith("[kworker") or name.startswith("[ksoftirq") or name.startswith("[migration"):
            name = "[kernel]"  # tarefas internas do Linux: junta numa linha so
        user = p[1]
        mu = re.match(r"u(\d+)_a\d+", user)
        prof = mu.group(1) if mu else "0"
        base = name.split(":")[0]
        key = (base, prof)
        nice = "Conexão do cabo (ADB, esta leitura)" if base == "adbd" else "Tarefas internas do Linux" \
            if base == "[kernel]" else dg.nice(base)
        x = procs.setdefault(key, {"pkg": base, "name": nice, "kind": dg.kind(base, user), "profile": prof,
                                   "profile_name": dg.profile_name(prof), "cpu": 0.0, "mem": 0, "n": 0})
        x["cpu"] += cpu / max(1, ncpu)  # top mostra % de 1 nucleo; aqui vira % do celular inteiro
        x["mem"] += _res(p[3])
        x["n"] += 1
    rows = sorted(procs.values(), key=lambda r: -r["cpu"])
    for r in rows:
        r["cpu"] = round(r["cpu"], 1)
    d["procs"] = rows[:15]
    return d


def _res(v):
    m = re.fullmatch(r"([\d.]+)([KMG]?)", v.strip())
    if not m:
        return 0
    return int(float(m.group(1)) * {"": 1024, "K": 1024, "M": 1 << 20, "G": 1 << 30}[m.group(2)])


class PhoneLive:
    def __init__(self, adb):
        self.adb = adb
        self.lock = threading.Lock()
        self.hist = deque(maxlen=HISTORY)
        self.last = None
        self.asked = 0.0
        self.running = False
        self.error = None
        self.session = {}  # (pkg, perfil) -> segundos de processador somados desde que o painel abriu

    def want(self):
        """Chamado pela tela: mantem a coleta viva por mais 10 s."""
        self.asked = time.time()
        if not self.running:
            self.running = True
            threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        try:
            while time.time() - self.asked < 10:
                t0 = time.time()
                try:
                    out = self.adb.shell(SCRIPT, timeout=20)
                    if "==END" not in out:
                        raise RuntimeError(out.strip()[:120] or "sem resposta")
                    d = parse(out)
                    dt = (t0 - self.last["t"]) if self.last else 0
                    for r in d["procs"]:
                        k = (r["pkg"], r["profile"])
                        self.session[k] = self.session.get(k, 0.0) + r["cpu"] / 100 * d["ncpu"] * dt
                    with self.lock:
                        self.last = d
                        self.hist.append({k: d.get(k) for k in ("t", "bat_temp", "cpu", "mem_pct", "watts", "skin_temp",
                                                               "cpu_temp", "gpu")})
                        self.error = None
                except Exception as e:  # noqa: BLE001
                    self.error = str(e)
                time.sleep(max(0.5, 3 - (time.time() - t0)))
        finally:
            self.running = False

    def snapshot(self):
        self.want()
        with self.lock:
            d = dict(self.last) if self.last else None
            hist = list(self.hist)
        top = sorted(((k, v) for k, v in self.session.items() if v > 0), key=lambda kv: -kv[1])[:8]
        return {"now": d, "hist": hist, "error": self.error,
                "session": [{"name": dg.nice(k[0]), "kind": dg.kind(k[0]), "profile": dg.profile_name(k[1]),
                             "secs": round(v, 1)} for k, v in top]}

    def reset(self):
        with self.lock:
            self.hist.clear()
            self.last = None
            self.session = {}
