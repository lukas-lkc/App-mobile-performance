package br.calorscan

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import br.calorscan.Ui.dp
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private var tab = 0
    private var liveTemp: TextView? = null
    private var liveMood: TextView? = null
    private var mascot: MascotView? = null
    private val sampler by lazy { LiveReader(this) }

    private val live = object : Runnable {
        override fun run() { updateLive(); handler.postDelayed(this, 3000) }
    }
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, res ->
        toast(if (res == PackageManager.PERMISSION_GRANTED) "Shizuku conectado ✓" else "Permissão negada no Shizuku")
        show(tab)
    }

    // ================================================================ estrutura
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.SKY1
        window.navigationBarColor = Ui.GRASS2
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.SKY1, Ui.SKY2))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.GRASS)
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        val grassTop = View(this).apply { setBackgroundColor(Ui.GRASS2) }
        root.addView(grassTop, LinearLayout.LayoutParams(-1, dp(5)))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0); nav.setPadding(dp(8), dp(8), dp(8), dp(10) + bars.bottom); ins
        }
        GuardService.channels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        GuardService.start(this)
        Work.reschedule(this)
        handleIntent(intent, build = false) // onResume monta a tela
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent, build = true) }

    private fun handleIntent(i: Intent, build: Boolean) {
        if (i.getBooleanExtra("resume_work", false)) {
            Work.selected(this)?.let { Work.setPaused(this, it, false, interactive = true) }
        }
        tab = i.getIntExtra("tab", tab)
        if (build) show(tab)
    }

    override fun onResume() { super.onResume(); show(tab); handler.post(live) }
    override fun onPause() { handler.removeCallbacks(live); super.onPause() }
    override fun onDestroy() { runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }; super.onDestroy() }

    private fun buildNav() {
        nav.removeAllViews()
        listOf("🌡️\nAgora", "❄️\nEsfriar", "💼\nTrabalho", "📷\nFotos", "⚙️\nConfig").forEachIndexed { i, s ->
            val b = Ui.button(this, s, if (i == tab) Ui.Kind.SUN_ else Ui.Kind.WHITE_) { show(i) }.apply {
                textSize = 11.5f; setPadding(0, dp(6), 0, dp(11)); setLineSpacing(0f, 0.95f)
            }
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    private fun show(t: Int) {
        tab = t
        buildNav()
        content.removeAllViews()
        liveTemp = null; liveMood = null; mascot = null
        when (t) { 0 -> buildNow(); 1 -> buildCold(); 2 -> buildWork(); 3 -> buildPhotos(); else -> buildConfig() }
        scroll.scrollTo(0, 0)
    }

    // ================================================================ helpers
    private fun add(v: View, bottom: Int = 14) = content.addView(v, Ui.lp(this, bottom = bottom))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun header(title: String, sub: String) {
        add(Ui.title(this, title, 26f, Ui.WHITE).apply {
            setShadowLayer(0.01f, dp(3).toFloat(), dp(3).toFloat(), Ui.INK)
        }, 2)
        add(Ui.body(this, sub, 14f, Ui.INK), 14)
    }
    private fun card(face: Int = Ui.WHITE, build: LinearLayout.() -> Unit) = add(Ui.card(this, face).apply(build))
    private fun LinearLayout.t(s: String, size: Float = 17f) = addView(Ui.title(this@MainActivity, s, size), Ui.lp(this@MainActivity, bottom = 4))
    /** Texto: cinza nos cartoes brancos, escuro nos coloridos (contraste). */
    private fun LinearLayout.p(s: CharSequence, bottom: Int = 8) = addView(
        Ui.body(this@MainActivity, s, color = if ((tag as? Int ?: Ui.WHITE) == Ui.WHITE) Ui.MUTED else Ui.INK),
        Ui.lp(this@MainActivity, bottom = bottom))
    private fun LinearLayout.btn(s: String, kind: Ui.Kind = Ui.Kind.BLUE_, top: Int = 6, f: () -> Unit) =
        addView(Ui.button(this@MainActivity, s, kind, f), Ui.lp(this@MainActivity, top = top, bottom = 4))
    private fun open(i: Intent) = runCatching { startActivity(i) }.onFailure { toast("Não consegui abrir essa tela neste celular") }
    private fun appDetails(p: String) = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p")))

    /** Linha: textos a esquerda + chave a direita. */
    private fun toggleRow(title: String, sub: String?, checked: Boolean, enabled: Boolean = true,
                          onTitle: (() -> Unit)? = null, change: (Boolean) -> Unit): View {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.title(this@MainActivity, title, 15f))
            sub?.let { addView(Ui.body(this@MainActivity, it, 12.5f)) }
            onTitle?.let { f -> setOnClickListener { f() } }
        }
        val tg = BlockToggle(this).apply { this.checked = checked; isEnabled = enabled; onChange = change }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tg, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
    }

    private fun check(ok: Boolean, s: String) = Ui.body(this, (if (ok) "✅  " else "⬜  ") + s, 14.5f, Ui.INK)

    // ================================================================ AGORA
    private fun buildNow() {
        val g = Cold.grants(this)
        card(Ui.SUN) {
            val m = MascotView(this@MainActivity).also { mascot = it }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(this@MainActivity, "--", 44f).also { liveTemp = it })
                addView(Ui.title(this@MainActivity, "", 16f).also { liveMood = it })
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(m); addView(texts, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16) })
            })
            ObjectAnimator.ofFloat(m, "translationY", 0f, -dp(6).toFloat()).apply {
                duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        }
        updateLive()
        if (!g.usage || !g.notif) card(Ui.ORANGE) {
            t("Falta uma permissão")
            p("Sem ela o app não sabe qual app está aberto nem consegue avisar. Leva 10 segundos.")
            btn("Resolver agora", Ui.Kind.WHITE_) { show(4) }
        }
        val recent = Store.samples(this, System.currentTimeMillis() - 3 * 3_600_000).map { it.c }
        card {
            t("Últimas 3 horas")
            addView(ChartView(this@MainActivity).apply {
                points = if (recent.size > 60) recent.chunked((recent.size + 59) / 60).map { it.max() } else recent
            }, Ui.lp(this@MainActivity, top = 4, bottom = 4))
            p(if (recent.size < 2) "Ainda medindo… volte em alguns minutos." else "Tracejado = 43 °C, onde o celular começa a se proteger.", 0)
        }
        add(Ui.title(this, "O que o app descobriu", 20f, Ui.INK), 8)
        val loading = Ui.body(this, "Analisando…"); add(loading)
        Thread {
            val fs = runCatching { Diagnosis.findings(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (tab != 0) return@runOnUiThread
                content.removeView(loading)
                val anchor = content.childCount
                if (fs.isEmpty()) add(Ui.card(this).apply { p("Tudo tranquilo por enquanto. O app continua de olho.", 0) })
                fs.forEachIndexed { i, f -> content.addView(findingCard(f), anchor + i, Ui.lp(this, bottom = 14)) }
                card(Ui.WHITE) {
                    t("Pedir ajuda a uma IA")
                    p("Gera um resumo com as medições e as instruções prontas. É só colar no ChatGPT, Claude, Gemini…")
                    addView(Ui.row(this@MainActivity,
                        Ui.button(this@MainActivity, "Compartilhar", Ui.Kind.SUN_) { shareMd() },
                        Ui.button(this@MainActivity, "Copiar", Ui.Kind.WHITE_) { copyMd() }), Ui.lp(this@MainActivity, top = 6, bottom = 0))
                }
            }
        }.start()
    }

    private fun findingCard(f: Finding): View = Ui.card(this).apply {
        val color = when (f.level) { Finding.DANGER -> Ui.RED; Finding.WARN -> Ui.ORANGE; Finding.INFO -> Ui.BLUE; else -> Ui.GREEN }
        val dot = View(this@MainActivity).apply {
            background = GradientDrawable().apply { setColor(color); setStroke(dp(3), Ui.INK) }
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(Ui.title(this@MainActivity, f.title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        }, Ui.lp(this@MainActivity, bottom = 4))
        p(f.text, if (f.tab >= 0) 4 else 0)
        if (f.tab >= 0) btn("Resolver →", Ui.Kind.WHITE_) { show(f.tab) }
    }

    private fun updateLive() {
        val t = sampler.temp()
        mascot?.temp = t
        liveTemp?.text = t?.let { Diagnosis.f1(it) + " °C" } ?: "--"
        liveMood?.text = buildString {
            append(t?.let { "Bateria " + Diagnosis.mood(it) } ?: "")
            append("\nSistema: ${Diagnosis.thermalName(sampler.status())}")
            append("\nLigado há ${SystemClock.elapsedRealtime() / 60000} min")
        }
    }

    private fun shareMd() {
        Thread {
            val md = Diagnosis.markdown(this)
            runOnUiThread {
                open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "CalorScan — resumo para IA").putExtra(Intent.EXTRA_TEXT, md), "Enviar resumo para…"))
            }
        }.start()
    }

    private fun copyMd() {
        Thread {
            val md = Diagnosis.markdown(this)
            runOnUiThread {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CalorScan", md))
                toast("Copiado! Cole no chat da IA.")
            }
        }.start()
    }

    // ================================================================ ESFRIAR
    private fun buildCold() {
        header("Esfriar", "Escolha o que fica mais leve. Nada é apagado e dá para desfazer.")
        val g = Cold.grants(this)
        val plan = Cold.plan(this)
        val pending = plan.commands.size
        val shizuku = Cold.shizukuAllowed()
        card(if (pending == 0) Ui.GREEN else Ui.SUN) {
            if (pending == 0) { t("Tudo aplicado ✓"); p("Suas escolhas abaixo já estão valendo.", 0) }
            else {
                t("$pending ajuste(s) esperando")
                when {
                    shizuku -> { p("O Shizuku está ativo: aplico agora mesmo."); btn("Aplicar agora", Ui.Kind.GREEN_) { applyShizuku() } }
                    else -> p("Para aplicar sozinho, use uma das opções:\n• Notebook: abra o CalorScan PC e clique em “Aplicar ajustes”.\n" +
                        "• Sem notebook: ative o Shizuku (aba Config).\n• À mão: toque no nome de cada app marcado e vá em Bateria → Restrita.", 0)
                }
            }
        }

        // 1 — apps
        val listBox = Ui.card(this)
        add(listBox)
        listBox.apply {
            t("Não deixar iniciar sozinho")
            p("Os apps marcados não rodam em segundo plano e não iniciam junto com o celular. Eles abrem normalmente quando você toca neles; " +
                "só as notificações podem atrasar. Desmarque os que precisam avisar na hora (Pix, mensagens).")
            addView(toggleRow("Ativado", "Desligue para liberar todos de uma vez", Store.getBool(this@MainActivity, "boot_block", true)) { on ->
                Store.putBool(this@MainActivity, "boot_block", on)
                Cold.setWanted(this@MainActivity, if (on) Store.getSet(this@MainActivity, "sel_restrict") else emptySet())
                show(1)
            })
            p("Carregando seus apps…", 0)
        }
        Thread {
            val items = runCatching { Cold.candidates(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (tab != 1) return@runOnUiThread
                listBox.removeViewAt(listBox.childCount - 1)
                if (!Store.getBool(this, "sel_init")) { // primeira vez: marca as sugestoes
                    Store.putSet(this, "sel_restrict", items.filter { it.suggested }.map { it.pkg }.toSet())
                    Store.putBool(this, "sel_init", true)
                    if (Store.getBool(this, "boot_block", true)) Cold.setWanted(this, Store.getSet(this, "sel_restrict"))
                }
                val sel = Store.getSet(this, "sel_restrict")
                val applied = Cold.restricted(this)
                val master = Store.getBool(this, "boot_block", true)
                if (items.isEmpty()) listBox.addView(Ui.body(this, "Nenhum app instalado por você foi encontrado."), Ui.lp(this, top = 6, bottom = 0))
                items.forEach { a ->
                    val state = if (a.pkg in applied) "✓ restrito" else if (a.pkg in sel && master) "⏳ esperando aplicar" else null
                    listBox.addView(toggleRow(a.label, listOfNotNull(a.reason, state).joinToString(" · ").ifEmpty { null },
                        a.pkg in sel, enabled = master, onTitle = { appDetails(a.pkg) }) { on ->
                        val s = Store.getSet(this, "sel_restrict").toMutableSet()
                        val ex = Cold.exceptions(this).toMutableSet()
                        if (on) { s += a.pkg; ex -= a.pkg } else { s -= a.pkg; if (a.reason != null) ex += a.pkg }
                        Store.putSet(this, "sel_restrict", s); Cold.setExceptions(this, ex)
                        if (Store.getBool(this, "boot_block", true)) Cold.setWanted(this, s)
                    })
                }
                listBox.addView(Ui.body(this, "Toque no nome de um app para abrir a página dele no Android.", 12.5f), Ui.lp(this, top = 6, bottom = 0))
            }
        }.start()

        // 2 — Samsung
        val sams = Cold.SAMSUNG.filter { Cold.installed(this, it.first) }
        if (sams.isNotEmpty()) card {
            t("Recursos da Samsung")
            p("Rodam o tempo todo, em todos os perfis. Ligado = desativar.")
            sams.forEach { (pkg, name) ->
                val on = pkg in Cold.wantOff(this@MainActivity)
                val now = if (Cold.pkgEnabled(this@MainActivity, pkg)) "agora: funcionando" else "agora: desativado ✓"
                addView(toggleRow("Desativar $name", now, on, onTitle = { appDetails(pkg) }) { v ->
                    val s = Cold.wantOff(this@MainActivity).toMutableSet(); if (v) s += pkg else s -= pkg
                    Cold.setWantOff(this@MainActivity, s)
                })
            }
            p("À mão: abra Configurações, toque na lupa e procure “Bixby” ou “Sugestões”.", 0)
        }

        // 3 — busca Wi-Fi/Bluetooth
        card {
            t("Busca de Wi-Fi e Bluetooth")
            p("Não é o Wi-Fi em si: ele continua ligado e conectando sozinho. Esta opção faz o celular procurar redes " +
                "mesmo com o Wi-Fi desligado, só para melhorar a localização. Efeito no calor: pequeno.")
            addView(toggleRow("Desligar a busca", if (Cold.scanOn(this@MainActivity)) "agora: buscando" else "agora: desligada ✓",
                Cold.wantScanOff(this@MainActivity)) { v ->
                Cold.setWantScanOff(this@MainActivity, v)
                if (!g.secure) toast("Será aplicado junto com os outros ajustes")
                show(1)
            })
        }

        card(Ui.WHITE) {
            t("Desfazer tudo")
            p("Volta tudo como era antes do CalorScan.")
            btn("Desfazer tudo", Ui.Kind.RED_) {
                Store.putBool(this@MainActivity, "boot_block", false)
                Cold.setWanted(this@MainActivity, emptySet()); Cold.setWantOff(this@MainActivity, emptySet())
                Cold.undoScan(this@MainActivity)
                if (Cold.shizukuAllowed()) applyShizuku() else { toast("Marcado. Aplique pelo notebook ou Shizuku."); show(1) }
            }
        }
    }

    private fun applyShizuku() {
        toast("Aplicando…")
        Thread { val msg = Cold.applyWithShizuku(this); runOnUiThread { toast(msg); show(tab) } }.start()
    }

    // ================================================================ TRABALHO
    private fun buildWork() {
        header("Trabalho", "Pausar os apps de trabalho fora do expediente: menos coisa rodando e menos notificação.")
        val profiles = Work.profiles(this)
        if (profiles.isEmpty()) {
            card { t("Sem perfil de trabalho"); p("Este celular não tem perfil de trabalho. Nada para fazer aqui 🙂", 0) }
            return
        }
        val sel = Work.selected(this)!!
        val can = Work.canControl(this)
        card {
            t(if (profiles.size > 1) "Escolha o perfil de trabalho" else "Seu perfil de trabalho")
            profiles.forEach { u ->
                val apps = Work.profileApps(this@MainActivity, u)
                val paused = Work.isPaused(this@MainActivity, u)
                val desc = (if (paused) "⏸ pausado" else "▶ ligado") + " · " +
                    apps.take(4).joinToString(", ").ifEmpty { "sem apps visíveis" }
                if (profiles.size > 1) addView(toggleRow("Perfil com ${apps.firstOrNull() ?: "apps"}", desc, u == sel) {
                    Work.select(this@MainActivity, u); Work.reschedule(this@MainActivity); show(2)
                }) else p(desc)
            }
            val paused = Work.isPaused(this@MainActivity, sel)
            btn(if (paused) "Ligar agora" else "Pausar agora", if (paused) Ui.Kind.GREEN_ else Ui.Kind.ORANGE_) {
                val err = Work.setPaused(this@MainActivity, sel, !paused, interactive = true)
                if (err != null && err != "precisa do seu PIN") toast("Não deu: $err. Use o atalho “Apps de trabalho” no painel rápido.")
                handler.postDelayed({ show(2) }, 800)
            }
            if (!can) p("Para o app pausar e ligar sozinho no horário, ele precisa ser liberado uma vez (aba Config). " +
                "Até lá ele só te lembra no horário.", 0)
        }
        card {
            addView(toggleRow("Agendar", "Liga e pausa sozinho nos horários abaixo", Work.enabled(this@MainActivity)) { v ->
                Work.setEnabled(this@MainActivity, v); show(2)
            })
            p("Toque no dia para ativar ou desativar. Toque em um horário para escolher outro.", 4)
            val days = Work.days(this@MainActivity)
            Work.DAY_NAMES.forEach { (d, name) ->
                val day = days[d]!!
                val dayBtn = Ui.button(this@MainActivity, if (day.on) "✓ $name" else name, if (day.on) Ui.Kind.SUN_ else Ui.Kind.WHITE_) {
                    Work.setDay(this@MainActivity, d, day.copy(on = !day.on)); show(2)
                }
                fun timeBtn(label: String, min: Int, start: Boolean) =
                    Ui.button(this@MainActivity, "$label\n${hhmm(min)} ✏️", Ui.Kind.WHITE_) {
                        pickTime(min) { m ->
                            val nd = if (start) day.copy(start = m, on = true) else day.copy(end = m, on = true)
                            if (nd.end <= nd.start) {
                                toast("A pausa precisa ser depois do horário de ligar (${hhmm(nd.start)}).")
                            } else {
                                Work.setDay(this@MainActivity, d, nd)
                                toast("$name: liga às ${hhmm(nd.start)} e pausa às ${hhmm(nd.end)}")
                            }
                            show(2)
                        }
                    }.apply {
                        textSize = 13.5f; setLineSpacing(0f, 0.95f)
                        alpha = if (day.on) 1f else 0.55f // dia desligado: ainda da para tocar (e ativa o dia)
                    }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(dayBtn.apply { textSize = 14f; setPadding(0, dp(8), 0, dp(14)) }, LinearLayout.LayoutParams(dp(82), -2))
                    addView(timeBtn("Liga às", day.start, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                    addView(timeBtn("Pausa às", day.end, false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                }, Ui.lp(this@MainActivity, top = 4, bottom = 4))
            }
            val nx = if (Work.enabled(this@MainActivity)) Work.next(this@MainActivity) else null
            p(nx?.let { (t, pause) -> "Próximo: ${if (pause) "pausa" else "liga"} ${whenText(t)}." }
                ?: "Agenda desligada.", 0)
        }
        card(Ui.WHITE) {
            t("Bom saber")
            p("• Quando o celular liga, o app já aplica o horário: fora do expediente o perfil nem inicia, o que alivia o aquecimento.\n" +
                "• Se o perfil tiver senha própria, o Android pode pedir o PIN para ligar. O app avisa com uma notificação.\n" +
                "• Algumas empresas limitam quanto tempo o perfil pode ficar pausado. Se o celular avisar, encurte a pausa.", 0)
        }
    }

    private fun hhmm(m: Int) = String.format("%02d:%02d", m / 60, m % 60)
    private fun whenText(t: Long): String {
        val days = ((t - System.currentTimeMillis()) / 86_400_000L).toInt()
        val dayTxt = when {
            android.text.format.DateUtils.isToday(t) -> "hoje"
            android.text.format.DateUtils.isToday(t - 86_400_000L) -> "amanhã"
            else -> java.text.SimpleDateFormat("EEEE", java.util.Locale("pt", "BR")).format(t) + if (days > 6) " que vem" else ""
        }
        return "$dayTxt às ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(t)}"
    }
    private fun pickTime(cur: Int, done: (Int) -> Unit) =
        TimePickerDialog(this, { _, h, m -> done(h * 60 + m) }, cur / 60, cur % 60, true).show()

    // ================================================================ FOTOS
    private fun buildPhotos() {
        header("Fotos", "Menos pós-processamento: pele, olhos e cores mais naturais.")
        val natural = Photos.natural(this)
        card(if (natural) Ui.GREEN else Ui.WHITE) {
            addView(toggleRow("Fotos naturais", if (natural) "Ligado: siga a lista abaixo" else "Desligado: câmera no padrão Samsung", natural) { v ->
                Photos.setNatural(this@MainActivity, v); show(3)
            })
            p("A Samsung não deixa nenhum app mudar as configurações da câmera (nem com o notebook). " +
                "Então o CalorScan te guia: abra a câmera, faça cada item e marque aqui. O app não consegue conferir sozinho.", 0)
        }
        val steps = Photos.steps(natural)
        val done = Photos.done(this)
        card {
            t(if (natural) "Para deixar natural" else "Para voltar ao padrão Samsung")
            p("Os nomes podem variar um pouco conforme a versão do One UI. Se não achar, procure algo parecido no mesmo lugar.")
            steps.forEach { st ->
                addView(toggleRow(st.title, st.where, st.id in done) { v -> Photos.mark(this@MainActivity, st.id, v) })
            }
            addView(Ui.row(this@MainActivity,
                Ui.button(this@MainActivity, "Abrir câmera", Ui.Kind.SUN_) {
                    open(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                }), Ui.lp(this@MainActivity, top = 8, bottom = 0))
        }
        card {
            t("Câmera Expert RAW (opcional)")
            p("App oficial e gratuito da Samsung que tira fotos com bem menos processamento. Use quando quiser a foto mais " +
                "natural possível e deixe a câmera normal para o dia a dia: é o seu \"liga/desliga\" da IA na hora.")
            val raw = Photos.EXPERT_RAW
            if (Cold.installed(this@MainActivity, raw)) btn("Abrir Expert RAW", Ui.Kind.BLUE_) {
                packageManager.getLaunchIntentForPackage(raw)?.let { open(it) }
            } else btn("Instalar pela Galaxy Store", Ui.Kind.WHITE_) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/$raw"))) }
                    .onFailure { open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$raw"))) }
            }
        }
        card(Ui.WHITE) {
            t("Por que o olho fica artificial?")
            p("Na câmera frontal, a Samsung aplica efeitos de rosto (alisar pele, afinar o rosto, realçar olhos) e o otimizador " +
                "de cena aumenta nitidez e contraste. Juntos, deixam olhos e pele com cara de \"filtro\". Zerar os efeitos de " +
                "rosto costuma resolver a maior parte.", 0)
        }
    }

    // ================================================================ CONFIG
    private fun buildConfig() {
        header("Config", "Deixe o app pronto. A maior parte é uma vez só.")
        val g = Cold.grants(this)
        card {
            t("Monitor")
            addView(toggleRow("Monitorar sempre", "Inicia sozinho ao ligar o celular. Gasta muito pouco.", Store.monitorOn(this@MainActivity)) { v ->
                Store.setMonitor(this@MainActivity, v)
                if (v) GuardService.start(this@MainActivity) else GuardService.stop(this@MainActivity)
            })
            p("Me avise quando passar de:", 4)
            val cur = Store.alertAt(this@MainActivity)
            addView(Ui.row(this@MainActivity, *listOf(41, 43, 45).map { v ->
                Ui.button(this@MainActivity, "$v °C", if (v == cur) Ui.Kind.SUN_ else Ui.Kind.WHITE_) {
                    Store.setAlertAt(this@MainActivity, v); show(4)
                }
            }.toTypedArray()), Ui.lp(this@MainActivity, bottom = 0))
        }

        card {
            t("1. Permissões básicas (sem computador)")
            addView(check(g.notif, "Notificações"))
            addView(check(g.usage, "Acesso aos dados de uso (qual app está aberto)"))
            addView(check(g.battery, "Rodar em segundo plano sem ser fechado"))
            if (!g.notif) btn("Permitir notificações") {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            if (!g.usage) btn("Permitir dados de uso") {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); toast("Encontre CalorScan na lista e ative")
            }
            if (!g.battery) btn("Permitir em segundo plano") {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }

        card(if (g.secure && g.quiet) Ui.GREEN else Ui.WHITE) {
            t("2. Liberação completa (1 vez só)")
            addView(check(g.secure && g.quiet, "Pausar o trabalho no horário e aplicar ajustes sozinho"))
            if (g.secure && g.quiet) {
                p("Pronto! Se o modo desenvolvedor ainda estiver ligado, desligue: alguns apps de banco e do governo não abrem com ele ligado.", 4)
                btn("Abrir Opções do desenvolvedor", Ui.Kind.WHITE_) { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                p("Desligue a chave no topo da tela.", 0)
                return@card
            }
            p("Escolha um jeito. Depois as permissões continuam valendo mesmo com o modo desenvolvedor desligado.")
        }
        if (!(g.secure && g.quiet)) {
            card {
                t("Jeito A — com notebook")
                p("1. No notebook, abra o programa CalorScan PC.\n2. Conecte o cabo e siga os passos na tela.\n" +
                    "3. Clique em “Liberar app”. Pronto.", 0)
            }
            card {
                t("Jeito B — sem computador (Shizuku)")
                p("Precisa de Wi-Fi. Leva uns 5 minutos.")
                val running = Cold.shizukuRunning(); val allowed = Cold.shizukuAllowed()
                val hasShizuku = Cold.installed(this@MainActivity, "moe.shizuku.privileged.api")
                addView(check(hasShizuku, "Instalar o app Shizuku (grátis, Play Store)"))
                if (!hasShizuku) btn("Abrir na Play Store", Ui.Kind.WHITE_) {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                }
                addView(check(running, "Ligar o Shizuku pela Depuração sem fio"))
                if (!running) {
                    p("a) Configurações → Sobre o telefone → Informações do software → toque 7 vezes em Número de compilação (digite o PIN).\n" +
                        "b) Abra o Shizuku → em “Iniciar via Depuração sem fio”, toque em Parear. Ele mostra uma notificação.\n" +
                        "c) Configurações → Opções do desenvolvedor (no fim da lista) → Depuração sem fio → ligue → " +
                        "“Parear dispositivo com código de pareamento”. Aparece um código de 6 números.\n" +
                        "d) Puxe a notificação do Shizuku, toque em “Digite o código” e digite os 6 números.\n" +
                        "e) Volte ao Shizuku e toque em Iniciar.", 4)
                    addView(Ui.row(this@MainActivity,
                        Ui.button(this@MainActivity, "Opções do dev.", Ui.Kind.WHITE_) { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
                        Ui.button(this@MainActivity, "Abrir Shizuku", Ui.Kind.WHITE_) {
                            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { open(it) } ?: toast("Instale o Shizuku primeiro")
                        }), Ui.lp(this@MainActivity, top = 6, bottom = 4))
                }
                addView(check(allowed, "Permitir o CalorScan no Shizuku"))
                if (running && !allowed) btn("Conectar ao Shizuku") { runCatching { Shizuku.requestPermission(5) } }
                if (allowed) btn("Liberar agora", Ui.Kind.GREEN_) { applyShizuku() }
                p("O Shizuku desliga quando o celular reinicia. Só precisa ligar de novo se quiser mudar os apps restringidos.", 0)
            }
        }
        card(Ui.WHITE) {
            t("Sobre")
            p("CalorScan ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")} · " +
                "Nenhum dado sai do celular, a não ser quando você compartilha o resumo.", 0)
        }
    }
}

/** Leitura instantanea para a tela (sem depender do servico). */
class LiveReader(private val a: Activity) {
    fun temp(): Double? = a.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    fun status(): Int = a.getSystemService(android.os.PowerManager::class.java).currentThermalStatus
}
