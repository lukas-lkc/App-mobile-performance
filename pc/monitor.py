"""Monitor ao vivo do computador: processador, memoria, processos, GPU, temperatura, disco e rede.

- A cada 2 s: le processador, memoria e todos os processos direto da API do Windows (leve).
- A cada 5 s: um unico PowerShell em segundo plano (prioridade baixa) le os contadores do
  Windows (frequencia, limite de desempenho, zonas termicas, GPU por processo, disco, rede,
  bateria) e a placa NVIDIA (nvidia-smi), se existir.
Guarda 10 min de historico e o consumo acumulado de cada programa desde que o CalorScan abriu.
"""
import json
import os
import subprocess
import threading
import time
from collections import deque

import ps
import suspeitos as sus
import winapi

SAMPLE_S = 2
HISTORY = 300  # 10 min
SYSTEM_PROCS = {"system", "registry", "memory compression", "secure system", "system idle process",
                "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe",
                "lsaiso.exe", "svchost.exe", "fontdrvhost.exe", "dwm.exe", "memcompression",
                "sgrmbroker.exe", "msmpeng.exe", "nissrv.exe", "mpdefendercoreservice.exe", "wudfhost.exe",
                "searchindexer.exe", "spoolsv.exe", "audiodg.exe", "wmiprvse.exe", "dashost.exe",
                "ctfmon.exe", "sihost.exe", "taskhostw.exe", "runtimebroker.exe", "conhost.exe",
                "dllhost.exe", "explorer.exe", "startmenuexperiencehost.exe", "searchhost.exe",
                "shellexperiencehost.exe", "textinputhost.exe", "smartscreen.exe", "securityhealthservice.exe",
                "securityhealthsystray.exe", "lockapp.exe", "useroobebroker.exe", "applicationframehost.exe",
                "systemsettings.exe", "widgets.exe", "phoneexperiencehost.exe", "msedgewebview2.exe"}
CRITICAL = {"system", "registry", "memory compression", "secure system", "system idle process", "smss.exe",
            "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe", "lsaiso.exe",
            "svchost.exe", "dwm.exe", "fontdrvhost.exe", "sgrmbroker.exe", "msmpeng.exe"}
FRIENDLY = {"system": "Núcleo do Windows", "registry": "Registro do Windows",
            "memory compression": "Compressão de memória do Windows", "svchost.exe": "Serviços do Windows",
            "msmpeng.exe": "Antivírus do Windows (Defender)", "dwm.exe": "Desenho das janelas do Windows",
            "explorer.exe": "Explorador de Arquivos / Área de trabalho", "searchindexer.exe": "Indexador de pesquisa",
            "tiworker.exe": "Atualização do Windows (instalando)", "msedgewebview2.exe": "Componente web (WebView2)",
            "wmiprvse.exe": "Informações do sistema (WMI)", "audiodg.exe": "Áudio do Windows",
            "csrss.exe": "Processo essencial do Windows", "lsass.exe": "Segurança e senhas do Windows"}

SLOW_SCRIPT = r"""
$parent = %PARENT%
$adm = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$nv = (Get-Command nvidia-smi -ErrorAction SilentlyContinue).Source
while($true){
  if(-not (Get-Process -Id $parent -ErrorAction SilentlyContinue)){ exit }
  $o=@{}
  try{ $p=Get-CimInstance Win32_PerfFormattedData_Counters_ProcessorInformation -Filter "Name='_Total'" -EA Stop
       $o.freqpct=[int]$p.PercentofMaximumFrequency; $o.perf=[int]$p.PercentProcessorPerformance
       $o.limit=[int]$p.PercentPerformanceLimit; $o.base=[int]$p.ProcessorFrequency }catch{}
  try{ $o.zones=@(Get-CimInstance Win32_PerfFormattedData_Counters_ThermalZoneInformation -EA Stop | %{ [math]::Round($_.HighPrecisionTemperature/10-273.15,1) }) }catch{}
  if($adm){ try{ $o.acpi=@(Get-CimInstance -Namespace root/wmi MSAcpi_ThermalZoneTemperature -EA Stop | %{ [math]::Round($_.CurrentTemperature/10-273.15,1) }) }catch{} }
  try{ $g=@{}; Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -EA Stop | ?{ $_.UtilizationPercentage -gt 0 } | %{
         if($_.Name -match 'pid_(\d+)_.*engtype_(\w+)'){ $k=$matches[1]+'|'+$matches[2]; $g[$k]=[int]$g[$k]+[int]$_.UtilizationPercentage } }
       $gp=@{}; foreach($k in $g.Keys){ $id=$k.Split('|')[0]; if($g[$k] -gt [int]$gp[$id]){ $gp[$id]=[math]::Min(100,$g[$k]) } }; $o.gpu=$gp }catch{}
  try{ $d=Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter "Name='_Total'" -EA Stop
       $o.disk=[math]::Min(100,[int]$d.PercentDiskTime); $o.dr=[long]$d.DiskReadBytesPersec; $o.dw=[long]$d.DiskWriteBytesPersec }catch{}
  try{ $n=@(Get-CimInstance Win32_PerfFormattedData_Tcpip_NetworkInterface -EA Stop)
       $o.rx=[long]($n | Measure-Object BytesReceivedPersec -Sum).Sum; $o.tx=[long]($n | Measure-Object BytesSentPersec -Sum).Sum }catch{}
  try{ $b=Get-CimInstance Win32_Battery -EA Stop | Select-Object -First 1
       if($b){ $o.batt=[int]$b.EstimatedChargeRemaining; $o.ac=($b.BatteryStatus -ne 1 -and $b.BatteryStatus -ne 4 -and $b.BatteryStatus -ne 5) } }catch{}
  if($nv){ try{ $l=(& $nv --query-gpu=name,temperature.gpu,utilization.gpu,power.draw,fan.speed --format=csv,noheader,nounits 2>$null | Select-Object -First 1)
       if($l){ $f=$l.Split(','); $o.nv=@{name=$f[0].Trim();temp=[int]$f[1];util=[int]$f[2];watts=$f[3].Trim();fan=$f[4].Trim()} } }catch{} }
  try{ [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress -Depth 4)); [Console]::Out.Flush() }catch{ exit }
  Start-Sleep -Seconds 5
}
"""


def friendly_name(name, info):
    n = name.lower()
    if n in FRIENDLY:
        return FRIENDLY[n]
    d = (info.get("desc") or info.get("product") or "").strip()
    if d and len(d) < 70:
        return d
    return name[:-4] if n.endswith(".exe") else name


class Monitor:
    def __init__(self):
        self.lock = threading.Lock()
        self.ncpu = os.cpu_count() or 1
        self.prev_sys = None
        self.prev_t = None
        self.prev_proc = {}      # (pid, criado) -> cpu acumulada (100 ns)
        self.paths = {}          # (pid, criado) -> caminho
        self.groups = []         # ultima lista de programas (agrupada)
        self.hist = deque(maxlen=HISTORY)
        self.session = {}        # chave -> {"cpu_s", "mem_peak", "name", "path", "gpu_s"}
        self.started = time.time()
        self.samples = 0
        self.cpu = 0.0
        self.mem = {}
        self.slow = {}
        self.slow_t = 0
        self.signatures = {}     # caminho -> {"st", "by"} (preenchido pela verificacao de seguranca)
        self.app_of = lambda path: None   # ligado depois ao inventario de apps
        self.my_pids = {os.getpid()}
        self.my_keys = set()
        self._ps = None
        self.running = False

    # ------------------------------------------------------------------ ciclo
    def start(self):
        if self.running or not winapi.IS_WIN:
            return
        self.running = True
        threading.Thread(target=self._loop, daemon=True).start()
        threading.Thread(target=self._slow_loop, daemon=True).start()

    def stop(self):
        self.running = False
        if self._ps:
            try:
                self._ps.kill()
            except Exception:  # noqa: BLE001
                pass

    def _loop(self):
        while self.running:
            try:
                self.sample()
            except Exception:  # noqa: BLE001
                pass
            time.sleep(SAMPLE_S)

    def _slow_loop(self):
        script = SLOW_SCRIPT.replace("%PARENT%", str(os.getpid()))
        while self.running:
            try:
                self._ps = subprocess.Popen(
                    [ps.PS, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                     "-EncodedCommand", ps._enc(script)],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL,
                    creationflags=winapi.NO_WINDOW | winapi.BELOW_NORMAL)
                self.my_pids.add(self._ps.pid)
                for line in self._ps.stdout:
                    if not self.running:
                        break
                    try:
                        self.slow = json.loads(line.decode("utf-8", "replace"))
                        self.slow_t = time.time()
                    except ValueError:
                        pass
            except Exception:  # noqa: BLE001
                pass
            time.sleep(10)

    # ------------------------------------------------------------------ amostra
    def sample(self):
        now = time.time()
        idle, kern, user = winapi.system_times()
        if self.prev_sys:
            di = idle - self.prev_sys[0]
            dt = (kern - self.prev_sys[1]) + (user - self.prev_sys[2])
            cpu = max(0.0, min(100.0, 100.0 * (dt - di) / dt)) if dt > 0 else 0.0
        else:
            cpu = 0.0
        wall = (now - self.prev_t) if self.prev_t else 0
        self.prev_sys, self.prev_t = (idle, kern, user), now
        mem = winapi.memory()
        gpu_by_pid = {int(k): v for k, v in (self.slow.get("gpu") or {}).items()} if self.slow else {}

        groups = {}
        new_prev = {}
        for pid, ppid, name, threads in winapi.list_processes():
            if pid == 0:
                continue
            q = winapi.proc_query(pid, want_path=True) if pid != 4 else None
            if q:
                created, cpu100, ws, path = q
                key = (pid, created)
                if key in self.paths:
                    path = self.paths[key]
                else:
                    self.paths[key] = path
                new_prev[key] = cpu100
                delta = cpu100 - self.prev_proc[key] if key in self.prev_proc else 0
                pct = 100.0 * delta / (wall * 1e7 * self.ncpu) if wall > 0 else 0.0
                secs = delta / 1e7
            else:
                path, ws, pct, secs = None, 0, 0.0, 0.0
            gkey = (path or name).lower()
            g = groups.get(gkey)
            if not g:
                g = groups[gkey] = {"key": gkey, "exe": name, "path": path, "pids": [], "cpu": 0.0, "mem": 0,
                                    "threads": 0, "gpu": 0, "protected": q is None}
            g["pids"].append(pid)
            g["cpu"] += pct
            g["mem"] += ws
            g["threads"] += threads
            g["gpu"] = min(100, g["gpu"] + gpu_by_pid.get(pid, 0))
            s = self.session.setdefault(gkey, {"cpu_s": 0.0, "mem_peak": 0, "gpu_s": 0.0, "exe": name,
                                               "path": path, "seen": 0})
            s["cpu_s"] += max(0.0, secs)
            s["gpu_s"] += gpu_by_pid.get(pid, 0) / 100 * wall
        for g in groups.values():
            s = self.session[g["key"]]
            s["mem_peak"] = max(s["mem_peak"], g["mem"])
            s["seen"] += 1
        # limpa caches de processos que acabaram
        self.paths = {k: v for k, v in self.paths.items() if k in new_prev}
        self.prev_proc = new_prev

        out = []
        for g in groups.values():
            info = winapi.file_info(g["path"])
            n = g["exe"].lower()
            g["name"] = friendly_name(g["exe"], info)
            g["company"] = info.get("company", "")
            g["cpu"] = round(min(100.0, g["cpu"]), 1)
            g["count"] = len(g["pids"])
            g["kind"] = ("windows" if n in SYSTEM_PROCS or sus.in_windows(g["path"]) else
                         "protegido" if g["protected"] else "app")
            reasons = sus.path_reasons(g["path"], g["exe"])
            sig = self.signatures.get(g["path"] or "")
            if sig and sig.get("st") not in ("Valid", None) and not sus.in_windows(g["path"]):
                reasons.append(("baixo" if sus.in_program_files(g["path"]) else "medio",
                                "sem assinatura digital válida (não dá para saber quem fez)"))
            g["reasons"] = [r for _, r in reasons]
            g["risk"] = sus.level(reasons)
            g["signer"] = (sig or {}).get("by", "")
            mine = bool(set(g["pids"]) & self.my_pids)
            if mine:
                self.my_keys.add(g["key"])
                g["name"] += " (inclui o CalorScan)" if g["count"] > 1 else " (CalorScan)"
            g["critical"] = n in CRITICAL or g["key"] in ("system",) or mine
            g["session_cpu"] = round(self.session[g["key"]]["cpu_s"], 1)
            app = self.app_of(g["path"])
            g["app"] = app["id"] if app else None
            g["app_name"] = app["name"] if app else None
            out.append(g)
        out.sort(key=lambda x: (-x["cpu"], -x["mem"]))

        temps = self.temps()
        point = {"t": round(now - self.started), "cpu": round(cpu, 1), "mem": mem["load"],
                 "gpu": (self.slow.get("nv") or {}).get("util"), "gtemp": temps.get("gpu"),
                 "ctemp": temps.get("cpu"), "disk": self.slow.get("disk"),
                 "freq": self.slow.get("freqpct"), "limit": self.slow.get("limit")}
        with self.lock:
            self.cpu, self.mem, self.groups = cpu, mem, out
            if wall > 0:
                self.hist.append(point)
                self.samples += 1

    # ------------------------------------------------------------------ leitura
    def temps(self):
        s = self.slow or {}
        t = {}
        zones = [z for z in (s.get("acpi") or []) + (s.get("zones") or []) if 15 < z < 120]
        if zones:
            t["cpu"] = max(zones)
            t["cpu_src"] = "zona térmica do Windows"
        nv = s.get("nv") or {}
        if nv.get("temp"):
            t["gpu"] = nv["temp"]
            t["gpu_name"] = nv.get("name")
        return t

    def live(self):
        with self.lock:
            hist = list(self.hist)
            groups = list(self.groups)
            cpu, mem = self.cpu, dict(self.mem)
        s = self.slow or {}
        recent = [p["cpu"] for p in hist[-30:]]  # ~1 min
        freq = None
        if s.get("base") and s.get("freqpct") is not None:
            freq = round(s["base"] * s["freqpct"] / 100 / 1000, 2)
        limited = [p for p in hist[-60:] if p.get("limit") is not None and p["limit"] < 90]
        return {
            "cpu": round(cpu, 1), "cpu_avg1": round(sum(recent) / len(recent), 1) if recent else None,
            "mem": mem, "ncpu": self.ncpu, "uptime": winapi.uptime_s(), "freq_ghz": freq,
            "freq_pct": s.get("freqpct"), "limit": s.get("limit"), "throttled": len(limited) >= 10,
            "disk": s.get("disk"), "dr": s.get("dr"), "dw": s.get("dw"), "rx": s.get("rx"), "tx": s.get("tx"),
            "batt": s.get("batt"), "ac": s.get("ac"), "nv": s.get("nv"), "temps": self.temps(),
            "slow_ok": time.time() - self.slow_t < 20, "hist": hist, "procs": groups,
            "session_min": round((time.time() - self.started) / 60, 1),
            "steady": [r for r in self.session_top(8) if r["avg"] >= 5 and r["key"] not in self.my_keys]
            if time.time() - self.started >= 120 else [],
        }

    def session_top(self, n=15):
        mins = max(1e-6, time.time() - self.started)
        rows = []
        for k, s in self.session.items():
            if s["cpu_s"] <= 0 and s["gpu_s"] <= 0:
                continue
            info = winapi.file_info(s["path"])
            name = friendly_name(s["exe"], info) + (" (inclui o CalorScan)" if k in self.my_keys else "")
            rows.append({"key": k, "name": name, "exe": s["exe"], "path": s["path"],
                         "cpu_s": round(s["cpu_s"], 1), "avg": round(100 * s["cpu_s"] / mins / self.ncpu, 1),
                         "gpu_s": round(s["gpu_s"], 1), "mem_peak": s["mem_peak"]})
        rows.sort(key=lambda r: -r["cpu_s"])
        return rows[:n]


MON = Monitor()
