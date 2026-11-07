"""Transforma uma medicao do scanner em conclusoes simples + resumo .md para IA."""
import re
from pathlib import Path

import analisar

NAMES = {
    "br.com.intermedium": "Inter", "br.com.gabba.Caixa": "Caixa Tem", "br.gov.caixa.fgts.trabalhador": "FGTS",
    "br.com.meliuz": "Méliuz", "br.com.brainweb.ifood": "iFood", "com.instagram.android": "Instagram",
    "com.instagram.barcelona": "Threads", "com.anthropic.claude": "Claude", "com.whatsapp": "WhatsApp",
    "com.shopee.br": "Shopee", "com.Slack": "Slack", "com.spotify.music": "Spotify", "com.ubercab": "Uber",
    "com.nu.production": "Nubank", "com.mercadolibre": "Mercado Livre", "com.picpay": "PicPay",
    "com.google.android.gms": "Serviços do Google", "com.android.vending": "Play Store",
    "com.google.android.as": "Android System Intelligence", "com.google.android.googlequicksearchbox": "Google",
    "com.samsung.android.smartsuggestions": "Sugestões inteligentes (Samsung)",
    "com.samsung.android.bixby.wakeup": "\"Oi, Bixby\" (escuta de voz)", "com.samsung.android.bixby.agent": "Bixby",
    "com.samsung.android.app.routines": "Modos e Rotinas", "com.sec.android.sdhms": "Gerenciador térmico Samsung",
    "com.twitter.android": "X", "com.facebook.katana": "Facebook", "com.zhiliaoapp.musically": "TikTok",
    "com.google.android.youtube": "YouTube", "com.amazon.mShop.android.shopping": "Amazon",
    "com.pixelfederation.ts3.train.station.evolution.building.manager.tycoon.simulator": "TrainStation 3",
    "com.lemon.lvoverseas": "CapCut", "system_server": "Sistema Android", "[kswapd0]": "Liberação de memória",
    "surfaceflinger": "Desenho da tela", "com.sec.android.app.launcher": "Tela inicial",
    "com.android.systemui": "Barra e notificações", "com.touchtype.swiftkey": "Teclado SwiftKey",
    "com.samsung.android.honeyboard": "Teclado Samsung", "com.google.android.inputmethod.latin": "Gboard",
}
SYSTEM = re.compile(r"^(system_server|surfaceflinger|zygote|installd|logd|lmkd|statsd|init|keystore|ueventd|"
                    r"android\.|vendor\.|audioserver|cameraserver|media|\[|com\.android\.(systemui|phone|"
                    r"providers)|android\.process)")
PROFILE = {"0": "principal", "12": "perfil de trabalho", "150": "Pasta Segura"}
THERMAL = {0: "normal", 1: "leve", 2: "moderado", 3: "severo", 4: "crítico", 5: "emergência", 6: "desligando"}


def nice(pkg: str) -> str:
    base = pkg.split(":")[0]
    return NAMES.get(base) or NAMES.get(pkg) or base.split(".")[-1].replace("_", " ").capitalize()


def kind(pkg: str, user: str = "u0_a") -> str:
    if pkg in ("com.sec.android.app.launcher", "com.touchtype.swiftkey", "com.samsung.android.honeyboard"):
        return "sistema"
    if "." in pkg and pkg.startswith(("com.samsung.", "com.sec.")):
        return "samsung"
    if "." in pkg and pkg.startswith(("com.google.", "com.android.vending")):
        return "google"
    if "." not in pkg or SYSTEM.match(pkg) or pkg.startswith("com.android.") or not re.match(r"u\d+_a", user):
        return "sistema"
    return "app"


def secs(t: str) -> int:
    parts = [int(x) for x in t.split(":")]
    while len(parts) < 3:
        parts.insert(0, 0)
    return parts[0] * 3600 + parts[1] * 60 + parts[2]


def parse_ps(text: str):
    """Linhas do ps com TIME acumulado. Retorna (uptime, [ {user, profile, secs, name} ])."""
    up = re.search(r"uptime (\d+)s", text)
    rows = []
    for line in text.splitlines():
        p = line.split()
        if len(p) < 6 or not re.fullmatch(r"\d+", p[0]) or not re.fullmatch(r"[\d:]+", p[3]):
            continue
        user, name = p[2], p[5]
        m = re.match(r"u(\d+)_a\d+", user)
        rows.append({"user": user, "profile": m.group(1) if m else "0", "secs": secs(p[3]), "name": name})
    return (int(up.group(1)) if up else None), rows


def analyze(folder):
    d = Path(folder)
    samples = analisar.parse_samples(analisar.read(d / "samples.txt"))
    samples.sort(key=lambda s: s.get("uptime", s["t"]))
    info = analisar.read(d / "info.txt")
    model = (re.search(r"ro.product.model: (.+)", info) or [None, "Android"])[1].strip()
    android = (re.search(r"ro.build.version.release: (.+)", info) or [None, "?"])[1].strip()
    boot_mode = "boot_mode: 1" in info or (samples and samples[0].get("uptime", 9999) < 300)

    temps = [(s.get("uptime"), s["batt_temp"]) for s in samples if "batt_temp" in s]
    peak = max(temps, key=lambda x: x[1]) if temps else (None, None)
    first = temps[0] if temps else (None, None)
    last = temps[-1] if temps else (None, None)
    severe = next((s.get("uptime") for s in samples if s.get("thermal_status", 0) >= 3), None)
    worst = max([s.get("thermal_status", 0) for s in samples] or [0])
    charging = sum(1 for s in samples if s.get("plugged")) / max(len(samples), 1)

    up0, start_rows = parse_ps(analisar.read(d / "cputime_start.txt"))
    _, end_rows = parse_ps(analisar.read(d / "cputime_end.txt"))
    rows = start_rows or end_rows

    # agrupa por app (somando perfis e subprocessos)
    groups = {}
    for r in rows:
        if r["name"].startswith("sh") or r["name"] in ("ps", "top", "dumpsys"):
            continue
        key = r["name"].split(":")[0]
        g = groups.setdefault(key, {"name": nice(key), "pkg": key, "kind": kind(key, r["user"]), "secs": 0,
                                    "profiles": set()})
        g["secs"] += r["secs"]
        g["profiles"].add(r["profile"])
    top = sorted(groups.values(), key=lambda g: -g["secs"])
    total = sum(g["secs"] for g in top) or 1
    by_kind = {}
    for g in top:
        by_kind[g["kind"]] = by_kind.get(g["kind"], 0) + g["secs"]
    profiles = sorted({r["profile"] for r in rows if r["profile"] != "0"})
    google_profiles = len(groups.get("com.google.android.gms", {}).get("profiles", []))
    kswapd = groups.get("[kswapd0]", {}).get("secs", 0)
    fg = {s.get("fg") for s in samples[:6] if s.get("fg")}
    autostart = [g for g in top if g["kind"] == "app" and g["secs"] >= 3 and g["pkg"] not in fg][:6]

    findings = []
    if peak[1]:
        when = f", {peak[0] // 60} min {peak[0] % 60} s depois de ligar" if boot_mode and peak[0] else ""
        level = "danger" if peak[1] >= 43 else "warn" if peak[1] >= 40 else "ok"
        findings.append({"level": level, "title": f"Pico de {peak[1]:.1f} °C{when}",
                         "text": f"Começou em {first[1]:.1f} °C e terminou em {last[1]:.1f} °C. "
                                 f"Pior estado do sistema: {THERMAL.get(worst, worst)}"
                                 + (f" (a partir de {severe} s ligado)." if severe and boot_mode else ".")})
    if boot_mode and up0 and up0 < 900 and rows:
        cores = len(re.findall(r"^cpu\d+:", info, re.M)) or 8
        busy = total / (up0 * cores) * 100
        findings.append({"level": "danger" if busy > 50 else "warn",
                         "title": f"Nos primeiros {up0 // 60} min o processador trabalhou {busy:.0f}% do máximo",
                         "text": "Tudo iniciando ao mesmo tempo. Divisão: "
                                 + ", ".join(f"{label} {by_kind.get(k, 0) * 100 // total}%"
                                             for k, label in [("sistema", "sistema Android"), ("google", "Google"),
                                                              ("app", "apps"), ("samsung", "Samsung")]) + "."})
    if profiles:
        names = ", ".join(PROFILE.get(p, f"perfil {p}") for p in profiles)
        findings.append({"level": "warn", "title": f"O celular liga {len(profiles) + 1} perfis juntos",
                         "text": f"Além do principal: {names}. Cada perfil inicia sua própria cópia dos serviços do "
                                 f"Google" + (f" (aqui, {google_profiles} cópias)" if google_profiles > 1 else "")
                                 + ". Pausar o perfil de trabalho quando não usa reduz isso."})
    if autostart and boot_mode:
        findings.append({"level": "warn", "title": "Apps que iniciaram sozinhos",
                         "text": ", ".join(f"{g['name']} ({g['secs']} s)" for g in autostart)
                                 + ". Restrinja o segundo plano dos que não precisam de notificação na hora."})
    if kswapd >= 20:
        findings.append({"level": "warn", "title": "Memória cheia",
                         "text": f"O sistema gastou {kswapd} s só liberando memória: muita coisa aberta ao mesmo tempo."})
    sams = [g for g in top if g["kind"] == "samsung" and g["secs"] >= 4]
    if sams:
        findings.append({"level": "info", "title": "Samsung e IA",
                         "text": ", ".join(f"{g['name']} ({g['secs']} s)" for g in sams[:4])
                                 + ". Pesam menos que os itens acima."})
    if charging > 0.3:
        findings.append({"level": "info", "title": "Estava carregando",
                         "text": f"Carregando em {charging * 100:.0f}% da medição. Carregar também esquenta; "
                                 "para comparar, meça sem o cabo."})

    timeline = [{"up": t[0], "c": t[1]} for t in temps]
    top_list = [{"name": g["name"], "secs": g["secs"], "kind": g["kind"],
                 "profiles": len(g["profiles"])} for g in top[:10]]
    md = markdown(model, android, boot_mode, findings, top_list, timeline)
    return {"model": model, "android": android, "boot": bool(boot_mode), "folder": str(d),
            "peak": peak[1], "findings": findings, "top": top_list, "timeline": timeline,
            "uptime_start": up0, "markdown": md}


def markdown(model, android, boot, findings, top, timeline):
    lines = [
        "# Pedido de análise: celular esquentando",
        "",
        "Você é um especialista em desempenho e temperatura de celulares Android. Abaixo está uma medição "
        "real feita no meu celular. Responda em português, de forma direta e sem jargão:",
        "1. O que mais provavelmente está causando o aquecimento (em ordem de impacto).",
        "2. O que eu devo mudar, com o caminho exato no menu do celular (One UI se for Samsung).",
        "3. O que é normal e não precisa de preocupação.",
        "Não invente dados que não estão aqui. Se algo for incerto, diga.",
        "",
        f"## Aparelho\n- {model}, Android {android}\n- Medição {'desde o boot' if boot else 'durante o uso'}",
        "",
        "## Conclusões automáticas",
    ]
    lines += [f"- **{f['title']}** — {f['text']}" for f in findings]
    lines += ["", "## Quem mais usou o processador (segundos de CPU)", "| Processo | Tipo | CPU (s) | Perfis |",
              "|---|---|---:|---:|"]
    lines += [f"| {t['name']} | {t['kind']} | {t['secs']} | {t['profiles']} |" for t in top]
    step = max(1, len(timeline) // 30)
    lines += ["", "## Temperatura da bateria (segundos desde que ligou → °C)",
              ", ".join(f"{p['up']}s→{p['c']}" for p in timeline[::step])]
    return "\n".join(lines) + "\n"
