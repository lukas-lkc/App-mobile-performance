/* CalorScan PC — parte "Computador" (painel, processos, apps, inicialização, esfriar, discos, segurança, relatório) */
const PC = {tab: "painel", live: null, apps: null, startup: null, sec: null, disks: null, power: null,
            procSort: "cpu", procQ: "", showWin: false, appQ: "", appF: "todos", appSort: "uso",
            disk: {view: "pastas", path: null, sort: "size", cat: "", q: "", older: 0, offset: 0, sel: new Set(), files: null,
                   dupSel: new Set(), dups: null, clean: null, cleanSel: new Set(["temp", "browser", "thumbs"])},
            report: null, timer: null};
const TABS = [["painel","Painel"],["procs","Processos"],["apps","Apps"],["inicio","Inicialização"],["esfriar","Esfriar"],
              ["discos","Discos"],["seg","Segurança"],["relatorio","Relatório para IA"]];

/* ---------------- utilidades ---------------- */
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const fmtB = b => { b = b||0; const u=["B","KB","MB","GB","TB"]; let i=0; while(b>=1024&&i<4){b/=1024;i++} return (i>=3?b.toFixed(1):Math.round(b))+" "+u[i]; };
const fmtD = t => { if(!t) return "—"; const d = typeof t==="number" ? new Date(t*1000) : new Date(t); return isNaN(d)?"—":d.toLocaleDateString("pt-BR"); };
const ago = s => s<3600 ? Math.round(s/60)+" min" : s<86400 ? Math.round(s/3600)+" h" : Math.round(s/86400)+" dias";
const lvlCol = v => v>=85 ? "var(--red)" : v>=60 ? "var(--orange)" : v>=35 ? "var(--sun)" : "var(--green)";
const J = o => esc(JSON.stringify(o));
async function api(path){ const r = await fetch(path); return r.json(); }
async function doPC(a, extra={}, quiet=false){
  const r = await fetch("/api/pc/do", {method:"POST", headers:{"Content-Type":"application/json","X-Token":TOKEN},
                                       body: JSON.stringify({a, ...extra})});
  let j = {}; try{ j = await r.json(); }catch{}
  if(!quiet && j.msg) toast(j.msg);
  if(!r.ok && !j.msg) toast("Não deu certo.");
  livePoll();
  return j;
}
function meter(pct, col){
  const on = Math.round((pct||0)/10);
  return `<div class="meter" style="--c:${col||lvlCol(pct)}">${Array.from({length:10},(_,i)=>`<i class="${i<on?"on":""}"></i>`).join("")}</div>`;
}
function confirmBox(title, text, ok="Confirmar", cls="r"){
  return new Promise(res => {
    const m = document.createElement("div"); m.className = "modal";
    m.innerHTML = `<div class="card"><h3>${title}</h3><p>${text}</p><div class="btns">
      <button class="${cls}" data-ok>${ok}</button><button class="w sm" style="background:#fff;color:var(--ink)" data-no>Cancelar</button></div></div>`;
    document.body.appendChild(m);
    m.querySelector("[data-ok]").onclick = () => { m.remove(); res(true); };
    m.querySelector("[data-no]").onclick = () => { m.remove(); res(false); };
    m.onclick = e => { if(e.target===m){ m.remove(); res(false); } };
    m.querySelector("[data-ok]").focus();
  });
}

/* ---------------- navegação ---------------- */
function pcEnter(){ renderTabs(); showTab(PC.tab); livePoll(); }
function renderTabs(){
  const L = PC.live || {}, f = L.findings || [];
  const cnt = {procs: (L.procs||[]).filter(p=>p.risk==="alto"||p.risk==="medio").length,
               seg: f.filter(x=>x.tab==="seg" && x.level==="danger").length,
               inicio: f.filter(x=>x.tab==="inicio" && x.level!=="info").length,
               apps: f.filter(x=>x.tab==="apps" && x.level!=="info").length,
               discos: f.filter(x=>x.tab==="discos" && x.level==="danger").length,
               esfriar: f.filter(x=>x.tab==="esfriar" && x.level==="warn").length};
  $("#pcTabs").innerHTML = TABS.map(([k,l]) => `<button class="${PC.tab===k?"sel":""}" onclick="showTab('${k}')">${l}${cnt[k]?`<span class="n">${cnt[k]}</span>`:""}</button>`).join("");
}
function showTab(t){
  PC.tab = t; renderTabs();
  const b = $("#pcBody");
  if(t==="painel"||t==="procs"){ b.innerHTML = t==="procs" ? procsShell() : `<div id="painel"></div>`; renderLive(); }
  else if(t==="apps") loadApps();
  else if(t==="inicio") loadStartup();
  else if(t==="esfriar") loadPower();
  else if(t==="discos") loadDisks();
  else if(t==="seg") renderSec();
  else if(t==="relatorio") renderReport();
  window.scrollTo({top:0});
}

/* ---------------- ao vivo ---------------- */
async function livePoll(){
  if(view!=="pc") return;
  // janela minimizada: so um "estou vivo" a cada 20 s (o programa fecha sozinho se ficar 45 s sem noticias)
  if(document.hidden){ if(Date.now() - (PC._hiddenPing||0) < 20000) return; PC._hiddenPing = Date.now(); }
  try{ PC.live = await api("/api/pc/live"); }
  catch{ $("#chipText").textContent = "Programa fechado. Abra de novo."; return; }
  const L = PC.live, t = L.temps||{};
  // mascote: temperatura real se existir; senão o uso do processador vira "sensação térmica"
  const fromCpu = L.cpu_avg1==null ? null : L.cpu_avg1<25 ? 34 : L.cpu_avg1<50 ? 38 : L.cpu_avg1<80 ? 41.5 : 44;
  const fromGpu = t.gpu ? (t.gpu<55?34:t.gpu<70?38:t.gpu<82?41.5:44) : null;
  const fromCt = t.cpu ? (t.cpu<55?34:t.cpu<72?38:t.cpu<85?41.5:44) : null;
  const m = Math.max(fromCpu||0, fromGpu||0, fromCt||0) || null;
  mascot(m);
  $("#dot").className = "dot " + (m>=43?"bad":m>=40?"warn":"on");
  $("#chipText").textContent = `Processador ${Math.round(L.cpu)}% · Memória ${L.mem.load}%` +
    (t.gpu ? ` · Vídeo ${t.gpu} °C` : t.cpu ? ` · ${t.cpu} °C` : "") + (L.admin ? " · admin" : "");
  const lg = $("#log"), key = (L.log||[]).join("|");
  if(key !== PC._logKey){ PC._logKey = key; PC._logT = Date.now(); }
  lg.innerHTML = (L.busy?`<span class="spin" style="border-color:#fff;border-right-color:transparent"></span> ${esc(L.busy)}…<br>`:"") + (L.log||[]).slice(-2).map(esc).join("<br>");
  lg.classList.toggle("show", !!(L.busy || ((L.log||[]).length && Date.now()-PC._logT < 9000)));
  renderTabs();
  if(PC.tab==="painel"||PC.tab==="procs") renderLive();
  if(PC.tab==="discos" && (L.disk_phase==="scanning"||L.disk_phase==="hashing"||PC._diskWasBusy)) diskTick();
  if(PC.tab==="seg" && (L.sec_running || PC._secWasBusy)){ PC._secWasBusy = L.sec_running; if(!L.sec_running) loadSec(); else renderSec(); }
  if(PC.tab==="apps" && PC._appsBusy && !L.busy){ PC._appsBusy=false; loadApps(); }
}
setInterval(livePoll, 2000);

function renderLive(){
  const L = PC.live;
  if(!L){ ($("#painel")||$("#procList")||{}).innerHTML = `<div class="card empty2"><span class="spin"></span> Lendo o computador…</div>`; return; }
  if(PC.tab==="painel") renderPainel(L); else renderProcs(L);
}

function chartBars(hist, key, max, col, label, unit="%"){
  const pts = hist.filter(p=>p[key]!=null);
  if(pts.length<2) return `<div class="empty2">Juntando dados… ${label} aparece em alguns segundos.</div>`;
  const W=600, H=100, bw=W/Math.max(pts.length,60);
  const bars = pts.map((p,i)=>{ const v=Math.min(max,p[key]); const h=Math.max(2,v/max*(H-6));
    return `<rect x="${i*bw}" y="${H-h}" width="${Math.max(1,bw-1)}" height="${h}" fill="${typeof col==="function"?col(v):col}"><title>${v}${unit}</title></rect>`; }).join("");
  return `<svg class="chart2" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">${bars}</svg>`;
}

function renderPainel(L){
  const el = $("#painel"); if(!el) return;
  const t = L.temps||{}, nv = L.nv||{}, si = L.sys||{};
  const sysDrive = null;
  const fs = L.findings||[];
  const bad = fs.filter(f=>f.level==="danger"||f.level==="warn");
  const scol = L.score>=80?"var(--green2)":L.score>=55?"var(--orange2)":"var(--red)";
  const findHTML = fs.filter(f=>f.level!=="ok").slice(0,9).map(f=>`<div class="find ${f.level==="danger"?"danger":f.level}">
      <div class="ic"></div><div><b>${esc(f.title)}</b><span>${esc(f.text)}</span>
      ${f.tab?`<div class="act"><button class="sm" onclick="showTab('${f.tab}')">Resolver →</button></div>`:""}</div></div>`).join("")
    || `<div class="find ok"><div class="ic"></div><div><b>Nada preocupante agora</b><span>Continue usando; o CalorScan segue de olho.</span></div></div>`;
  const tempTxt = t.cpu ? `${t.cpu} °C` : t.gpu ? `${t.gpu} °C` : "—";
  const tempSub = t.cpu ? "sensor do Windows" : t.gpu ? `placa de vídeo${/^\d+$/.test(nv.fan||"")?" · ventoinha "+nv.fan+"%":""}` : "o Windows não informa";
  const top = (L.procs||[]).filter(p=>p.kind!=="windows" || p.cpu>=5).slice(0,6);
  const maxc = Math.max(10, ...top.map(p=>p.cpu));
  el.innerHTML = `
  <div class="row rh">
    <div class="card">
      <h2>Saúde do PC</h2>
      <div class="score"><svg width="64" height="88" viewBox="0 0 16 22" id="m3"></svg>
        <div><div class="snum" style="color:${scol}">${L.score}</div><div class="lbl">${esc(L.score_label)}</div>
        <div class="hint" style="margin:4px 0 0">${bad.length?`${bad.length} coisa(s) para olhar`:"Tudo em ordem"}</div></div></div>
      <p class="hint" style="margin-top:10px">${esc(si.maker||"")} ${esc(si.model||"")}<br>${esc(si.cpu||"")}<br>
        Ligado há ${ago(L.uptime)} · monitorando há ${L.session_min} min</p>
      ${L.admin?`<span class="tag ok">Modo administrador</span>`:`<button class="sm w adm" onclick="askAdmin()">Abrir como administrador</button>`}
    </div>
    <div class="stats">
      <div class="stat"><div class="k">Processador</div><div class="v">${Math.round(L.cpu)}%</div>${meter(L.cpu)}
        <div class="s">${L.freq_ghz?`${L.freq_ghz} GHz · `:""}média 1 min ${L.cpu_avg1??"—"}%${L.throttled?" · <b style='color:var(--red)'>freado</b>":""}</div></div>
      <div class="stat"><div class="k">Memória</div><div class="v">${L.mem.load}%</div>${meter(L.mem.load)}
        <div class="s">${fmtB(L.mem.total-L.mem.free)} de ${fmtB(L.mem.total)}</div></div>
      <div class="stat"><div class="k">Temperatura</div><div class="v">${tempTxt}</div>${meter(t.cpu||t.gpu ? Math.min(100,((t.cpu||t.gpu)-30)/60*100) : 0)}
        <div class="s">${tempSub}</div></div>
      <div class="stat"><div class="k">Placa de vídeo</div><div class="v">${nv.util!=null?nv.util+"%":"—"}</div>${meter(nv.util||0)}
        <div class="s">${nv.name?esc(nv.name)+(nv.watts?` · ${nv.watts} W`:""):"sem placa NVIDIA"}</div></div>
      <div class="stat"><div class="k">Disco (atividade)</div><div class="v">${L.disk!=null?L.disk+"%":"—"}</div>${meter(L.disk||0)}
        <div class="s">lendo ${fmtB(L.dr)}/s · gravando ${fmtB(L.dw)}/s</div></div>
      <div class="stat"><div class="k">${L.batt!=null?"Bateria":"Internet"}</div>
        <div class="v">${L.batt!=null?L.batt+"%":fmtB(L.rx)+"/s"}</div>${meter(L.batt!=null?L.batt:0, "var(--green)")}
        <div class="s">${L.batt!=null?(L.ac?"na tomada":"na bateria")+" · ":""}↓ ${fmtB(L.rx)}/s ↑ ${fmtB(L.tx)}/s</div></div>
    </div>
  </div>
  <div class="row r2">
    <div class="card"><h2>O que o CalorScan descobriu</h2>${findHTML}
      <p class="hint" style="margin-top:8px">Para uma análise completa, rode a <a href="#" onclick="showTab('seg');return false">verificação de segurança</a>
      e gere o <a href="#" onclick="showTab('relatorio');return false">relatório para IA</a>.</p></div>
    <div>
      <div class="card"><h2>Processador <small>últimos 10 min</small></h2>${chartBars(L.hist,"cpu",100,lvlCol,"O gráfico")}
        <div class="legend"><span>tempo →</span><span><i style="background:var(--green)"></i>tranquilo</span><span><i style="background:var(--sun)"></i>ocupado</span><span><i style="background:var(--orange)"></i>pesado</span><span><i style="background:var(--red)"></i>no limite</span></div>
        <h2 style="margin-top:6px">Memória</h2>${chartBars(L.hist,"mem",100,v=>v>=90?"var(--red)":v>=80?"var(--orange)":"var(--blue)","A memória")}
        ${L.hist.some(p=>p.gtemp)?`<h2 style="margin-top:6px">Temperatura da placa de vídeo</h2>${chartBars(L.hist,"gtemp",100,v=>v>=85?"var(--red)":v>=75?"var(--orange)":"var(--green)","A temperatura"," °C")}`:""}
      </div>
      <div class="card"><h2>Pesando agora <span class="sp"></span><button class="sm" onclick="showTab('procs')">Ver todos</button></h2>
        ${top.map(p=>`<div class="proc"><span class="n" title="${esc(p.exe)}">${esc(p.name)}${p.count>1?` ×${p.count}`:""}</span>
          <div class="b ${p.kind==="windows"?"sistema":p.risk?"":"google"}" style="width:${Math.max(3,p.cpu/maxc*100)}%;${p.risk?"background:var(--red)":""}"></div><span>${p.cpu}%</span></div>`).join("")}
      </div>
    </div>
  </div>`;
  $("#m3").innerHTML = $("#mascot").innerHTML;
}

/* ---------------- processos ---------------- */
function procsShell(){
  return `<div class="card">
    <h2>Programas rodando agora <small id="procCount"></small></h2>
    <p class="hint">Agrupados por programa (ex.: todas as abas do navegador juntas). <b>Encerrar</b> fecha na hora; o que não foi salvo nele se perde.
      Os marcados em <b style="color:var(--red)">vermelho</b> ou <b style="color:var(--orange2)">laranja</b> têm algo estranho: veja o motivo embaixo do nome.</p>
    <div class="tbar"><input class="in" id="procQ" placeholder="Procurar programa…" value="${esc(PC.procQ)}" oninput="PC.procQ=this.value;renderLive()">
      <span class="seg">${[["cpu","Processador"],["mem","Memória"],["sess","Desde que abriu"],["risk","Suspeitos"]].map(([k,l])=>`<button class="${PC.procSort===k?"sel":""}" onclick="PC.procSort='${k}';showTab('procs')">${l}</button>`).join("")}</span>
      <label style="font-size:14px;display:flex;gap:6px;align-items:center"><input type="checkbox" class="chk" ${PC.showWin?"checked":""} onchange="PC.showWin=this.checked;renderLive()"> Mostrar processos do Windows</label>
    </div>
    <div class="list" id="procList"></div></div>`;
}
function renderProcs(L){
  const el = $("#procList"); if(!el) return;
  let rows = (L.procs||[]).slice();
  if(!PC.showWin) rows = rows.filter(p=>p.kind!=="windows" || p.risk);
  if(PC.procQ){ const q = PC.procQ.toLowerCase(); rows = rows.filter(p=>(p.name+" "+p.exe+" "+(p.company||"")).toLowerCase().includes(q)); }
  const rk = {alto:3, medio:2, baixo:1};
  if(PC.procSort==="mem") rows.sort((a,b)=>b.mem-a.mem);
  else if(PC.procSort==="sess") rows.sort((a,b)=>b.session_cpu-a.session_cpu);
  else if(PC.procSort==="risk") rows = rows.filter(p=>p.risk).sort((a,b)=>(rk[b.risk]||0)-(rk[a.risk]||0));
  $("#procCount").textContent = `${rows.length} programas · ${L.procs.reduce((s,p)=>s+p.count,0)} processos`;
  const maxc = Math.max(5, ...rows.map(p=>p.cpu));
  el.innerHTML = rows.slice(0,120).map(p => {
    const ic = p.risk || p.kind;
    const label = p.risk==="alto"?"!":p.risk?"?":p.kind==="windows"?"W":p.kind==="protegido"?"🔒":p.name.slice(0,1).toUpperCase();
    return `<div class="it"><div class="ico ${ic}">${label}</div>
      <div style="min-width:0"><div class="t">${esc(p.name)}${p.count>1?` <span class="tag g">×${p.count}</span>`:""}${p.risk?`<span class="tag ${p.risk}">${p.risk==="alto"?"suspeito":p.risk==="medio"?"atenção":"verificar"}</span>`:""}${p.app_name?`<span class="tag b">app</span>`:""}</div>
        <div class="d" title="${esc(p.path||"")}">${esc(p.exe)} · ${esc(p.company||(p.kind==="protegido"?"protegido pelo Windows":"sem fabricante"))}${p.path?" · "+esc(p.path):""}</div>
        ${p.reasons.length?`<div class="why ${p.risk}">⚠ ${esc(p.reasons.join("; "))}</div>`:""}</div>
      <div class="r">
        <span class="mini">Processador<b>${p.cpu}%</b><span class="hb"><i style="width:${Math.min(100,p.cpu/maxc*100)}%;background:${lvlCol(p.cpu*2)}"></i></span></span>
        <span class="mini">Memória<b>${fmtB(p.mem)}</b></span>
        ${p.gpu?`<span class="mini">Vídeo<b>${p.gpu}%</b></span>`:""}
        <span class="mini">Desde que abriu<b>${p.session_cpu}s</b></span>
        ${p.critical?`<span class="tag g" title="Essencial para o Windows">essencial</span>`:`<button class="sm r" onclick='killProc(${J(p.key)},${J(p.name)})'>Encerrar</button>`}
        ${p.path?`<button class="sm w" title="Abrir a pasta" onclick='doPC("open",{path:${J(p.path)}})'>📁</button>`:""}
        <button class="sm w" title="Pesquisar na internet o que é" onclick='doPC("web",{name:${J(p.exe)}})'>🔎</button>
        ${p.app&&!p.critical?`<button class="sm o" onclick='uninstallApp(${J(p.app)},${J(p.app_name)})'>Desinstalar</button>`:""}
      </div></div>`;
  }).join("") || `<div class="empty2">Nada encontrado.</div>`;
}
async function killProc(key, name){
  if(await confirmBox(`Encerrar ${esc(name)}?`, "O programa fecha na hora. O que não foi salvo nele se perde. Se ele abrir sozinho de novo, desligue-o na aba Inicialização.", "Encerrar"))
    doPC("kill", {key});
}
async function askAdmin(){
  if(await confirmBox("Abrir como administrador?", "Com permissão de administrador o CalorScan vê todos os processos, as exclusões do antivírus e pode mexer em itens do sistema sem pedir a cada vez. O Windows vai perguntar se você permite. Uma nova janela abre; feche esta.", "Abrir", "g"))
    doPC("admin");
}

/* ---------------- apps ---------------- */
async function loadApps(refresh){
  const b = $("#pcBody");
  if(!PC.apps) b.innerHTML = `<div class="card empty2"><span class="spin"></span> Listando os apps instalados…</div>`;
  PC.apps = await api("/api/pc/apps" + (refresh ? "?refresh=1" : ""));
  if(PC.tab==="apps") renderApps();
}
function renderApps(){
  const all = PC.apps.apps||[];
  if(!$("#appList")){
    $("#pcBody").innerHTML = `<div class="card"><h2>Apps instalados <small id="appCount"></small><span class="sp"></span>
      <button class="sm w" onclick="doPC('refresh',{what:'apps'});PC._appsBusy=true">Atualizar lista</button></h2>
      <p class="hint"><b>Desinstalar</b> abre o desinstalador do próprio programa: siga a janela dele. Apps com <b style="color:var(--red)">alerta</b>
        são piratas, indesejados (propaganda) ou estão em pastas estranhas. A coluna <b>Agora</b> mostra quanto cada app está usando neste momento.</p>
      <div class="tbar"><input class="in" placeholder="Procurar app…" value="${esc(PC.appQ)}" oninput="PC.appQ=this.value;renderAppRows()">
        <span class="seg" id="appF"></span><span class="seg" id="appS"></span></div>
      <div class="list" id="appList"></div></div>`;
  }
  const F = [["todos","Todos"],["alerta","Com alerta"],["uso","Usando agora"],["recentes","Recentes"],["store","Microsoft Store"]];
  const S = [["uso","Consumo"],["tam","Tamanho"],["data","Data"],["nome","Nome"]];
  $("#appF").innerHTML = F.map(([k,l])=>`<button class="${PC.appF===k?"sel":""}" onclick="PC.appF='${k}';renderApps()">${l}${k==="alerta"?` (${all.filter(a=>a.risk).length})`:""}</button>`).join("");
  $("#appS").innerHTML = S.map(([k,l])=>`<button class="${PC.appSort===k?"sel":""}" onclick="PC.appSort='${k}';renderApps()">${l}</button>`).join("");
  renderAppRows();
}
function renderAppRows(){
  let rows = (PC.apps.apps||[]).slice();
  if(PC.appF==="alerta") rows = rows.filter(a=>a.risk);
  if(PC.appF==="uso") rows = rows.filter(a=>a.running);
  if(PC.appF==="recentes") rows = rows.filter(a=>a.days!=null && a.days<=30);
  if(PC.appF==="store") rows = rows.filter(a=>a.store);
  if(PC.appQ){ const q=PC.appQ.toLowerCase(); rows = rows.filter(a=>(a.name+" "+a.publisher).toLowerCase().includes(q)); }
  const rk = {alto:3, medio:2, baixo:1};
  const srt = {uso:(a,b)=>(b.cpu-a.cpu)||(b.session_cpu-a.session_cpu)||(b.mem-a.mem)||((rk[b.risk]||0)-(rk[a.risk]||0)),
               tam:(a,b)=>b.size-a.size, data:(a,b)=>(b.date||"").localeCompare(a.date||""), nome:(a,b)=>a.name.localeCompare(b.name)};
  rows.sort(srt[PC.appSort]);
  $("#appCount").textContent = `${rows.length} de ${(PC.apps.apps||[]).length}`;
  $("#appList").innerHTML = rows.map(a=>`<div class="it"><div class="ico ${a.risk||"app"}">${a.risk?"!":esc(a.name.slice(0,1).toUpperCase())}</div>
    <div style="min-width:0"><div class="t">${esc(a.name)}${a.risk?`<span class="tag ${a.risk}">${a.risk==="alto"?"perigoso":a.risk==="medio"?"suspeito":"verificar"}</span>`:""}${a.store?`<span class="tag g">Store</span>`:""}${a.running?`<span class="tag ok">aberto</span>`:""}</div>
      <div class="d">${esc(a.publisher||"sem fabricante")} · versão ${esc(a.version||"?")} · ${a.store?"":"instalado "}${fmtD(a.date)}${a.size?" · "+fmtB(a.size):""}</div>
      ${a.reasons.length?`<div class="why ${a.risk}">⚠ ${esc(a.reasons.join("; "))}</div>`:""}</div>
    <div class="r">
      ${a.running?`<span class="mini">Agora<b>${a.cpu}%</b></span><span class="mini">Memória<b>${fmtB(a.mem)}</b></span>`:""}
      ${a.session_cpu?`<span class="mini">Desde que abriu<b>${a.session_cpu}s</b></span>`:""}
      ${a.location?`<button class="sm w" title="Abrir a pasta" onclick='doPC("open",{path:${J(a.location)}})'>📁</button>`:""}
      ${a.self?`<span class="tag g" title="O CalorScan precisa dele para funcionar">usado pelo CalorScan</span>`:`<button class="sm r" onclick='uninstallApp(${J(a.id)},${J(a.name)})'>Desinstalar</button>`}</div></div>`).join("")
    || `<div class="empty2">Nenhum app aqui.</div>`;
}
async function uninstallApp(id, name){
  if(!await confirmBox(`Desinstalar ${esc(name)}?`, "Vai abrir o desinstalador do próprio programa (ou remover direto, se for da Microsoft Store). Dados do programa podem ser apagados. Não dá para desfazer pelo CalorScan: para voltar, instale de novo.", "Desinstalar")) return;
  const j = await doPC("uninstall", {id}, true);
  if(j.ok){ toast("Abrindo o desinstalador… siga a janela dele"); PC._appsBusy = true; }
}

/* ---------------- inicialização ---------------- */
async function loadStartup(refresh){
  if(!PC.startup) $("#pcBody").innerHTML = `<div class="card empty2"><span class="spin"></span> Lendo o que abre com o Windows…</div>`;
  PC.startup = await api("/api/pc/startup" + (refresh?"?refresh=1":""));
  if(PC.tab==="inicio") renderStartup();
}
function togHTML(on, onclick, dis){ return `<button class="tog ${on?"on":""}" onclick="${onclick}" ${dis?"disabled":""} aria-pressed="${on}" title="${on?"Ligado":"Desligado"}"></button>`; }
function riskLine(i){ return i.reasons&&i.reasons.length ? `<div class="why ${i.risk}">⚠ ${esc(i.reasons.join("; "))}</div>` : ""; }
function renderStartup(){
  const S = PC.startup, u = Object.keys(S.undo||{}).length;
  const TR = {Logon:"ao entrar", Boot:"ao ligar", Daily:"todo dia", Weekly:"toda semana", Time:"num horário", Idle:"com o PC parado",
              Registration:"ao instalar", SessionStateChange:"ao desbloquear", Event:"num evento", "":"—"};
  const runOn = S.run.filter(i=>i.enabled).length;
  $("#pcBody").innerHTML = `
  <div class="card"><h2>Inicialização <span class="sp"></span>
      <button class="sm w" onclick="loadStartup(true)">Atualizar</button>
      <button class="sm y" ${u?"":"disabled"} onclick="undoAll()">Desfazer tudo${u?` (${u})`:""}</button></h2>
    <p class="hint">Tudo o que liga aqui roda <b>desde o momento em que o PC liga</b>, ocupando processador e memória. Desligar <b>não desinstala</b>:
      o programa continua funcionando quando você abrir. Itens de "todos os usuários" e serviços pedem permissão de administrador.
      <b>Desfazer tudo</b> volta como estava antes do CalorScan.</p></div>
  <div class="card"><h2>Abrem junto com o Windows <small>${runOn} ligados de ${S.run.length}</small></h2>
    <div class="list">${S.run.map(i=>`<div class="it"><div class="ico ${i.risk||"app"}">${i.risk?"!":esc(i.name.slice(0,1).toUpperCase())}</div>
      <div style="min-width:0"><div class="t">${esc(i.desc||i.name)}${i.risk?`<span class="tag ${i.risk}">${i.risk==="alto"?"suspeito":"atenção"}</span>`:""}<span class="tag g">${esc(i.scope)}</span></div>
        <div class="d" title="${esc(i.cmd)}">${esc(i.company||"sem fabricante")} · ${esc(i.cmd)}</div>${riskLine(i)}</div>
      <div class="r">${i.exe&&!i.exe.endsWith(".lnk")?`<button class="sm w" onclick='doPC("open",{path:${J(i.exe)}})'>📁</button>`:""}
        ${togHTML(i.enabled, `toggleStartup(this,'startup',${J(i.id)},${!i.enabled})`)}</div></div>`).join("") || `<div class="empty2">Nada abre sozinho. 👍</div>`}</div></div>
  <div class="card"><h2>Tarefas agendadas <small>de outros fabricantes (as da Microsoft só aparecem se forem estranhas)</small></h2>
    <div class="list">${S.tasks.map(t=>`<div class="it"><div class="ico ${t.risk||"app"}">${t.risk?"!":"⏱"}</div>
      <div style="min-width:0"><div class="t">${esc(t.name)}${t.risk?`<span class="tag ${t.risk}">${t.risk==="alto"?"suspeita":"atenção"}</span>`:""}</div>
        <div class="d" title="${esc(t.exec)}">${esc(t.company||t.author||"")} · roda ${esc((t.trig||"").split(",").map(x=>TR[x]||x).join(", ")||"—")} · ${esc(t.exec)}</div>${riskLine(t)}</div>
      <div class="r">${togHTML(t.enabled, `toggleStartup(this,'task',${J(t.id)},${!t.enabled})`)}</div></div>`).join("") || `<div class="empty2">Nenhuma.</div>`}</div></div>
  <div class="card"><h2>Serviços de outros fabricantes <small>rodam escondidos, sem janela</small></h2>
    <p class="hint">Só desligue se souber o que é (ex.: atualizadores e "melhoria da experiência"). Desligar serviços de placa de vídeo, áudio ou teclado pode tirar funções.</p>
    <div class="list">${S.services.map(s=>`<div class="it"><div class="ico ${s.risk||(s.state==="Running"?"app":"windows")}">${s.risk?"!":"⚙"}</div>
      <div style="min-width:0"><div class="t">${esc(s.disp)}${s.risk?`<span class="tag ${s.risk}">${s.risk==="alto"?"suspeito":"atenção"}</span>`:""}<span class="tag ${s.state==="Running"?"ok":"g"}">${s.state==="Running"?"rodando":"parado"}</span><span class="tag g">${({Auto:"automático",Manual:"manual",Disabled:"desativado"})[s.mode]||esc(s.mode)}</span></div>
        <div class="d" title="${esc(s.path)}">${esc(s.company||"sem fabricante")} · ${esc(s.desc||s.path)}</div>${riskLine(s)}</div>
      <div class="r">${s.exe?`<button class="sm w" onclick='doPC("open",{path:${J(s.exe)}})'>📁</button>`:""}${togHTML(s.enabled, `toggleStartup(this,'service',${J(s.id)},${!s.enabled})`)}</div></div>`).join("") || `<div class="empty2">Nenhum.</div>`}</div></div>`;
}
async function toggleStartup(btn, a, id, on){
  btn.disabled = true;
  const j = await doPC(a, {id, on});
  if(j.job){ toast(on?"Religando…":"Desligando…"); const w = setInterval(async()=>{ if(!PC.live.busy){ clearInterval(w); loadStartup(true); } }, 1500); }
  else loadStartup(true);
}
async function undoAll(){
  if(!await confirmBox("Desfazer tudo?", "Volta a inicialização, as tarefas, os serviços e o plano de energia como estavam antes do CalorScan mexer.", "Desfazer", "y")) return;
  await doPC("undo_all");
  const w = setInterval(async()=>{ if(!PC.live.busy){ clearInterval(w); PC.tab==="esfriar"?loadPower():loadStartup(true); } }, 1500);
}

/* ---------------- esfriar ---------------- */
async function loadPower(){
  const j = await api("/api/pc/power"); PC.power = j.power; PC.powerUndo = j.undo;
  if(PC.tab==="esfriar") renderEsfriar();
}
function renderEsfriar(){
  const p = PC.power||{}, L = PC.live||{}, si = L.sys||{};
  const balanced = /equilibrad|balanced/i.test(p.name||"");
  const maker = (si.maker||"").toLowerCase();
  const fan = maker.includes("acer") ? "NitroSense (ou PredatorSense)" : maker.includes("asus") ? "Armoury Crate" : maker.includes("lenovo") ? "Lenovo Vantage" : maker.includes("dell") ? "Dell Power Manager" : maker.includes("hp") ? "OMEN Gaming Hub" : "app do fabricante";
  const u = Object.keys(PC.powerUndo||{}).filter(k=>k.startsWith("power|")).length;
  const heavy = (L.procs||[]).filter(x=>x.kind!=="windows" && x.cpu>=10).slice(0,4);
  $("#pcBody").innerHTML = `
  <div class="card"><h2>Esfriar o computador <span class="sp"></span><button class="sm y" ${u?"":"disabled"} onclick="undoAll()">Desfazer${u?` (${u})`:""}</button></h2>
    <p class="hint">Cada chave pode ser ligada e desligada quando quiser. <b>Desfazer</b> volta exatamente como estava.</p>
    <div class="list">
      <div class="it"><div class="ico ${balanced?"app":"medio"}">⚡</div><div><div class="t">Plano de energia equilibrado</div>
        <div class="d" style="white-space:normal">Agora: <b>${esc(p.name||"?")}</b>. No "Alto desempenho" o processador fica sempre acelerado, mesmo parado.
        O Equilibrado acelera só quando precisa: <b>esquenta menos</b> e quase não muda a velocidade do dia a dia.</div></div>
        <div class="r">${togHTML(balanced, `powerToggle(this,'balanced',${!balanced})`)}</div></div>
      <div class="it"><div class="ico ${p.cool?"app":"baixo"}">❄</div><div><div class="t">Modo fresco (sem turbo)</div>
        <div class="d" style="white-space:normal">Limita o processador a 99%, o que desliga o "turbo". É a mudança que <b>mais esfria</b> notebooks:
        costuma baixar de 10 a 20 °C com o processador cheio. Perde uns 10-25% de velocidade só nos picos (jogos e vídeos pesados).
        Agora: ${p.max_ac??"?"}% na tomada, ${p.max_dc??"?"}% na bateria.</div></div>
        <div class="r">${togHTML(!!p.cool, `powerToggle(this,'cool',${!p.cool})`)}</div></div>
    </div></div>
  <div class="row r2">
    <div class="card"><h2>Tirar peso do processador</h2>
      ${heavy.length?`<p class="hint">Pesando agora:</p>${heavy.map(x=>`<div class="proc"><span class="n">${esc(x.name)}</span><div class="b" style="width:${Math.min(100,x.cpu)}%"></div><span>${x.cpu}%</span></div>`).join("")}`:`<p class="hint">Nenhum programa pesando muito agora.</p>`}
      <div class="btns"><button class="sm" onclick="showTab('procs')">Ver programas</button><button class="sm" onclick="showTab('inicio')">O que abre com o Windows</button>
        <button class="sm" onclick="showTab('seg')">Procurar mineradores</button></div></div>
    <div class="card"><h2>Fora do programa (faz muita diferença)</h2>
      <ol class="tips">
        <li><b>Ventoinha:</b> no ${fan}, use o modo automático ou máximo quando jogar.</li>
        <li><b>Ar livre:</b> não use em cama, sofá ou colo. Uma base com ventoinha ou algo que levante a traseira ajuda bastante.</li>
        <li><b>Poeira:</b> notebook com mais de 1 ano costuma ter as saídas de ar entupidas. Uma limpeza (e pasta térmica nova) pode baixar 10-15 °C.</li>
        <li><b>Reiniciar:</b> use <b>Reiniciar</b> de vez em quando; o "Desligar" do Windows com Inicialização rápida não limpa tudo.</li>
        <li><b>Disco C: cheio</b> deixa tudo mais lento e o processador trabalha mais: veja a aba <a href="#" onclick="showTab('discos');return false">Discos</a>.</li>
      </ol></div>
  </div>`;
}
async function powerToggle(btn, a, on){ btn.disabled = true; await doPC(a, {on}); loadPower(); }

/* ---------------- discos ---------------- */
async function loadDisks(){
  PC.disks = await api("/api/pc/disks");
  if(PC.tab!=="discos") return;
  renderDisks();
}
function diskTick(){
  const was = PC._diskWasBusy, now = PC.live.disk_phase==="scanning"||PC.live.disk_phase==="hashing";
  PC._diskWasBusy = now;
  api("/api/pc/disks").then(d=>{ PC.disks = d; if(PC.tab!=="discos") return;
    if(now) renderDiskProgress(); else if(was){ PC.disk.files=null; PC.disk.dups=null; renderDisks(); } });
}
function renderDiskProgress(){
  const el = $("#diskProg"); if(!el) return renderDisks();
  const s = PC.disks.state||{};
  el.innerHTML = s.phase==="scanning" ? `<div class="card"><h2><span class="spin"></span> Analisando ${esc(s.root)}</h2>
      <p class="hint">${(s.files||0).toLocaleString("pt-BR")} arquivos · ${fmtB(s.bytes)} · ${(s.dirs||0).toLocaleString("pt-BR")} pastas · ${Math.round(Date.now()/1000-s.started)} s</p>
      <div class="d" style="font-size:12px;color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(s.current||"")}</div>
      <div class="btns"><button class="sm r" onclick="doPC('disk_cancel')">Cancelar</button></div></div>`
    : s.phase==="hashing" ? `<div class="card"><h2><span class="spin"></span> Procurando arquivos duplicados</h2>
      <div class="bar"><i style="width:${s.hash_total?Math.min(100,s.hash_done/s.hash_total*100):0}%"></i></div>
      <p class="hint">Comparando o conteúdo dos arquivos de mesmo tamanho (${fmtB(s.hash_done)} de ${fmtB(s.hash_total)}).</p>
      <div class="btns"><button class="sm r" onclick="doPC('disk_cancel')">Cancelar</button></div></div>` : "";
}
function renderDisks(){
  const D = PC.disks||{drives:[]}, s = D.state||{}, sum = D.summary;
  const busy = s.phase==="scanning"||s.phase==="hashing";
  const views = [["pastas","Pastas"],["grandes","Arquivos grandes"],["data","Por data"],["tipos","Por tipo"],["dups","Duplicados"],["limpeza","Limpeza rápida"]];
  $("#pcBody").innerHTML = `
  <div class="row r3" style="margin-bottom:0">${D.drives.map(d=>{ const pct = d.total? d.used/d.total*100 : 0;
    return `<div class="card"><div class="drv"><div><h2 style="margin:0">${esc(d.letter)} ${esc(d.label||"")}</h2>
      <div class="hint" style="margin:0">${esc(d.type)} · ${esc(d.fs)} · ${fmtB(d.total)}</div></div>
      <button class="sm g" ${busy?"disabled":""} onclick='doPC("disk_scan",{root:${J(d.root)}});PC._diskWasBusy=true;setTimeout(diskTick,400)'>Analisar</button>
      <div class="dbar"><i style="width:${pct}%;background:${pct>=90?"var(--red)":pct>=80?"var(--orange)":"var(--blue)"}"></i></div>
      <div style="grid-column:1/-1;font-size:13.5px"><b>${fmtB(d.free)} livres</b> · ${fmtB(d.used)} usados (${Math.round(pct)}%)</div></div></div>`; }).join("")}</div>
  <div class="card"><div class="tbar" style="margin:0"><b style="font-family:var(--head)">Analisar uma pasta:</b>
    <input class="in" id="dirIn" style="flex:1;min-width:200px" placeholder="Ex.: C:\\Users\\voce\\Downloads">
    <button class="sm g" ${busy?"disabled":""} onclick='doPC("disk_scan",{root:$("#dirIn").value});PC._diskWasBusy=true;setTimeout(diskTick,400)'>Analisar</button></div></div>
  <div id="diskProg"></div>
  <nav class="subtabs" style="background:var(--blue2)">${views.map(([k,l])=>`<button class="${PC.disk.view===k?"sel":""}" onclick="PC.disk.view='${k}';PC.disk.offset=0;PC.disk.sel.clear();renderDisks()">${l}</button>`).join("")}</nav>
  <div id="diskView"></div>`;
  renderDiskProgress();
  const v = $("#diskView");
  if(PC.disk.view==="limpeza") return renderCleanup(v);
  if(!sum){ v.innerHTML = `<div class="card empty2">Clique em <b>Analisar</b> em um dos discos acima (o C: é o mais importante). Leva de 1 a 5 minutos e você pode usar o PC enquanto isso.</div>`; return; }
  v.innerHTML = `<p class="hint">Análise de <b>${esc(sum.root)}</b>: ${fmtB(sum.bytes)} em arquivos. ${s.secs?`Levou ${s.secs} s.`:""}</p><div id="dv"></div>`;
  const dv = $("#dv");
  if(PC.disk.view==="pastas") renderTree(dv);
  else if(PC.disk.view==="grandes") renderFiles(dv, "size");
  else if(PC.disk.view==="data") renderFiles(dv, PC.disk.sort==="old"?"old":"new");
  else if(PC.disk.view==="tipos") renderTypes(dv, sum);
  else if(PC.disk.view==="dups") renderDups(dv, sum);
}
async function renderTree(el, path){
  if(path!==undefined) PC.disk.path = path;
  const t = await api("/api/pc/disk/tree" + (PC.disk.path ? "?path="+encodeURIComponent(PC.disk.path) : ""));
  if(!t.path){ PC.disk.path=null; if(path!==null) return renderTree(el, null); el.innerHTML=`<div class="card empty2">Analise um disco primeiro.</div>`; return; }
  PC.disk.path = t.path;
  const root = PC.disks.summary.root, parts = []; let acc = root;
  parts.push([root, root]);
  t.path.slice(root.length).split("\\").filter(Boolean).forEach(x=>{ acc = acc.endsWith("\\") ? acc+x : acc+"\\"+x; parts.push([acc, x]); });
  const max = Math.max(1, ...t.kids.map(k=>k.bytes), t.loose);
  el.innerHTML = `<div class="card"><div class="crumbs">${parts.map(([p,n],i)=>`${i?"›":""}<button onclick='renderTree($("#dv"),${J(p)})'>${esc(n)}</button>`).join("")}</div>
    <h2>${esc(t.path)} <small>${fmtB(t.bytes)} · ${t.files.toLocaleString("pt-BR")} arquivos</small><span class="sp"></span>
      <button class="sm w" onclick='doPC("open",{path:${J(t.path)}})'>Abrir no Explorer</button></h2>
    <p class="hint">Clique numa pasta para entrar. As maiores ficam em cima. Pastas do Windows e de programas ficam protegidas.</p>
    <div class="list">${t.kids.map(k=>`<div class="it folder" onclick='renderTree($("#dv"),${J(k.path)})'><div class="ico app" style="background:var(--sun);color:var(--ink)">📁</div>
      <div style="min-width:0"><div class="t">${esc(k.name)}</div><div class="fbar"><i style="width:${k.bytes/max*100}%"></i></div></div>
      <div class="r"><span class="mini">${k.files.toLocaleString("pt-BR")} arq.<b>${fmtB(k.bytes)}</b></span>
        <button class="sm w" onclick='event.stopPropagation();doPC("open",{path:${J(k.path)}})'>📁</button>
        ${k.protected?`<span class="tag g">protegida</span>`:`<button class="sm r" onclick='event.stopPropagation();recycle([${J(k.path)}],${k.bytes})'>Lixeira</button>`}</div></div>`).join("")}
      ${t.loose?`<div class="it"><div class="ico windows">📄</div><div><div class="t">Arquivos soltos nesta pasta</div><div class="fbar"><i style="width:${t.loose/max*100}%;background:var(--grey2)"></i></div></div><div class="r"><span class="mini"><b>${fmtB(t.loose)}</b></span></div></div>`:""}
      ${t.deeper?`<div class="empty2">Para ver mais fundo, <button class="sm g" onclick='doPC("disk_scan",{root:${J(t.path)}});PC._diskWasBusy=true;setTimeout(diskTick,400)'>analise esta pasta</button></div>`:""}
    </div></div>`;
}
async function renderFiles(el, sort){
  const d = PC.disk, cats = ["", ...(PC.disks.summary.cats||[]).map(c=>c.cat)];
  const r = await api(`/api/pc/disk/files?sort=${sort}&cat=${encodeURIComponent(d.cat)}&q=${encodeURIComponent(d.q)}&offset=${d.offset}&older=${d.older}`);
  const selB = [...d.sel].reduce((s,p)=>s+(d.sizes?.[p]||0),0);
  d.sizes = d.sizes || {}; r.rows.forEach(f=>d.sizes[f.path]=f.size);
  const isDate = PC.disk.view==="data";
  el.innerHTML = `<div class="card">
    <h2>${isDate?"Arquivos por data":"Arquivos grandes"} <small>${r.total.toLocaleString("pt-BR")} arquivos de 1 MB ou mais · ${fmtB(r.bytes)}</small></h2>
    <p class="hint">${isDate?"Arquivos antigos que você esqueceu costumam ocupar muito espaço. ":""}Marque e mande para a <b>Lixeira</b> (dá para restaurar de lá). Arquivos do Windows e de programas ficam protegidos.</p>
    <div class="tbar">
      <input class="in" placeholder="Procurar pelo nome…" value="${esc(d.q)}" onchange="PC.disk.q=this.value;PC.disk.offset=0;renderDisks()">
      <select class="in" onchange="PC.disk.cat=this.value;PC.disk.offset=0;renderDisks()">${cats.map(c=>`<option value="${esc(c)}" ${c===d.cat?"selected":""}>${c||"Todos os tipos"}</option>`).join("")}</select>
      ${isDate?`<span class="seg"><button class="${sort==="new"?"sel":""}" onclick="PC.disk.sort='new';renderDisks()">Mais novos</button><button class="${sort==="old"?"sel":""}" onclick="PC.disk.sort='old';renderDisks()">Mais antigos</button></span>
        <span class="seg">${[[0,"Qualquer data"],[365,"+1 ano"],[730,"+2 anos"]].map(([k,l])=>`<button class="${d.older==k?"sel":""}" onclick="PC.disk.older=${k};PC.disk.offset=0;renderDisks()">${l}</button>`).join("")}</span>`:""}
      <span class="sp" style="flex:1"></span>
      <button class="sm r" ${d.sel.size?"":"disabled"} onclick="recycle([...PC.disk.sel])">Mandar ${d.sel.size||""} para a Lixeira${d.sel.size?` (${fmtB(selB)})`:""}</button>
    </div>
    <div class="list">${r.rows.map(f=>`<div class="it"><div>${f.protected?`<span title="protegido">🔒</span>`:`<input type="checkbox" class="chk" ${d.sel.has(f.path)?"checked":""} onchange='selFile(${J(f.path)},this.checked)'>`}</div>
      <div style="min-width:0"><div class="t" title="${esc(f.path)}">${esc(f.path.split("\\").pop())}</div>
        <div class="d" title="${esc(f.path)}">${esc(f.cat)} · modificado em ${fmtD(f.mtime)} · ${esc(f.path)}</div></div>
      <div class="r"><span class="mini"><b>${fmtB(f.size)}</b></span><button class="sm w" onclick='doPC("open",{path:${J(f.path)}})'>📁</button></div></div>`).join("") || `<div class="empty2">Nada aqui.</div>`}</div>
    <div class="btns">${d.offset?`<button class="sm w" onclick="PC.disk.offset-=100;renderDisks()">← Anteriores</button>`:""}
      ${d.offset+100<r.total?`<button class="sm w" onclick="PC.disk.offset+=100;renderDisks()">Próximos →</button>`:""}</div></div>`;
}
function selFile(p, on){ on ? PC.disk.sel.add(p) : PC.disk.sel.delete(p); const b = document.querySelector("#dv .tbar .r"); renderDisksKeepScroll(); }
function renderDisksKeepScroll(){ const y = window.scrollY; renderDisks(); setTimeout(()=>window.scrollTo({top:y}), 60); }
function renderTypes(el, sum){
  const max = Math.max(1, ...sum.cats.map(c=>c.bytes));
  const col = {"Vídeos":"var(--red)","Imagens":"var(--orange)","Músicas":"var(--sun)","Documentos":"var(--blue)","Compactados":"var(--green)","Programas":"var(--grey2)"};
  el.innerHTML = `<div class="card"><h2>O que ocupa o espaço</h2><p class="hint">Clique num tipo para ver os arquivos dele.</p>
    ${sum.cats.map(c=>`<div class="it folder" onclick='PC.disk.view="grandes";PC.disk.cat=${J(c.cat)};PC.disk.offset=0;renderDisks()'><div class="ico" style="background:${col[c.cat]||"var(--blue2)"}"></div>
      <div><div class="t">${esc(c.cat)}</div><div class="fbar"><i style="width:${c.bytes/max*100}%;background:${col[c.cat]||"var(--blue2)"}"></i></div></div>
      <div class="r"><span class="mini">${c.files.toLocaleString("pt-BR")} arq.<b>${fmtB(c.bytes)}</b></span></div></div>`).join("")}</div>`;
}
async function renderDups(el, sum){
  const busy = PC.disks.state.phase==="hashing";
  if(!sum.dups && !busy){ el.innerHTML = `<div class="card"><h2>Arquivos duplicados</h2>
    <p class="hint">Compara o <b>conteúdo</b> dos arquivos (não só o nome), então só aparecem cópias idênticas. Pode levar alguns minutos.</p>
    <button class="g" onclick="doPC('dups');PC._diskWasBusy=true;setTimeout(diskTick,400)">Procurar duplicados</button></div>`; return; }
  if(busy){ el.innerHTML = ""; return; }
  const r = await api("/api/pc/disk/dups"); PC.disk.dups = r.groups;
  const sel = PC.disk.dupSel, selB = r.groups.reduce((s,g)=>s+g.files.filter(f=>sel.has(f.path)).length*g.size,0);
  el.innerHTML = `<div class="card"><h2>Arquivos duplicados <small>${r.groups.length} grupos · ${fmtB(sum.dups.waste)} em cópias</small></h2>
    <p class="hint">Em cada grupo, os arquivos são <b>idênticos</b>. Marque as cópias que quer apagar (vão para a Lixeira). O botão abaixo marca todas as cópias e <b>mantém o arquivo mais antigo</b> de cada grupo.</p>
    <div class="tbar"><button class="sm y" onclick="dupAuto()">Marcar cópias (manter o mais antigo)</button><button class="sm w" onclick="PC.disk.dupSel.clear();renderDisks()">Desmarcar</button>
      <span style="flex:1"></span><button class="sm r" ${sel.size?"":"disabled"} onclick="recycle([...PC.disk.dupSel])">Mandar ${sel.size||""} para a Lixeira${sel.size?` (${fmtB(selB)})`:""}</button></div>
    ${r.groups.slice(0,150).map(g=>`<div class="grp"><div class="h">${g.count} cópias · ${fmtB(g.size)} cada · <span style="color:var(--red2)">${fmtB(g.waste)} sobrando</span> <span class="tag g">${esc(g.cat)}</span></div>
      ${g.files.map((f,i)=>`<div class="it" style="padding:5px 0"><div><input type="checkbox" class="chk" ${sel.has(f.path)?"checked":""} onchange='this.checked?PC.disk.dupSel.add(${J(f.path)}):PC.disk.dupSel.delete(${J(f.path)});renderDisksKeepScroll()'></div>
        <div style="min-width:0"><div class="d" style="color:var(--ink)" title="${esc(f.path)}">${esc(f.path)}</div><div class="d">${fmtD(f.mtime)}${i===0?" · mais antigo":""}</div></div>
        <div class="r"><button class="sm w" onclick='doPC("open",{path:${J(f.path)}})'>📁</button></div></div>`).join("")}</div>`).join("") || `<div class="empty2">Nenhum duplicado encontrado. 👍</div>`}</div>`;
}
function dupAuto(){ (PC.disk.dups||[]).forEach(g=>g.files.slice(1).forEach(f=>PC.disk.dupSel.add(f.path))); renderDisksKeepScroll(); }
async function recycle(paths, bytes){
  const n = paths.length; if(!n) return;
  if(!await confirmBox(`Mandar ${n} item(ns) para a Lixeira?`, `${bytes?fmtB(bytes)+". ":""}Eles saem da pasta mas ficam na Lixeira; dá para restaurar de lá. O espaço só volta quando você esvaziar a Lixeira (aba Limpeza rápida).`, "Mandar para a Lixeira")) return;
  const j = await doPC("recycle", {paths});
  (j.gone||[]).forEach(p=>{ PC.disk.sel.delete(p); PC.disk.dupSel.delete(p); });
  PC.disks = await api("/api/pc/disks"); renderDisksKeepScroll();
}
async function renderCleanup(el){
  if(!PC.disk.clean){ el.innerHTML = `<div class="card empty2"><span class="spin"></span> Medindo o que dá para limpar…</div>`;
    PC.disk.clean = await api("/api/pc/cleanup"); if(PC.disk.view!=="limpeza"||PC.tab!=="discos") return; }
  const c = PC.disk.clean, sel = PC.disk.cleanSel;
  const tot = c.items.filter(i=>sel.has(i.id)).reduce((s,i)=>s+i.bytes,0);
  el.innerHTML = `<div class="card"><h2>Limpeza rápida <small>não precisa analisar o disco</small><span class="sp"></span><button class="sm w" onclick="PC.disk.clean=null;renderDisks()">Medir de novo</button></h2>
    <p class="hint">Só coisas que o Windows e os programas <b>recriam sozinhos</b>. Nenhum documento, foto ou senha é apagado. Itens com 🛡 pedem permissão de administrador.</p>
    <div class="list">${c.items.map(i=>`<div class="it"><div><input type="checkbox" class="chk" ${sel.has(i.id)?"checked":""} onchange="this.checked?PC.disk.cleanSel.add('${i.id}'):PC.disk.cleanSel.delete('${i.id}');renderDisksKeepScroll()"></div>
      <div style="min-width:0"><div class="t">${esc(i.name)} ${i.admin&&!c.admin?"🛡":""}</div><div class="d" style="white-space:normal">${esc(i.why)}</div></div>
      <div class="r"><span class="mini">${(i.files||0).toLocaleString("pt-BR")} arq.<b>${fmtB(i.bytes)}</b></span></div></div>`).join("")}</div>
    <div class="btns"><button class="g" ${sel.size?"":"disabled"} onclick="doCleanup()">Limpar selecionados (${fmtB(tot)})</button></div></div>`;
}
async function doCleanup(){
  const ids = [...PC.disk.cleanSel];
  const rec = ids.includes("recycle") ? " A Lixeira será esvaziada: o que está nela some de vez." : "";
  if(!await confirmBox("Limpar agora?", "Os arquivos selecionados serão apagados de vez (são temporários e o Windows recria)." + rec, "Limpar", "g")) return;
  await doPC("cleanup", {ids});
  const w = setInterval(async()=>{ if(!PC.live.busy){ clearInterval(w); PC.disk.clean=null; PC.disks = await api("/api/pc/disks"); if(PC.tab==="discos") renderDisks(); } }, 1500);
}

/* ---------------- segurança ---------------- */
async function loadSec(){ const j = await api("/api/pc/security"); PC.sec = j.result; if(PC.tab==="seg") renderSec(); }
function renderSec(){
  const L = PC.live||{}, R = PC.sec, running = L.sec_running;
  if(R===null && !running && !PC._secLoaded){ PC._secLoaded = true; loadSec(); }
  const susp = (L.procs||[]).filter(p=>p.risk);
  const d = R?.defender||{};
  const card = (k, ok, v, s) => `<div class="stat"><div class="k">${k}</div><div class="v" style="font-size:22px;color:${ok===null?"var(--muted)":ok?"var(--green2)":"var(--red)"}">${v}</div><div class="s">${s||""}</div></div>`;
  let h = `<div class="card"><h2>Segurança <span class="sp"></span>
      <button class="g" ${running||L.busy?"disabled":""} onclick="doPC('sec_scan');PC._secWasBusy=true">${running?`<span class="spin"></span> Verificando…`:R?"Verificar de novo":"Verificar agora"}</button></h2>
    <p class="hint">Confere o antivírus, o firewall, as ameaças que o Windows já encontrou, a assinatura digital dos programas abertos,
      programas piratas e rastros do vírus que sequestra arquivos. <b>Não apaga nada sozinho.</b> Leva de 20 s a 1 min.
      ${R?` Última verificação: ${new Date(R.t*1000).toLocaleTimeString("pt-BR")}.`:""}${!L.admin?` Para ver as exclusões do antivírus, <a href="#" onclick="askAdmin();return false">abra como administrador</a>.`:""}</p></div>`;
  if(R){
    const fw = R.firewall||[], fwOn = fw.length && fw.every(x=>x.on);
    const others = (R.av||[]).filter(a=>!/defender/i.test(a.n));
    h += `<div class="stats" style="margin-bottom:16px">
      ${card("Antivírus", others.length?true:!!d.av, others.length?esc(others[0].n):d.av?"Ligado":"Desligado", others.length?"de outro fabricante":"Windows Defender")}
      ${card("Proteção em tempo real", others.length?null:!!d.rt, d.rt?"Ligada":others.length?"—":"Desligada")}
      ${card("Firewall", fw.length?fwOn:null, fw.length?(fwOn?"Ligado":"Desligado em parte"):"—", fw.map(x=>x.n+": "+(x.on?"sim":"não")).join(" · "))}
      ${card("Atualização", d.sigAge==null?null:d.sigAge<=3, d.sigAge==null?"—":d.sigAge===0?"Hoje":`há ${d.sigAge} dias`, `verificação rápida há ${d.quickAge??"?"} dia(s)`)}
      ${card("Ameaças já achadas", (R.threats||[]).length===0, (R.threats||[]).length, "no histórico do Windows")}
    </div>
    <div class="card"><h2>Antivírus do Windows</h2>
      <p class="hint">A <b>verificação offline</b> é a mais forte contra mineradores e vírus escondidos: o PC reinicia e o antivírus verifica antes do Windows abrir (uns 15 min). Salve tudo antes.</p>
      <div class="btns"><button class="sm" ${L.busy?"disabled":""} onclick="defScan('quick')">Verificação rápida</button>
        <button class="sm" ${L.busy?"disabled":""} onclick="defScan('full')">Verificação completa</button>
        <button class="sm o" ${L.busy?"disabled":""} onclick="defScan('offline')">Verificação offline (reinicia)</button>
        <button class="sm w" ${L.busy?"disabled":""} onclick="defScan('update')">Atualizar proteção</button></div></div>`;
    if((R.threats||[]).length) h += `<div class="card"><h2>Ameaças que o antivírus já encontrou</h2><div class="list">${R.threats.map(t=>`<div class="it"><div class="ico ${t.ok?"baixo":"alto"}">!</div>
      <div style="min-width:0"><div class="t">${esc(t.name||t.id)}<span class="tag ${t.ok?"ok":"alto"}">${t.ok?"resolvida":"não resolvida"}</span></div>
        <div class="d" title="${esc((t.res||[]).join("; "))}">${fmtD(t.when)} · ${esc((t.res||[]).join("; "))}</div></div><div class="r"></div></div>`).join("")}</div></div>`;
    const f = R.files||{};
    const oldRansom = f.ransom_newest && (Date.now()/1000 - f.ransom_newest) > 365*86400;
    if(f.ransom_count) h += `<div class="card" style="border-color:var(--red2)"><h2 style="color:var(--red2)">${oldRansom?"Restos de um vírus antigo (STOP/Djvu)":"Rastros do vírus que sequestra arquivos (STOP/Djvu)"}</h2>
      <p class="hint">Achei ${f.ransom_count} arquivo(s) de resgate ou decodificador.
      ${oldRansom?`O bilhete mais novo é de <b>${new Date(f.ransom_newest*1000).toLocaleDateString("pt-BR",{month:"2-digit",year:"numeric"})}</b> e nada foi sequestrado depois disso: é resto de uma infecção antiga, <b>não um vírus ativo</b>. Os arquivos sequestrados daquela época só voltam com a chave dos criminosos.`
      :`Esse vírus costuma instalar junto <b>ladrões de senha e mineradores</b>. Rode a <b>verificação offline</b> e troque as senhas importantes <b>usando outro aparelho</b>.`}</p>
      ${f.ransom_notes.slice(0,8).map(p=>`<div class="d" style="font-size:12.5px">• ${esc(p)} <button class="sm w" onclick='doPC("open",{path:${J(p)}})'>📁</button></div>`).join("")}</div>`;
    if((f.piracy||[]).length) h += `<div class="card"><h2>Programas piratas, cracks e ativadores <small>${f.piracy.length}</small></h2>
      <p class="hint">É a origem mais comum de <b>mineradores de criptomoeda</b> (o que deixa o processador em 100% e esquenta o PC). Considere apagar e desinstalar.</p>
      <div class="list">${f.piracy.map(x=>`<div class="it"><div class="ico ${x.why==="mineração"?"alto":"medio"}">${x.dir?"📁":"!"}</div>
        <div style="min-width:0"><div class="t">${esc(x.path.split("\\").pop())}<span class="tag ${x.why==="mineração"?"alto":"medio"}">${esc(x.why)}</span></div><div class="d" title="${esc(x.path)}">${fmtD(x.mtime)} · ${esc(x.path)}</div></div>
        <div class="r"><button class="sm w" onclick='doPC("open",{path:${J(x.path)}})'>📁</button><button class="sm r" onclick='recycleSec(${J(x.path)})'>Lixeira</button></div></div>`).join("")}</div></div>`;
    if((R.exclusions||[]).length) h += `<div class="card"><h2>Pastas escondidas do antivírus <small>${R.exclusions.length}</small></h2>
      <p class="hint">Vírus costumam criar exclusões para não serem achados. Se você não reconhece, remova em <b>Segurança do Windows → Proteção contra vírus → Configurações → Exclusões</b>.</p>
      ${R.exclusions.map(e=>`<div class="d" style="font-size:13px">• ${esc(e)}</div>`).join("")}</div>`;
    if((R.hosts||{}).bad?.length) h += `<div class="card"><h2>Arquivo hosts alterado</h2><p class="hint">Estas linhas bloqueiam ou desviam sites de segurança/bancos:</p>${R.hosts.bad.map(l=>`<div class="d">• ${esc(l)}</div>`).join("")}</div>`;
    h += `<div class="card"><h2>Programas abertos sem assinatura digital <small>${(R.unsigned||[]).length} de ${R.checked}</small></h2>
      <p class="hint">Assinatura digital mostra quem fez o programa. Sem ela não quer dizer vírus (muitos programas pequenos não têm), mas é um sinal para conferir.</p>
      ${(R.unsigned||[]).slice(0,40).map(u=>`<div class="d" style="font-size:12.5px" title="${esc(u.path)}">• ${esc(u.path)} <span class="tag g">${esc(u.st)}</span></div>`).join("") || `<div class="empty2">Todos assinados. 👍</div>`}</div>`;
  }
  h += `<div class="card"><h2>Alertas ao vivo <small>processos com algo estranho agora</small></h2>${susp.length?susp.map(p=>`<div class="it"><div class="ico ${p.risk}">!</div>
      <div style="min-width:0"><div class="t">${esc(p.name)}<span class="tag ${p.risk}">${p.risk}</span></div><div class="d">${esc(p.path||p.exe)}</div><div class="why ${p.risk}">⚠ ${esc(p.reasons.join("; "))}</div></div>
      <div class="r">${p.critical?"":`<button class="sm r" onclick='killProc(${J(p.key)},${J(p.name)})'>Encerrar</button>`}<button class="sm w" onclick='doPC("web",{name:${J(p.exe)}})'>🔎</button></div></div>`).join("")
    : `<div class="empty2">Nenhum processo suspeito rodando agora.</div>`}</div>`;
  $("#pcBody").innerHTML = h;
}
async function defScan(kind){
  if(kind==="offline" && !await confirmBox("Reiniciar para a verificação offline?", "O PC vai reiniciar <b>na hora</b> e o antivírus vai verificar tudo antes do Windows abrir (uns 15 minutos). Salve e feche tudo antes.", "Reiniciar e verificar")) return;
  if(kind==="full" && !await confirmBox("Verificação completa?", "Verifica todos os arquivos. Pode levar mais de 1 hora e deixar o PC mais lento enquanto isso. Você pode continuar usando.", "Começar", "g")) return;
  doPC("defender", {kind});
}
async function recycleSec(p){ await recycle([p]); loadSec(); }

/* ---------------- relatório ---------------- */
function renderReport(){
  const R = PC.report, L = PC.live||{};
  $("#pcBody").innerHTML = `<div class="row rh"><div class="card"><h2>Relatório para IA</h2>
      <p class="hint">Junta tudo o que o CalorScan viu (processador, programas, temperatura, inicialização, apps, segurança e discos) num arquivo <b>.md</b>
      com as instruções prontas. É só copiar e colar no Claude, ChatGPT ou Gemini.</p>
      <p class="hint">Dica: deixe o CalorScan aberto <b>pelo menos 5 minutos</b> usando o PC normalmente e rode a <a href="#" onclick="showTab('seg');return false">verificação de segurança</a> antes.
      Monitorando há <b>${L.session_min??0} min</b>${L.sec_running?" · verificando segurança…":L.sec_done?" · segurança verificada ✓":" · segurança ainda não verificada"}.</p>
      <p class="hint">A pasta do seu usuário é trocada por %USERPROFILE% no texto. Nada sai do PC: você decide onde colar.</p>
      <div class="btns"><button class="g" onclick="genReport()">${R?"Gerar de novo":"Gerar relatório"}</button></div>
      ${R?`<div class="btns" style="margin-top:12px"><button class="y" onclick="copyReport()">Copiar</button><button onclick="saveReport()">Salvar .md</button>
        <button class="w sm" style="background:#fff;color:var(--ink)" onclick="fetch('/api/open-reports',{method:'POST',headers:{'X-Token':TOKEN}})">Abrir pasta</button></div>
        <p class="hint" style="margin-top:10px">Salvo em ${esc(R.file)}</p>`:""}</div>
    <div class="card"><h2>${R?`Prévia <small>nota ${R.score}/100 · ${esc(R.label)}</small>`:"Prévia"}</h2>
      ${R?`<pre class="md">${esc(R.md)}</pre>`:`<div class="empty2">Clique em <b>Gerar relatório</b>.</div>`}</div></div>`;
}
async function genReport(){ toast("Gerando…"); PC.report = await api("/api/pc/report"); renderReport(); toast("Relatório pronto ✓"); }
async function copyReport(){
  try{ await navigator.clipboard.writeText(PC.report.md); toast("Copiado! Cole no chat da IA"); }
  catch{ const a=document.createElement("textarea");a.value=PC.report.md;document.body.appendChild(a);a.select();document.execCommand("copy");a.remove();toast("Copiado!"); }
}
function saveReport(){
  const a=document.createElement("a"); a.href=URL.createObjectURL(new Blob([PC.report.md],{type:"text/markdown"}));
  a.download="CalorScan_PC_relatorio_para_IA.md"; a.click();
}
