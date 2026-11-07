#!/usr/bin/env python3
"""Gera RESUMO.md a partir de uma pasta de relatorio do scan.

Uso: python3 analisar.py reports/<pasta>
"""
import re
import sys
from collections import defaultdict
from pathlib import Path
from statistics import mean

THERMAL_STATUS = {0: "nenhum", 1: "leve", 2: "moderado", 3: "severo",
                  4: "critico", 5: "emergencia", 6: "desligamento"}


def read(p: Path) -> str:
    try:
        return p.read_text(errors="replace")
    except FileNotFoundError:
        return ""


def parse_top(lines):
    """Pega o ultimo quadro do top (o primeiro nao tem %CPU confiavel)."""
    hdr_idx = [i for i, l in enumerate(lines) if "%CPU" in l and "PID" in l]
    cpu_line = [l for l in lines if re.search(r"\d+%cpu", l)]
    total = idle = None
    if cpu_line:
        m = re.search(r"(\d+)%cpu", cpu_line[-1])
        n = re.search(r"(\d+)%idle", cpu_line[-1])
        if m and n:
            total, idle = int(m.group(1)), int(n.group(1))
    procs = []
    if hdr_idx:
        hdr = lines[hdr_idx[-1]].split()
        try:
            ci = next(i for i, h in enumerate(hdr) if "%CPU" in h)
        except StopIteration:
            return total, idle, procs
        # coluna "S[%CPU]" do formato padrao vira 2 campos nas linhas
        shift = 1 if hdr[ci].startswith("S[") else 0
        ncols = len(hdr) + shift
        for l in lines[hdr_idx[-1] + 1:]:
            parts = l.split()
            if len(parts) < ncols:
                continue
            try:
                cpu = float(parts[ci + shift])
            except ValueError:
                continue
            name = " ".join(parts[ncols - 1:])
            procs.append((name, cpu))
    return total, idle, procs


def parse_samples(text):
    samples = []
    for block in re.split(r"(?m)^=== SAMPLE ", text)[1:]:
        head, _, body = block.partition("\n")
        m = re.match(r"(\d+) t=(\d+)", head)
        if not m:
            continue
        s = {"n": int(m.group(1)), "t": int(m.group(2)), "zones": {},
             "hal": {}, "procs": []}
        u = re.search(r"uptime=(\d+)", head)
        if u:
            s["uptime"] = int(u.group(1))
        sections = defaultdict(list)
        cur = None
        for l in body.splitlines():
            if l.startswith("--- "):
                cur = l[4:].strip()
                continue
            if cur:
                sections[cur].append(l)
        for l in sections["battery"]:
            l = l.strip()
            if l.startswith("temperature:"):
                s["batt_temp"] = int(l.split(":")[1]) / 10
            elif l.startswith("level:"):
                s["level"] = int(l.split(":")[1])
            elif l.startswith("status:"):
                s["status"] = int(l.split(":")[1])
            elif "powered: true" in l:
                s["plugged"] = l.split(" powered")[0]
            elif l.startswith("sys_current_now:"):
                s["current_ua"] = int(l.split(":")[1])
            elif l.startswith("sys_temp:") and "batt_temp" not in s:
                s["batt_temp"] = int(l.split(":")[1]) / 10
        for l in sections["screen"]:
            if l.startswith("brightness:"):
                s["brightness"] = l.split(":")[1].strip()
            m = re.search(r"mWakefulness=(\w+)", l)
            if m:
                s["wake"] = m.group(1)
        for l in sections["foreground"]:
            m = re.search(r"u\d+ ([\w.]+)/", l)
            if m:
                s["fg"] = m.group(1)
        for l in sections["thermal"]:
            m = re.search(r"Thermal Status:\s*(\d+)", l)
            if m:
                s.setdefault("thermal_status", int(m.group(1)))
            m = re.search(r"mValue=([\d.]+).*?mName=([^,}]+)", l)
            if m:
                s["hal"].setdefault(m.group(2).strip(), float(m.group(1)))
        for l in sections["zones"]:
            if "=" in l:
                name, _, v = l.rpartition("=")
                try:
                    v = float(v)
                except ValueError:
                    continue
                if abs(v) > 1000:
                    v /= 1000
                if 0 < v < 150:
                    s["zones"][name] = v
        s["cpu_total"], s["cpu_idle"], s["procs"] = parse_top(sections["top"])
        samples.append(s)
    return samples


def extract(text, start_pat, max_lines=40):
    lines = text.splitlines()
    for i, l in enumerate(lines):
        if re.search(start_pat, l):
            out = [l]
            for x in lines[i + 1:i + 1 + max_lines]:
                if not x.strip():
                    break
                out.append(x)
            return "\n".join(out)
    return "(nao encontrado)"


def main(folder):
    d = Path(folder)
    samples = parse_samples(read(d / "samples.txt"))
    info = read(d / "info.txt")
    bs = read(d / "batterystats.txt")
    out = ["# Resumo do scan de aquecimento", ""]
    out += ["## Aparelho", "```", info.strip(), "```", ""]

    if not samples:
        out.append("**Nenhuma amostra encontrada em samples.txt.**")
    else:
        temps = [s["batt_temp"] for s in samples if "batt_temp" in s]
        hot = [max(s["zones"].values()) for s in samples if s["zones"]]
        out += ["## Temperatura", ""]
        if temps:
            out.append(f"- Bateria: min {min(temps):.1f} °C, media {mean(temps):.1f} °C, "
                       f"max {max(temps):.1f} °C")
        if hot:
            out.append(f"- Sensor mais quente (zonas termicas): max {max(hot):.1f} °C")
        st = [s.get("thermal_status", 0) for s in samples]
        out.append(f"- Pior estado termico do sistema: {max(st)} "
                   f"({THERMAL_STATUS.get(max(st), '?')})")
        plugged = [s for s in samples if s.get("plugged")]
        if plugged:
            out.append(f"- ⚠️ Carregando em {len(plugged)}/{len(samples)} amostras "
                       f"({plugged[0]['plugged']}) — carregar tambem esquenta.")
        out.append("")

        # processos que mais usam CPU
        cpu_sum = defaultdict(float)
        cpu_peak = defaultdict(float)
        for s in samples:
            for name, c in s["procs"]:
                cpu_sum[name] += c
                cpu_peak[name] = max(cpu_peak[name], c)
        ranking = sorted(cpu_sum.items(), key=lambda x: -x[1])[:15]
        ncores = None
        if samples[0]["cpu_total"]:
            ncores = samples[0]["cpu_total"] // 100
        out += ["## Processos que mais usaram CPU",
                f"(% de 1 nucleo; o aparelho tem {ncores or '?'} nucleos, 100% = 1 nucleo cheio)", "",
                "| Processo | CPU media | Pico |", "|---|---:|---:|"]
        for name, tot in ranking:
            out.append(f"| `{name[:70]}` | {tot / len(samples):.1f}% | {cpu_peak[name]:.0f}% |")
        out.append("")

        # correlacao app em primeiro plano x temperatura
        by_fg = defaultdict(list)
        for s in samples:
            by_fg[s.get("fg", "(tela desligada/desconhecido)")].append(s)
        out += ["## App em primeiro plano x temperatura", "",
                "| App | Amostras | Bateria media | Bateria max | CPU usada media |",
                "|---|---:|---:|---:|---:|"]
        rows = []
        for fg, ss in by_fg.items():
            t = [x["batt_temp"] for x in ss if "batt_temp" in x] or [0]
            used = [100 * (x["cpu_total"] - x["cpu_idle"]) / x["cpu_total"]
                    for x in ss if x["cpu_total"]]
            rows.append((mean(t), fg, len(ss), max(t), mean(used) if used else 0))
        for avg, fg, n, mx, u in sorted(rows, reverse=True):
            out.append(f"| `{fg}` | {n} | {avg:.1f} °C | {mx:.1f} °C | {u:.0f}% |")
        out.append("")

        # linha do tempo
        out += ["## Linha do tempo", "",
                "| t (s) | Ligado ha (s) | Bateria | Sensor + quente | Status | CPU usada | Top processo | App na tela |",
                "|---:|---:|---:|---|---:|---:|---|---|"]
        for s in samples:
            hz = max(s["zones"].items(), key=lambda x: x[1]) if s["zones"] else ("-", 0)
            used = (f"{100 * (s['cpu_total'] - s['cpu_idle']) / s['cpu_total']:.0f}%"
                    if s["cpu_total"] else "-")
            top = f"{s['procs'][0][0][:40]} ({s['procs'][0][1]:.0f}%)" if s["procs"] else "-"
            out.append(f"| {s['t']} | {s.get('uptime', '-')} | {s.get('batt_temp', '-')} | {hz[0]} {hz[1]:.1f} | "
                       f"{s.get('thermal_status', '-')} | {used} | `{top}` | {s.get('fg', '-')} |")
        out.append("")

    ct = read(d / "cputime_end.txt")
    if ct:
        out += ["## Processos que mais gastaram CPU desde que o celular ligou",
                "(TIME = tempo total de processador; %CPU = media desde que o processo iniciou)",
                "```", "\n".join(ct.splitlines()[:35]), "```", ""]

    out += ["## Consumo estimado de bateria por app (batterystats)", "```",
            extract(bs, r"Estimated power use", 45), "```", "",
            "## Wakelocks (impedem o celular de dormir)", "```",
            extract(bs, r"All partial wake locks", 20), "```", "",
            "## dumpsys cpuinfo (fim do scan)", "```",
            "\n".join(read(d / "cpuinfo_end.txt").splitlines()[:30]), "```", ""]

    (d / "RESUMO.md").write_text("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
