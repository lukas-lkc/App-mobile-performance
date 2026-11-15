/* CalorScan PC — temas Claro, Escuro e Ao vivo (o mesmo ceu de blocos do app do celular).
 *
 * Claro = 10:30 de um dia bonito. Escuro = 22:30 de noite limpa, com a lua na fase de verdade.
 * Trocar entre eles passa pelo por (ou nascer) do sol em ~2,6 s.
 * Ao vivo = hora de verdade + tempo da cidade (nuvens, chuva, raios, vento), mudando devagar.
 *
 * Peso: so a faixa de cima (canvas) se mexe, a 8-15 quadros/s e so quando ha algo andando. Para sozinho com a
 * janela minimizada, com "reduzir movimento" do Windows e quando o computador esta pesado/quente.
 */
(() => {
  const html = document.documentElement;
  const cv = document.getElementById("sky"), cx = cv.getContext("2d");
  const BAND = 230;                 // altura da faixa do ceu (px)
  const U = 5;                      // tamanho do bloco
  const SYN = 29.530588853, NEW_MOON = Date.UTC(2000, 0, 6, 18, 14);
  const reduce = matchMedia("(prefers-reduced-motion: reduce)");

  let mode = (typeof THEME === "string" && ["light", "dark", "live"].includes(THEME) ? THEME : "light"), prefs = {}, wx = null;
  let target = null, cur = null, anim = null, timer = 0, lastStep = 0, flashUntil = 0, nextFlash = 0, seed = 99;
  const t0 = performance.now();

  /* ---------------- cena (igual ao Sky.scene do app) ---------------- */
  const hx = c => [(c >> 16) & 255, (c >> 8) & 255, c & 255];
  const DAY = [0x4FB3F4, 0x8ED4FA, 0xC6EFFF].map(hx), GOLD = [0x3E4C9E, 0xE7789A, 0xFFA45C].map(hx);
  const NIGHT = [0x070C24, 0x111A42, 0x26305E].map(hx), GREY_DAY = [0x5E6779, 0x7D8596, 0x9AA2B2].map(hx);
  const GREY_NIGHT = [0x0E1220, 0x181C2C, 0x262B3B].map(hx);
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const lerpC = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * clamp(t, 0, 1)));
  const mix = (A, B, t) => A.map((a, i) => lerpC(a, B[i], t));
  const rgb = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;

  const moonAge = t => { const d = (t - NEW_MOON) / 864e5; return d - Math.floor(d / SYN) * SYN; };
  const moonLit = a => (1 - Math.cos(2 * Math.PI * a / SYN)) / 2;
  const moonTransit = a => (12 + 24 * a / SYN) % 24;
  const phaseName = a => a < 1.5 || a > 28 ? "lua nova" : a < 6.4 ? "lua crescente" : a < 8.4 ? "quarto crescente" :
    a < 13.8 ? "gibosa crescente" : a < 15.8 ? "lua cheia" : a < 21.1 ? "gibosa minguante" : a < 23.1 ? "quarto minguante" : "lua minguante";
  const dayStart = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); };

  function rainOf(w) {
    const c = w.code;
    if ((c >= 95 && c <= 99) || c === 65 || c === 82 || w.precip >= 7.6) return 1;
    if (c === 63 || c === 81 || w.precip >= 2.5) return 0.65;
    if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82) || w.precip >= 0.2) return 0.35;
    return 0;
  }

  function scene(w, now) {
    const rise = w.rise, set = w.set, isDay = now >= rise && now <= set;
    const toEvent = Math.min(Math.abs(now - rise), Math.abs(now - set)) / 6e4;
    const golden = clamp(1 - toEvent / 60, 0, 1);
    const base = isDay ? mix(DAY, GOLD, golden) : mix(NIGHT, GOLD, golden * 0.8);
    const rain = rainOf(w), thunder = w.code >= 95 && w.code <= 99;
    const k = clamp(w.cloud / 100 * 0.45 + rain * 0.45 + (thunder ? 0.15 : 0), 0, 0.9);
    const sky = mix(base, isDay ? GREY_DAY : GREY_NIGHT, k);
    const nightish = !isDay && golden < 0.35;
    let sun = null;
    if (isDay) { const p = (now - rise) / (set - rise); sun = [0.08 + 0.84 * p, 1 - Math.sin(Math.PI * p)]; }
    const age = moonAge(now), dn = new Date(now);
    let h = dn.getHours() + dn.getMinutes() / 60 - moonTransit(age); if (h > 12) h -= 24; if (h < -12) h += 24;
    const moon = Math.abs(h) < 6 && moonLit(age) > 0.04 ? (p => [0.08 + 0.84 * p, 1 - Math.sin(Math.PI * p)])((h + 6) / 12) : null;
    const clouds = Math.min(4, rain > 0 || thunder ? Math.max(3, Math.floor(w.cloud / 22)) : Math.floor(w.cloud / 22));
    let label = golden > 0.3 && Math.abs(now - rise) < 36e5 ? "nascer do sol" : golden > 0.3 ? "pôr do sol" : isDay ? "dia" : "noite";
    if (thunder) label += " · tempestade"; else if (rain >= 0.65) label += " · chuva forte";
    else if (rain > 0) label += " · chuva"; else if (w.cloud > 70) label += " · nublado";
    if (w.wind >= 28) label += " · vento";
    if (!isDay || moon) label += " · " + phaseName(age);
    return { sky, darkUi: nightish || k > 0.62, sun, golden, moon, moonAge: age, moonDay: isDay,
      stars: nightish && w.cloud < 60, clouds, cloudDark: clamp(rain * 0.8 + (thunder ? 0.2 : 0), 0, 1) + (isDay ? 0 : 0.3),
      rain, thunder, wind: w.wind >= 28, fog: w.code === 45 || w.code === 48, label,
      veil: clamp(w.cloud / 100 * 0.8 + rain * 0.3, 0, 0.92) };
  }

  /* hora "de mentira" v (horas desde hoje 00:00): usada no Claro, no Escuro e na animacao entre eles */
  function virtual(v) {
    const hod = ((v % 24) + 24) % 24, base = dayStart() + Math.floor(v / 24) * 864e5;
    const cloud = 0;   // no PC o Claro/Escuro ficam parados (as nuvens de blocos do Claro sao do fundo, sem animacao)
    const s = scene({ code: cloud ? 2 : 0, cloud, precip: 0, wind: 0, rise: base + 6 * 36e5, set: base + 18 * 36e5 },
      dayStart() + v * 36e5);
    let moon = null;
    // no PC a lua do Escuro fica no meio e no alto: a direita do topo e do quadro do processador
    if (hod >= 18) { const p = clamp((hod - 18) / 4.5, 0, 1); moon = [0.94 - 0.42 * p, 1 - p]; }
    else if (hod <= 5.5) { const p = hod / 5.5; moon = [0.52 - 0.44 * p, p]; }
    return { ...s, moon, moonDay: false, stars: hod >= 18.8 || hod <= 5.2, label: s.sun ? "dia" : "noite · " + phaseName(s.moonAge) };
  }
  const DAY_H = 10.5, NIGHT_H = 22.5;

  function liveScene() {
    const now = Date.now(), ds = dayStart();
    const w = wx && !wx.old ? wx : null;
    const base = { code: 0, cloud: 0, precip: 0, wind: 0 };
    const rs = wx ? { rise: ds + wx.rise * 6e4, set: ds + wx.set * 6e4 } : { rise: ds + 6 * 36e5, set: ds + 18 * 36e5 };
    return scene({ ...base, ...(w || {}), ...rs }, now);
  }

  /* ---------------- estado atual (escorrega ate a cena) ---------------- */
  const from = (s, o) => ({
    sky: s.sky.map(c => c.slice()), sunX: s.sun ? s.sun[0] : o ? o.sunX : 0.9, sunY: s.sun ? s.sun[1] : o ? o.sunY : 1,
    sunA: s.sun ? 1 : 0, golden: s.golden, moonX: s.moon ? s.moon[0] : o ? o.moonX : 0.1, moonY: s.moon ? s.moon[1] : o ? o.moonY : 1,
    moonA: s.moon ? 1 : 0, moonAge: s.moonAge, moonDay: s.moonDay, stars: s.stars ? 1 : 0, clouds: s.clouds,
    cloudDark: s.cloudDark, rain: s.rain, wind: s.wind ? 1 : 0, fog: s.fog ? 1 : 0, thunder: s.thunder, veil: s.veil,
  });

  function allowed() {
    return !document.hidden && !reduce.matches && !(window.skyLoad >= 41.5);  // PC pesado/quente: ceu parado
  }
  function settling() {
    if (!cur || !target) return false;
    const s = target;
    return cur.sky.some((c, i) => c.some((v, j) => v !== s.sky[i][j])) || Math.abs(cur.clouds - s.clouds) > 0.02 ||
      Math.abs(cur.rain - s.rain) > 0.02 || Math.abs(cur.sunA - (s.sun ? 1 : 0)) > 0.02 || Math.abs(cur.veil - s.veil) > 0.02;
  }
  const moving = () => !!anim || (allowed() && (settling() || cur.clouds > 0.05 || cur.rain > 0.02 || cur.thunder || cur.wind > 0.05));
  const frameMs = () => anim ? 33 : cur && (cur.rain > 0.02 || cur.thunder || cur.wind > 0.05) ? 66 : 125;

  function step(now) {
    const dt = lastStep ? Math.min(500, now - lastStep) : 0; lastStep = now;
    if (!dt || !allowed() || anim) return;
    const k = 1 - Math.exp(-dt / 1200), s = target, c = cur;
    const f = (a, b) => Math.abs(b - a) < 0.01 ? b : a + (b - a) * k;
    c.sky = c.sky.map((col, i) => col.map((v, j) => { const d = s.sky[i][j] - v; return Math.abs(d) <= 2 ? s.sky[i][j] : v + Math.round(d * k); }));
    if (s.sun) { c.sunX = f(c.sunX, s.sun[0]); c.sunY = f(c.sunY, s.sun[1]); }
    c.sunA = f(c.sunA, s.sun ? 1 : 0); c.golden = f(c.golden, s.golden);
    if (s.moon) { c.moonX = f(c.moonX, s.moon[0]); c.moonY = f(c.moonY, s.moon[1]); }
    c.moonA = f(c.moonA, s.moon ? 1 : 0); c.moonAge = s.moonAge; c.moonDay = s.moonDay;
    c.stars = f(c.stars, s.stars ? 1 : 0); c.clouds = f(c.clouds, s.clouds); c.cloudDark = f(c.cloudDark, s.cloudDark);
    c.rain = f(c.rain, s.rain); c.wind = f(c.wind, s.wind ? 1 : 0); c.fog = f(c.fog, s.fog ? 1 : 0); c.thunder = s.thunder;
    c.veil = f(c.veil, s.veil);
  }

  /* ---------------- desenho ---------------- */
  let lastBg = "";
  function paintBg() {
    const k = cur.sky.join("|");
    if (k === lastBg) return; lastBg = k;
    html.style.setProperty("--skyA", rgb(cur.sky[0])); html.style.setProperty("--skyB", rgb(cur.sky[1]));
    html.style.setProperty("--skyC", rgb(cur.sky[2]));
  }
  function size() {
    const r = window.devicePixelRatio || 1, w = Math.round(innerWidth * r), h = Math.round(BAND * r);
    if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
    cx.setTransform(r, 0, 0, r, 0, 0);
  }
  const px = (ox, oy, x, y, col, u = U) => { cx.fillStyle = col; cx.fillRect(ox + x * u, oy + y * u, u, u); };

  function drawSun(x, y, golden, a) {
    const core = rgb(lerpC([255, 210, 63], [255, 138, 61], golden), a), rim = rgb(lerpC([224, 168, 0], [224, 85, 43], golden), a);
    const shine = rgb([255, 244, 194], a), ox = x - 6 * U, oy = y - 6 * U;
    for (let j = 4; j <= 7; j++) for (let i = 4; i <= 7; i++) px(ox, oy, i, j, core);
    for (let i = 4; i <= 7; i++) { px(ox, oy, i, 3, rim); px(ox, oy, i, 8, rim); px(ox, oy, 3, i, rim); px(ox, oy, 8, i, rim); }
    px(ox, oy, 4, 4, shine); px(ox, oy, 5, 4, shine);
    if (golden < 0.8) {
      for (let i = 5; i <= 6; i++) { px(ox, oy, i, 0, core); px(ox, oy, i, 11, core); px(ox, oy, 0, i, core); px(ox, oy, 11, i, core); }
      [[1, 1], [10, 1], [1, 10], [10, 10]].forEach(([i, j]) => px(ox, oy, i, j, rim));
    }
  }
  /* lua com a fase de verdade (lado direito acende na crescente, como no app) */
  function drawMoon(x, y, age, a) {
    const phi = 2 * Math.PI * age / SYN, lit = `rgba(244,231,181,${a})`, shade = `rgba(201,210,242,${a * 0.28})`;
    const ox = x - 5.5 * U, oy = y - 5.5 * U;
    for (let j = 0; j <= 10; j++) for (let i = 0; i <= 10; i++) {
      const xn = (i - 5) / 5.2, yn = (j - 5) / 5.2;
      if (xn * xn + yn * yn > 1) continue;
      const edge = Math.cos(phi) * Math.sqrt(1 - yn * yn);
      px(ox, oy, i, j, (phi < Math.PI ? xn > edge : xn < -edge) ? lit : shade);
    }
    if (age >= 11 && age <= 18) { px(ox, oy, 3, 4, `rgba(220,205,146,${a})`); px(ox, oy, 6, 7, `rgba(220,205,146,${a})`); }
  }
  const CLOUD = ["...XXX......", "..XXXXX.XX..", ".XXXXXXXXXX.", "XXXXXXXXXXXX", ".XXXXXXXXXX."];
  const SPOTS = [[0.58, 0.12], [0.18, 0.30], [0.80, 0.36], [0.38, 0.06]];
  function drawCloud(x, y, u, col, shadow) {
    cx.fillStyle = col;
    CLOUD.forEach((line, row) => { let c = 0;
      while (c < line.length) { if (line[c] !== "X") { c++; continue; } const s = c; while (c < line.length && line[c] === "X") c++;
        cx.fillRect(x + s * u, y + row * u, (c - s) * u, u); } });
    cx.fillStyle = shadow; cx.fillRect(x + u, y + CLOUD.length * u, (CLOUD[0].length - 2) * u, 0.6 * u);
  }
  const STARS = [[.06, .10, 1], [.18, .42, .7], [.31, .18, .7], [.44, .50, 1], [.52, .12, .7], [.66, .36, .7], [.74, .08, 1],
    [.88, .48, .7], [.94, .20, .7], [.12, .62, .7], [.38, .70, .7], [.80, .66, .7], [.24, .08, .7], [.60, .58, 1]];
  const DROPS = Array.from({ length: 120 }, (_, i) => [((i * 0.6180339) % 1), ((i * 0.3819660 * 7) % 1)]);

  function draw() {
    const now = performance.now();
    step(now);
    paintBg();
    size();
    const w = innerWidth, h = BAND, c = cur, t = (now - t0) / 1000;
    cx.clearRect(0, 0, w, h);
    const top = h * 0.16, hor = h * 0.94, arcY = f => top + (hor - top) * f;
    if (c.stars > 0.02) {
      cx.fillStyle = `rgba(255,255,255,${0.8 * c.stars})`;
      STARS.forEach(([fx, fy, b]) => cx.fillRect(fx * w, fy * h * 0.9, U * b, U * b));
    }
    const clear = 1 - c.veil;
    if (c.moonA * clear > 0.02) drawMoon(c.moonX * w, arcY(c.moonY), c.moonAge, (c.moonDay ? 0.6 : 1) * c.moonA * clear);
    if (c.sunA * clear > 0.02) drawSun(c.sunX * w, arcY(c.sunY), c.golden, c.sunA * clear);
    // nuvens andando para a direita (mais rapido com vento)
    const cc = lerpC([255, 255, 255], [58, 65, 86], clamp(c.cloudDark, 0, 1)), speed = 6 + 22 * c.wind, cw = 12 * U;
    SPOTS.forEach(([fx, fy], i) => {
      const a = clamp(c.clouds - i, 0, 1); if (a <= 0.02) return;
      const sc = i % 2 === 0 ? 1.15 : 0.9, span = w + cw * sc, x = ((fx * w + t * speed * (0.7 + 0.15 * i)) % span) - cw * sc, y = fy * h;
      drawCloud(x, y, U * sc, rgb(cc, a), c.cloudDark > 0.5 ? `rgba(38,43,60,${a})` : `rgba(0,0,0,${0.2 * a})`);
      if (c.thunder && i === 1 && now < flashUntil)
        [[2, 0], [1, 1], [0, 2], [1, 2], [2, 2], [1, 3], [0, 4]].forEach(([bx, by]) => px(x + 3 * U * sc, y + 5 * U * sc, bx, by, "#ffd23f"));
    });
    if (c.wind > 0.05) {
      cx.fillStyle = `rgba(255,255,255,${0.6 * c.wind})`;
      [[0.18, 0.52], [0.55, 0.42], [0.70, 0.72]].forEach(([fx, fy], i) => {
        const x = ((fx * w + t * (40 + 10 * i)) % (w + 12 * U)) - 12 * U, y = fy * h * 0.8;
        cx.fillRect(x, y, 9 * U, U * 0.8); cx.fillRect(x + 9 * U, y - U, U, U); cx.fillRect(x + 2 * U, y + 2 * U, 5 * U, U * 0.8);
      });
    }
    if (c.rain > 0.02) {   // gotas caindo das nuvens, so na faixa do ceu
      cx.fillStyle = c.cloudDark > 0.5 ? `rgba(159,195,240,${0.7 * Math.min(1, c.rain * 3)})` : `rgba(221,235,255,${0.7 * Math.min(1, c.rain * 3)})`;
      const n = Math.round(70 * c.rain) + 16, y0 = h * 0.4, range = h - y0, fall = 180 + 260 * c.rain;
      for (let i = 0; i < n; i++) {
        const [fx, fy] = DROPS[i % DROPS.length], y = y0 + ((fy * range + t * fall * (0.85 + 0.3 * fx)) % range);
        cx.fillRect((fx * w - (y - y0) * 0.18 + w) % w, y, U * 0.6, U * 1.8);
      }
      // a faixa some embaixo, sem corte seco
    }
    if (c.thunder && allowed()) {   // clarao de vez em quando (a cada 4-12 s)
      if (now >= nextFlash) { seed = (seed * 1103515245 + 12345) & 0x7fffffff; flashUntil = now + 140 + seed % 120; nextFlash = now + 4000 + seed % 8000; }
      if (now < flashUntil) { cx.fillStyle = "rgba(255,255,255,.33)"; cx.fillRect(0, 0, w, h); }
    }
    if (c.fog > 0.02) { cx.fillStyle = `rgba(255,255,255,${0.25 * c.fog})`; cx.fillRect(0, h * 0.4, w, h * 0.6); }
    // some aos poucos embaixo, para a faixa nao ter emenda com o resto da janela
    cx.globalCompositeOperation = "destination-out";
    const g = cx.createLinearGradient(0, h * 0.7, 0, h); g.addColorStop(0, "rgba(0,0,0,0)"); g.addColorStop(1, "rgba(0,0,0,1)");
    cx.fillStyle = g; cx.fillRect(0, h * 0.7, w, h * 0.3);
    cx.globalCompositeOperation = "source-over";
    schedule();
  }
  function schedule() {
    clearTimeout(timer);
    timer = moving() ? setTimeout(() => { timer = 0; requestAnimationFrame(draw); }, frameMs()) : 0;
  }
  const kick = () => { clearTimeout(timer); timer = 0; requestAnimationFrame(draw); };

  /* cores dos cartoes: escorregam junto (classe "swap" so durante a troca) */
  function setDark(d, smooth) {
    if (html.classList.contains("dark") === d) return;
    if (smooth && !reduce.matches) { html.classList.add("swap"); clearTimeout(setDark._t); setDark._t = setTimeout(() => html.classList.remove("swap"), 900); }
    html.classList.toggle("dark", d);
  }

  function show(s, smooth) {
    target = s;
    if (!cur || !smooth || !allowed()) { cur = from(s, cur); lastStep = 0; }
    kick();
  }

  /* claro <-> escuro: o sol se poe (ou nasce) em ~2,6 s */
  function animateTo(toDark) {
    if (reduce.matches || document.hidden) { show(toDark ? virtual(NIGHT_H) : virtual(DAY_H), false); setDark(toDark, true); return; }
    // horas-chave do caminho: passa rapido pelo dia/noite e devagar pelo por (ou nascer) do sol, para dar para ver
    const K = toDark ? [DAY_H, 16.8, 19.6, NIGHT_H] : [NIGHT_H, 28.6, 31.2, DAY_H + 24];
    const t1 = performance.now(), dur = 3000, swapAt = toDark ? 18.7 : 29.6;
    const hourAt = e => e < 0.2 ? K[0] + (K[1] - K[0]) * e / 0.2 : e < 0.8 ? K[1] + (K[2] - K[1]) * (e - 0.2) / 0.6 : K[2] + (K[3] - K[2]) * (e - 0.8) / 0.2;
    let swapped = false;
    anim = true;
    const tick = () => {
      const p = clamp((performance.now() - t1) / dur, 0, 1), e = p < .5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2, v = hourAt(e);
      const s = virtual(v); cur = from(s, cur); target = s;
      if (!swapped && (toDark ? v >= swapAt : v >= swapAt)) { swapped = true; setDark(toDark, true); }
      if (p < 1) { draw(); clearTimeout(timer); requestAnimationFrame(tick); }
      else { anim = null; if (!swapped) setDark(toDark, true); show(toDark ? virtual(NIGHT_H) : virtual(DAY_H), false); }
    };
    requestAnimationFrame(tick);
  }

  /* ---------------- ao vivo: tempo e relogio ---------------- */
  let liveTimer = 0, lastFetch = 0;
  async function fetchSky() {
    try { const j = await (await fetch("/api/sky")).json(); prefs = j; wx = j.wx; lastFetch = Date.now(); } catch { /* sem servidor: so o relogio */ }
  }
  async function liveTick(first) {
    if (mode !== "live") return;
    if (first || Date.now() - lastFetch > 5 * 6e4) {
      await fetchSky();
      // a 1a consulta do tempo leva uns segundos (localizacao do Windows + internet): olha de novo logo
      if (first && prefs.use_location && !wx) setTimeout(() => { lastFetch = 0; liveTick(false); }, 8000);
      if (!first && prefs.use_location && prefs.loc_state === "off" && !liveTick._warned) {
        liveTick._warned = true; toast("A localização do Windows está desligada: o céu segue só o horário.");
      }
    }
    if (mode !== "live") return;
    const s = liveScene();
    show(s, !first);
    setDark(s.darkUi, !first);
    renderIcon();
  }
  function startLive() { clearInterval(liveTimer); liveTimer = setInterval(() => liveTick(false), 60000); liveTick(true); }

  /* ---------------- botao e escolha do tema ---------------- */
  const R = (x, y, w, h, f) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${f}"/>`;
  const ICON = {
    light: () => R(6, 1, 4, 2, "#ffd23f") + R(6, 13, 4, 2, "#ffd23f") + R(1, 6, 2, 4, "#ffd23f") + R(13, 6, 2, 4, "#ffd23f") +
      R(4, 4, 8, 8, "#e0a800") + R(5, 5, 6, 6, "#ffd23f") + R(5, 5, 2, 1, "#fff4c2") + R(2, 2, 2, 2, "#e0a800") + R(12, 2, 2, 2, "#e0a800") +
      R(2, 12, 2, 2, "#e0a800") + R(12, 12, 2, 2, "#e0a800"),
    dark: () => R(5, 2, 6, 1, "#e9d98f") + R(3, 3, 8, 2, "#f4e7b5") + R(2, 5, 6, 6, "#f4e7b5") + R(3, 11, 9, 2, "#f4e7b5") + R(5, 13, 6, 1, "#e9d98f") +
      R(11, 10, 2, 1, "#f4e7b5") + R(4, 6, 2, 2, "#dccd92") + R(6, 10, 1, 1, "#dccd92") + R(13, 3, 1, 1, "#fff") + R(14, 7, 1, 1, "#fff"),
    live: () => R(8, 1, 3, 1, "#ffd23f") + R(13, 4, 2, 3, "#ffd23f") + R(7, 3, 6, 6, "#e0a800") + R(8, 4, 4, 4, "#ffd23f") +
      R(3, 8, 4, 2, "#ffffff") + R(1, 10, 10, 4, "#ffffff") + R(7, 7, 4, 3, "#ffffff") + R(2, 14, 8, 1, "#9aa3ad"),
  };
  const svg = k => `<svg viewBox="0 0 16 16" aria-hidden="true">${ICON[k]()}</svg>`;
  function renderIcon() {
    const b = document.getElementById("themeBtn"); if (!b) return;
    b.innerHTML = svg(mode);
    const lbl = mode === "live" && target ? `Tema: ao vivo (${target.label})` : mode === "dark" ? "Tema: escuro" : "Tema: claro";
    b.title = lbl; b.setAttribute("aria-label", lbl + ". Clique para trocar.");
  }

  function modal(inner) {
    const m = document.createElement("div"); m.className = "modal"; m.innerHTML = `<div class="card">${inner}</div>`;
    m.addEventListener("click", e => { if (e.target === m) m.remove(); });
    document.addEventListener("keydown", function esc(e) { if (e.key === "Escape") { m.remove(); document.removeEventListener("keydown", esc); } });
    document.body.appendChild(m); return m;
  }
  async function savePrefs(body) {
    try {
      const r = await fetch("/api/prefs", { method: "POST", headers: { "X-Token": TOKEN, "Content-Type": "application/json" }, body: JSON.stringify(body) });
      prefs = { ...prefs, ...(await r.json()) };
    } catch { /* fica so nesta janela */ }
  }

  window.chooseTheme = function () {
    const lab = { light: "Claro", dark: "Escuro", live: "Ao vivo" };
    const live = mode === "live" && target ? `<p class="hint">Agora: <b>${target.label}</b>${prefs.use_location ? (wx ? " · tempo da sua cidade" : " · buscando o tempo…") : " · só pelo horário"}.</p>` : "";
    const m = modal(`<h3>Tema</h3>
      <div class="tchoice">${["light", "dark", "live"].map(k => `<button class="${k === mode ? "sel" : ""}" data-k="${k}">${svg(k)}${lab[k]}</button>`).join("")}</div>
      <p class="hint"><b>Ao vivo:</b> o céu segue a hora e o tempo de verdade (sol, lua na fase certa, nuvens, chuva, raios, pôr do sol).
      A animação para sozinha com a janela minimizada e quando o computador está pesado ou quente.</p>${live}
      ${mode === "live" && prefs.use_location ? `<p class="hint"><a href="#" data-off>Parar de usar a localização</a></p>` : ""}
      <div class="btns"><button class="w sm" data-close style="background:var(--card);color:var(--text)">Fechar</button></div>`);
    m.querySelector("[data-close]").onclick = () => m.remove();
    const off = m.querySelector("[data-off]");
    if (off) off.onclick = async e => { e.preventDefault(); await savePrefs({ use_location: false }); wx = null; m.remove(); liveTick(true); };
    m.querySelectorAll(".tchoice button").forEach(b => b.onclick = () => { m.remove(); setMode(b.dataset.k); });
  };

  function askLocation() {
    return new Promise(res => {
      const m = modal(`<h3>Usar a localização?</h3>
        <p>Para o céu mostrar o tempo da sua cidade (chuva, nuvens, raios), o CalorScan usa a localização
        <b>aproximada</b> do Windows (arredondada para uns 10 km) e consulta o <b>open-meteo.com</b>, no máximo a cada 30 minutos.
        Nada mais sai do computador.</p>
        <p class="hint">Sem a localização, o céu segue só o horário (sol, lua e estrelas).</p>
        <div class="btns"><button class="g" data-y>Usar localização</button><button class="w sm" data-n style="background:var(--card);color:var(--text)">Só o horário</button></div>`);
      m.querySelector("[data-y]").onclick = () => { m.remove(); res(true); };
      m.querySelector("[data-n]").onclick = () => { m.remove(); res(false); };
    });
  }

  async function setMode(k) {
    if (k === mode && k !== "live") return;
    const was = mode;
    if (k === "live" && prefs.use_location == null) {
      const ok = await askLocation();
      await savePrefs({ use_location: ok });
    }
    mode = k; html.classList.toggle("live", k === "live");
    await savePrefs({ theme: k });
    renderIcon();
    clearInterval(liveTimer);
    if (k === "live") { startLive(); return; }
    if ((was === "light" && k === "dark") || (was === "dark" && k === "light")) animateTo(k === "dark");
    else { show(k === "dark" ? virtual(NIGHT_H) : virtual(DAY_H), true); setDark(k === "dark", true); }
  }

  /* ---------------- inicio ---------------- */
  window.addEventListener("resize", () => { lastBg = ""; kick(); });
  document.addEventListener("visibilitychange", () => { if (!document.hidden) { lastStep = 0; if (mode === "live") liveTick(false); kick(); } });
  reduce.addEventListener?.("change", kick);
  // o PC esfriou/aliviou: o ceu volta a andar (o painel avisa a carga em window.skyLoad)
  setInterval(() => { if (!timer && moving()) kick(); }, 5000);

  html.classList.toggle("live", mode === "live");
  if (mode === "live") { target = liveScene(); cur = from(target, null); setDark(target.darkUi, false); startLive(); }
  else { show(mode === "dark" ? virtual(NIGHT_H) : virtual(DAY_H), false); setDark(mode === "dark", false); }
  renderIcon();
  if (mode !== "live") fetch("/api/sky").then(r => r.json()).then(j => { prefs = j; }).catch(() => {});
})();
