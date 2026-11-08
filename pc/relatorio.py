"""Conclusoes em portugues claro, nota de saude e o relatorio .md para IA."""
import datetime as dt
import os
import time

import suspeitos as sus

HIGH_PLANS = ("alto desempenho", "high performance", "desempenho máximo", "desempenho maximo", "ultimate")
HEAVY = {"bluestacks": "emulador de Android (BlueStacks)", "hd-player": "emulador de Android (BlueStacks)",
         "steam": "Steam", "obs64": "OBS (gravação)", "wallpaper": "papel de parede animado"}


def gb(b):
    return f"{(b or 0) / 1024 ** 3:.1f} GB"


def mb(b):
    return f"{(b or 0) / 1024 ** 2:.0f} MB"


def fan_app(maker):
    m = (maker or "").lower()
    return ("NitroSense / PredatorSense" if "acer" in m else "Armoury Crate" if "asus" in m else
            "Lenovo Vantage" if "lenovo" in m else "Dell Power Manager" if "dell" in m else
            "OMEN Gaming Hub" if "hp" in m else "MSI Center" if "micro-star" in m or "msi" in m else
            "o app do fabricante")


def findings(live, sysinfo, apps, startup, sec, drives):
    f = []
    add = lambda lvl, title, text, tab=None: f.append({"level": lvl, "title": title, "text": text, "tab": tab})  # noqa: E731
    hist = live.get("hist") or []
    procs = live.get("procs") or []
    sysinfo = sysinfo or {}

    # processador
    if len(hist) >= 15:
        avg = sum(p["cpu"] for p in hist) / len(hist)
        top = [p for p in procs if p["kind"] != "windows" and p["cpu"] >= 5][:3]
        names = ", ".join(f"{p['name']} ({p['cpu']:.0f}%)" for p in top) or "processos do Windows"
        if avg >= 70:
            add("danger", "Processador quase sempre no limite",
                f"Média de {avg:.0f}% nos últimos {len(hist) * 2 // 60 or 1} min. Quem mais pesa agora: {names}. "
                "Processador cheio o tempo todo = calor e ventoinha alta.", "procs")
        elif avg >= 40:
            add("warn", "Processador bem ocupado", f"Média de {avg:.0f}%. Quem mais pesa agora: {names}.", "procs")
        else:
            add("ok", "Processador tranquilo", f"Média de {avg:.0f}% desde que o CalorScan abriu.")
    steady_keys = set()
    for r in live.get("steady") or []:
        if sus.in_windows(r.get("path")):
            continue
        steady_keys.add(r["key"])
        add("warn", f"{r['name']} pesa o tempo todo",
            f"Usou em média {r['avg']}% do processador inteiro durante {live.get('session_min')} min, sem parar. "
            "Se não precisa dele aberto, feche ou tire da inicialização.", "procs")
    for p in procs[:8]:
        if p["kind"] != "windows" and p["cpu"] >= 25 and not p.get("risk") and p["key"] not in steady_keys:
            add("warn", f"{p['name']} está pesando", f"Usando {p['cpu']:.0f}% do processador agora"
                + (f" ({p['count']} processos)" if p["count"] > 1 else "") + ". Feche se não estiver usando.", "procs")
    for p in procs:
        low = (p.get("path") or p["exe"]).lower()
        for k, label in HEAVY.items():
            if k in low and p["cpu"] >= 5:
                add("info", f"{label} aberto", f"Está usando {p['cpu']:.0f}% do processador. Feche quando não usar.", "procs")
                break

    # suspeitos
    bad = [p for p in procs if p.get("risk") in ("alto", "medio")]
    for p in bad[:6]:
        add("danger" if p["risk"] == "alto" else "warn", f"Programa suspeito rodando: {p['name']}",
            "; ".join(p["reasons"]) + ". " + ("Encerre e verifique com o antivírus." if p["risk"] == "alto" else
                                              "Confira se você reconhece este programa."), "procs")

    # temperatura e freio
    t = live.get("temps") or {}
    if t.get("gpu"):
        g = t["gpu"]
        if g >= 85:
            add("danger", "Placa de vídeo muito quente", f"{g} °C. Acima de 85 °C ela se protege e perde desempenho.", "painel")
        elif g >= 75:
            add("warn", "Placa de vídeo quente", f"{g} °C.", "painel")
    if t.get("cpu"):
        c = t["cpu"]
        if c >= 90:
            add("danger", "Temperatura muito alta", f"{c:.0f} °C (sensor do Windows).", "painel")
        elif c >= 80:
            add("warn", "Temperatura alta", f"{c:.0f} °C (sensor do Windows).", "painel")
    else:
        add("info", "Temperatura do processador",
            f"Este PC não informa a temperatura do processador ao Windows sem um programa do fabricante. "
            f"Veja no {fan_app(sysinfo.get('maker'))}. O CalorScan usa o uso do processador e o freio como sinal de calor.")
    if live.get("throttled"):
        add("warn", "Processador sendo freado",
            f"O Windows limitou o desempenho a {live.get('limit')}% por vários minutos. Normalmente é calor "
            "(ventilação entupida, superfície macia) ou economia de energia.", "esfriar")

    # energia
    pw = sysinfo.get("power") or {}
    if any(h in (pw.get("name") or "").lower() for h in HIGH_PLANS):
        add("warn", f"Plano de energia: {pw.get('name')}",
            "Neste plano o processador nunca descansa e esquenta mais. Use o Equilibrado (ele acelera quando precisa).",
            "esfriar")
    if pw and not pw.get("cool"):
        add("info", "Turbo do processador liberado",
            "O 'Modo fresco' (aba Esfriar) desliga o turbo: o PC esquenta bem menos e perde pouco no uso comum.", "esfriar")

    # memoria
    mem = live.get("mem") or {}
    if mem.get("load", 0) >= 90:
        add("danger", "Memória cheia", f"{mem['load']}% em uso. O PC passa a usar o disco no lugar e fica lento.", "procs")
    elif mem.get("load", 0) >= 80:
        add("warn", "Memória quase cheia", f"{mem['load']}% em uso.", "procs")

    up = live.get("uptime") or 0
    if up > 3 * 86400:
        txt = f"Ligado há {up // 86400} dias seguidos."
        if sysinfo.get("fastboot") == 1:
            txt += " Com a 'Inicialização rápida' do Windows, Desligar não zera o sistema: use Reiniciar de vez em quando."
        add("info", "Faz tempo que o PC não reinicia", txt)

    # inicializacao
    st = startup or {}
    on = [i for i in st.get("run", []) if i["enabled"]]
    if len(on) >= 8:
        add("warn", f"{len(on)} programas abrem junto com o Windows",
            "Cada um ocupa processador e memória desde que o PC liga. Desligue os que não precisa.", "inicio")
    for kind, label in (("run", "Abre com o Windows, suspeito"), ("tasks", "Tarefa agendada suspeita"),
                        ("services", "Serviço suspeito")):
        for i in st.get(kind, []):
            if i.get("risk") in ("alto", "medio") and i.get("enabled", True):
                name = i.get("disp") or i.get("name")
                add("danger" if i["risk"] == "alto" else "warn", f"{label}: {name}",
                    "; ".join(i["reasons"]) + ".", "inicio")

    # apps
    for a in [a for a in (apps or []) if a.get("risk") in ("alto", "medio")][:6]:
        add("danger" if a["risk"] == "alto" else "warn", f"App suspeito instalado: {a['name']}",
            "; ".join(a["reasons"]) + ".", "apps")
    recent = [a for a in (apps or []) if a.get("exact") and a.get("days") is not None and a["days"] <= 7]
    if recent:
        add("info", f"{len(recent)} app(s) instalados nesta semana",
            ", ".join(a["name"] for a in recent[:6]) + ". Se o calor começou agora, comece por eles.", "apps")

    # seguranca
    if sec:
        d = sec.get("defender") or {}
        if d and (not d.get("rt") or not d.get("av")):
            others = [a["n"] for a in sec.get("av", []) if "defender" not in a["n"].lower()]
            if others:
                add("info", "Antivírus", f"Usando {', '.join(others)}.", "seg")
            else:
                add("danger", "Proteção em tempo real desligada", "O antivírus do Windows não está vigiando. Ligue em "
                    "Segurança do Windows → Proteção contra vírus e ameaças.", "seg")
        if d.get("sigAge") and d["sigAge"] > 7:
            add("warn", "Antivírus desatualizado", f"Última atualização há {d['sigAge']} dias.", "seg")
        th = sec.get("threats") or []
        if th:
            names = sorted({x.get("name") or "ameaça" for x in th})
            miner = any("coinminer" in n.lower() or "miner" in n.lower() for n in names)
            add("danger", f"O antivírus já encontrou {len(th)} ameaça(s)",
                ", ".join(names[:6]) + (". Há mineradores de criptomoeda: eles usam o processador ao máximo."
                                         if miner else ".") + " Rode a verificação offline.", "seg")
        if sec.get("exclusions"):
            add("warn", "Pastas escondidas do antivírus",
                f"{len(sec['exclusions'])} exclusão(ões) no Windows Defender. Vírus costumam criar exclusões para não "
                "serem achados. Confira se foi você.", "seg")
        fr = sec.get("files") or {}
        if fr.get("ransom_count"):
            newest = fr.get("ransom_newest") or 0
            if newest and time.time() - newest > 365 * 86400:
                add("warn", "Restos de um vírus antigo (STOP/Djvu)",
                    f"{fr['ransom_count']} arquivo(s) de resgate/decodificador, o bilhete mais novo é de "
                    f"{dt.datetime.fromtimestamp(newest):%m/%Y}. Nada novo foi sequestrado desde então: são restos de "
                    "uma infecção antiga, não um vírus ativo. Os arquivos sequestrados dessa época não voltam sem a chave.", "seg")
            else:
                add("danger", "Rastros de vírus que sequestra arquivos",
                    f"{fr['ransom_count']} arquivo(s) de resgate/descriptografia (STOP/Djvu). Esse vírus costuma vir junto "
                    "com ladrões de senha e mineradores. Troque suas senhas usando outro aparelho.", "seg")
        if fr.get("piracy"):
            add("warn", f"{len(fr['piracy'])} arquivo(s)/pasta(s) de programas piratas",
                "Cracks e ativadores são a origem mais comum de mineradores escondidos. "
                + ", ".join(os.path.basename(x["path"]) for x in fr["piracy"][:4]) + ".", "seg")
        if (sec.get("hosts") or {}).get("bad"):
            add("danger", "Arquivo hosts alterado", "Há bloqueios/redirecionamentos de sites de segurança ou bancos.", "seg")
        if any(not p.get("on") for p in sec.get("firewall") or []):
            add("warn", "Firewall desligado em algum perfil", "Ligue em Segurança do Windows → Firewall.", "seg")
    # discos
    for d in drives or []:
        if d["type"] != "Disco interno" or not d["total"]:
            continue
        free = d["free"] / d["total"]
        if free < 0.10:
            add("danger", f"Disco {d['letter']} quase cheio", f"Só {gb(d['free'])} livres ({free * 100:.0f}%).", "discos")
        elif free < 0.20:
            add("warn", f"Disco {d['letter']} ficando cheio", f"{gb(d['free'])} livres ({free * 100:.0f}%).", "discos")
    for d in sysinfo.get("disks") or []:
        if d.get("health") and d["health"] not in ("Healthy", "0"):
            add("danger", f"Disco com problema: {d['name']}", f"O Windows diz: {d['health']}. Faça backup.", "discos")

    order = {"danger": 0, "warn": 1, "info": 2, "ok": 3}
    f.sort(key=lambda x: order[x["level"]])
    return f


def health(fs):
    score = 100 - sum(22 if x["level"] == "danger" else 8 if x["level"] == "warn" else 0 for x in fs)
    score = max(0, min(100, score))
    label = "Saudável" if score >= 80 else "Precisa de atenção" if score >= 55 else "Precisa de cuidado"
    return score, label


def markdown(live, sysinfo, apps, startup, sec, drives, session_top, disk_summary):
    fs = findings(live, sysinfo, apps, startup, sec, drives)
    score, label = health(fs)
    si = sysinfo or {}
    H = sus.hide_user
    hist = live.get("hist") or []
    L = [
        "# Pedido de análise: computador esquentando / processador sempre alto",
        "",
        "Você é um especialista em desempenho, temperatura e segurança de computadores Windows. Abaixo está um "
        "diagnóstico real gerado pelo CalorScan no meu PC. Responda em português, de forma direta e sem jargão:",
        "1. O que mais provavelmente está causando o calor e o processador alto (em ordem de impacto).",
        "2. Se há sinal de vírus, minerador de criptomoeda ou programa indesejado, e o que fazer passo a passo.",
        "3. O que eu devo desinstalar, desligar ou mudar (com o caminho exato no Windows 11).",
        "4. O que é normal e não precisa de preocupação.",
        "Não invente dados que não estão aqui. Se algo for incerto, diga.",
        "",
        f"_Gerado em {dt.datetime.now():%d/%m/%Y %H:%M} · monitorado por {live.get('session_min', 0)} min · "
        f"nota de saúde {score}/100 ({label})_",
        "",
        "## Computador",
        f"- {si.get('maker', '?')} {si.get('model', '')} · BIOS {si.get('bios', '?')}",
        f"- Processador: {si.get('cpu', '?')} ({si.get('cores', '?')} núcleos / {si.get('threads', '?')} threads)",
        f"- Memória: {gb(si.get('ram'))}" + (f" ({' + '.join(str(r['gb']) + ' GB' for r in si.get('ramsticks') or [])})"
                                             if si.get("ramsticks") else ""),
        f"- Vídeo: {', '.join(si.get('gpus') or ['?'])}",
        f"- Windows: {si.get('os', '?')} (build {si.get('build', '?')}), instalado em {str(si.get('installed', '?'))[:10]}",
        f"- Ligado há {(live.get('uptime') or 0) // 3600} h · Inicialização rápida: "
        f"{'ligada' if si.get('fastboot') == 1 else 'desligada' if si.get('fastboot') == 0 else '?'}",
        f"- Plano de energia: {(si.get('power') or {}).get('name', '?')} · processador máx. "
        f"{(si.get('power') or {}).get('max_ac', '?')}% na tomada / {(si.get('power') or {}).get('max_dc', '?')}% na bateria",
        f"- Programa rodando como administrador: {'sim' if si.get('admin') else 'não'}",
    ]
    for d in si.get("disks") or []:
        L.append(f"- Disco físico: {d.get('name')} · {d.get('media')} {d.get('bus')} · {gb(d.get('size'))} · saúde {d.get('health')}")
    L += ["", "## Conclusões automáticas"]
    L += [f"- **[{ {'danger': 'GRAVE', 'warn': 'ATENÇÃO', 'info': 'info', 'ok': 'ok'}[x['level']]}] {x['title']}** — {H(x['text'])}"
          for x in fs]

    if hist:
        cpu = [p["cpu"] for p in hist]
        L += ["", "## Medição ao vivo (amostras a cada 2 s)",
              f"- Processador: média {sum(cpu) / len(cpu):.0f}%, pico {max(cpu):.0f}%, agora {live.get('cpu')}%",
              f"- Memória: {(live.get('mem') or {}).get('load')}% de {gb((live.get('mem') or {}).get('total'))}"]
        if live.get("freq_ghz"):
            L.append(f"- Frequência atual: {live['freq_ghz']} GHz ({live.get('freq_pct')}% da base) · limite de "
                     f"desempenho {live.get('limit')}% {'(FREADO por vários minutos)' if live.get('throttled') else ''}")
        t = live.get("temps") or {}
        nv = live.get("nv") or {}
        if nv:
            fan = nv.get("fan") or ""
            L.append(f"- GPU {nv.get('name')}: {nv.get('temp')} °C, uso {nv.get('util')}%, {nv.get('watts')} W"
                     + (f", ventoinha {fan}%" if fan.replace(".", "").isdigit() else ""))
        L.append(f"- Temperatura do processador: {str(t['cpu']) + ' °C' if t.get('cpu') else 'não informada pelo Windows neste PC'}")
        if live.get("batt") is not None:
            L.append(f"- Bateria {live['batt']}% · {'na tomada' if live.get('ac') else 'na bateria'}")

    if session_top:
        L += ["", f"## Quem mais usou o processador desde que o CalorScan abriu ({live.get('session_min')} min)",
              "| Programa | Executável | Média CPU | CPU (s) | GPU (s) | Memória pico |", "|---|---|---|---|---|---|"]
        L += [f"| {r['name']} | {r['exe']} | {r['avg']}% | {r['cpu_s']} | {r['gpu_s']} | {mb(r['mem_peak'])} |"
              for r in session_top]
    procs = live.get("procs") or []
    if procs:
        L += ["", "## Processos agora (top 20 por processador)", "| Programa | Exe | CPU % | Memória | GPU % | Qtd | Pasta | Alerta |",
              "|---|---|---|---|---|---|---|---|"]
        for p in procs[:20]:
            L.append(f"| {p['name']} | {p['exe']} | {p['cpu']} | {mb(p['mem'])} | {p.get('gpu') or 0} | {p['count']} | "
                     f"{H(os.path.dirname(p.get('path') or '')) or '(protegido)'} | {p.get('risk') or ''} |")
        mem_top = sorted(procs, key=lambda p: -p["mem"])[:10]
        L += ["", "### Mais memória", ", ".join(f"{p['name']} {mb(p['mem'])}" for p in mem_top)]
        susp = [p for p in procs if p.get("risk")]
        if susp:
            L += ["", "### Processos com alerta"]
            L += [f"- **{p['name']}** (`{p['exe']}`, {p.get('risk')}) — {H(p.get('path') or '')} — {'; '.join(p['reasons'])}"
                  + (f" — assinado por {p['signer']}" if p.get("signer") else "") for p in susp]

    st = startup or {}
    if st:
        L += ["", "## Abre junto com o Windows"]
        L += [f"- {'✅' if i['enabled'] else '⛔ (desligado)'} **{i['name']}** — {i.get('company') or 'sem fabricante'} — "
              f"`{H(i['cmd'])[:160]}`" + (f" — ⚠️ {'; '.join(i['reasons'])}" if i.get("reasons") else "")
              for i in st.get("run", [])]
        if st.get("tasks"):
            L += ["", "## Tarefas agendadas (fora da Microsoft ou suspeitas)"]
            L += [f"- {'✅' if t['enabled'] else '⛔'} **{t['path']}{t['name']}** ({t.get('trig') or '?'}) — "
                  f"`{H(t.get('exec', ''))[:160]}`" + (f" — ⚠️ {'; '.join(t['reasons'])}" if t.get("reasons") else "")
                  for t in st["tasks"][:40]]
        if st.get("services"):
            L += ["", "## Serviços de outros fabricantes"]
            L += [f"- {s['disp']} ({s['state']}, {s['mode']}) — {s.get('company') or '?'}"
                  + (f" — ⚠️ {'; '.join(s['reasons'])}" if s.get("reasons") else "") for s in st["services"][:50]]

    if apps:
        L += ["", f"## Apps instalados ({len(apps)})"]
        flagged = [a for a in apps if a.get("risk")]
        if flagged:
            L += ["### Com alerta"] + [f"- **{a['name']}** ({a['publisher'] or 'sem fabricante'}) — {'; '.join(a['reasons'])}"
                                       for a in flagged]
        recent = sorted([a for a in apps if a.get("exact") and a.get("days") is not None and a["days"] <= 30],
                        key=lambda a: a["days"])
        if recent:
            L += ["### Instalados nos últimos 30 dias"] + [f"- {a['name']} — {a['date']} — {a['publisher']}" for a in recent]
        big = sorted([a for a in apps if a.get("size")], key=lambda a: -a["size"])[:10]
        if big:
            L += ["### Maiores"] + [f"- {a['name']} — {gb(a['size'])}" for a in big]
        L += ["### Lista completa", ", ".join(a["name"] for a in apps)]

    if sec:
        d = sec.get("defender") or {}
        L += ["", "## Segurança",
              f"- Windows Defender: modo {d.get('mode', '?')}, antivírus {'ligado' if d.get('av') else 'desligado'}, "
              f"tempo real {'ligado' if d.get('rt') else 'desligado'}, assinaturas de {d.get('sigAge', '?')} dia(s), "
              f"última verificação rápida há {d.get('quickAge', '?')} dia(s), proteção contra violação "
              f"{'ligada' if d.get('tamper') else 'desligada/?'}",
              f"- Outros antivírus: {', '.join(a['n'] for a in sec.get('av') or []) or 'nenhum'}",
              f"- Firewall: " + ", ".join(f"{p['n']} {'ligado' if p['on'] else 'DESLIGADO'}" for p in sec.get("firewall") or []),
              f"- Exclusões do Defender: " + ("precisa de administrador para ver" if sec.get("exclusions_need_admin") else
                                              ", ".join(H(e) for e in sec.get("exclusions") or []) or "nenhuma"),
              f"- Arquivo hosts: {(sec.get('hosts') or {}).get('entries', 0)} entrada(s)"
              + (f" — suspeitas: {'; '.join((sec.get('hosts') or {}).get('bad'))}" if (sec.get('hosts') or {}).get('bad') else ""),
              f"- Programas em uso sem assinatura válida: {len(sec.get('unsigned') or [])} de {sec.get('checked')} verificados"]
        L += [f"  - {H(u['path'])} ({u['st']})" for u in (sec.get("unsigned") or [])[:25]]
        if sec.get("threats"):
            L += ["", "### Histórico de ameaças do antivírus"]
            L += [f"- {x.get('when', '')[:16]} — **{x.get('name') or x.get('id')}** — "
                  f"{'removida' if x.get('ok') else 'NÃO removida'} — {H('; '.join(x.get('res') or []))[:200]}"
                  for x in sec["threats"]]
        fr = sec.get("files") or {}
        if fr.get("piracy") or fr.get("ransom_count"):
            L += ["", "### Arquivos de origem duvidosa"]
            L += [f"- {H(x['path'])} ({x['why']})" for x in fr.get("piracy", [])[:30]]
            if fr.get("ransom_count"):
                L += [f"- {fr['ransom_count']} bilhete(s) de resgate/decodificador do STOP/Djvu, ex.: "
                      + "; ".join(H(x) for x in fr["ransom_notes"][:5])]
    else:
        L += ["", "## Segurança", "- (verificação de segurança ainda não foi feita no programa)"]

    L += ["", "## Discos"]
    for d in drives or []:
        L.append(f"- {d['letter']} {d['label'] or ''} ({d['type']}, {d['fs']}): {gb(d['used'])} usados de {gb(d['total'])}, "
                 f"{gb(d['free'])} livres ({d['free'] / d['total'] * 100 if d['total'] else 0:.0f}%)")
    if disk_summary:
        L.append(f"- Análise de {H(disk_summary['root'])}: " + ", ".join(f"{c['cat']} {gb(c['bytes'])}" for c in disk_summary["cats"][:8]))
        if disk_summary.get("dups"):
            L.append(f"- Duplicados: {disk_summary['dups']['groups']} grupos, {gb(disk_summary['dups']['waste'])} desperdiçados")
    L += ["", "---", "_Relatório gerado pelo CalorScan PC. Caminhos com a pasta do usuário foram trocados por %USERPROFILE%._"]
    return "\n".join(L), fs, score, label
