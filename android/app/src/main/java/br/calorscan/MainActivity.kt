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
    /** Temperatura do corpo do celular (sensor da carcaca), quando o aparelho informa. */
    private var bodyTemp: Double? = null
    private var liveMood: TextView? = null
    private var mascot: MascotView? = null
    private val sampler by lazy { LiveReader(this) }

    private val live = object : Runnable {
        override fun run() { updateLive(); refreshLiveDash(); sky.animate = skyAllowed(); handler.postDelayed(this, 3000) }
    }
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, res ->
        toast(if (res == PackageManager.PERMISSION_GRANTED) "Shizuku conectado ✓" else "Permissão negada no Shizuku")
        show(tab)
    }

    // ================================================================ estrutura
    private lateinit var root: LinearLayout
    private lateinit var grassTop: View
    private lateinit var shot: android.widget.ImageView
    private lateinit var themeBtn: ThemeToggle
    private lateinit var appTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // tema: o que a pessoa escolheu; na primeira vez, o mesmo do celular
        val sysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val pr = Store.prefs(this)
        mode = pr.getString("theme_mode", null) ?: if (pr.getBoolean("theme_dark", sysDark)) "dark" else "light"
        applyPalette()

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // barra do topo: nome do app + sol/lua
        appTitle = Ui.title(this, "CalorScan", 18f, Ui.PAPER).apply { setShadowLayer(0.01f, dp(2).toFloat(), dp(2).toFloat(), Ui.INK) }
        themeBtn = ThemeToggle(this).apply { setOnClickListener { chooseTheme() } }
        topBar = LinearLayout(this)
        // so esta faixa (atras da barra do topo) se mexe: o Android redesenha so ela
        // altura = a da barra do topo (sem isto a faixa pedia a tela inteira e escondia os cartoes)
        bandView = object : View(this) {
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w),
                if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else 0)
        }.apply { background = sky }
        root.addView(FrameLayout(this).apply {
            addView(bandView, FrameLayout.LayoutParams(-1, -1))
            addView(topBar, FrameLayout.LayoutParams(-1, -2))
        })
        topBar.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(78)
            setPadding(dp(18), dp(8), dp(14), dp(0))
            addView(appTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(themeBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        grassTop = View(this)
        root.addView(grassTop, LinearLayout.LayoutParams(-1, dp(5)))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        // "foto" da tela antiga fica por baixo durante a troca de tema
        shot = android.widget.ImageView(this).apply { visibility = View.GONE; scaleType = android.widget.ImageView.ScaleType.FIT_XY }
        setContentView(FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(shot, FrameLayout.LayoutParams(-1, -1))  // por cima: esmaece na troca de tema
        })
        themeBtn.mode = mode
        applyChrome()
        // a faixa do ceu (sol, lua, nuvens) e a barra do topo
        bandView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            sky.gradOffset = (v.parent as View).top.toFloat(); sky.gradHeight = root.height.toFloat(); sky.band = v.height.toFloat()
        }
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

    // ================================================================ tema claro / escuro / ao vivo
    private var mode = "light"
    private lateinit var topBar: LinearLayout
    private val sky by lazy {
        SkyDrawable(resources.displayMetrics.density).apply {
            // o fundo (parado) e a barra de status acompanham as cores do ceu
            @Suppress("DEPRECATION")
            onColors = { t, m, b -> skyBg.set(t, m, b); if (::root.isInitialized) window.statusBarColor = t }
        }
    }
    private val skyBg = SkyBackground()
    private lateinit var bandView: View
    private var pendingLive = false
    private var themeAnim: ValueAnimator? = null

    /** Paleta dos cartoes + ceu de cada tema. */
    private fun applyPalette(smooth: Boolean = false) {
        val sc = when (mode) {
            "live" -> Sky.scene(Weather.current(this))
            "dark" -> Sky.dark()
            else -> Sky.light()
        }
        Ui.setDark(sc.darkUi); Ui.setSky(sc.top, sc.bottom)
        sky.show(sc, smooth)
        if (::themeBtn.isInitialized) themeBtn.mode = mode
    }

    /** Seletor dos 3 temas, no estilo de blocos. */
    private fun chooseTheme() {
        val dlg = android.app.Dialog(this)
        val box = Ui.card(this).apply {
            t("Tema")
            fun opt(label: String, m: String, sub: String) {
                btn(if (m == mode) "✓ $label" else label, if (m == mode) Ui.Kind.SUN_ else Ui.Kind.WHITE_, top = 8) {
                    dlg.dismiss()
                    if (m == "live") enableLive() else if (m != mode) switchTheme(m)
                }
                addView(Ui.body(this@MainActivity, sub, 12.5f, Ui.MUTED), Ui.lp(this@MainActivity, bottom = 2))
            }
            opt("☀️  Claro", "light", "Céu azul de dia.")
            opt("🌙  Escuro", "dark", "Noite com a lua na fase de hoje. Descansa a vista.")
            opt("⛅  Ao vivo", "live", "Segue a hora e o tempo da sua cidade: sol, lua na fase certa, pôr do sol, nuvens andando, " +
                "chuva caindo, raios e vento. Usa a localização aproximada e consulta o open-meteo.com a cada 30 min, só com o app aberto. " +
                "A animação pausa sozinha na economia de bateria e quando o celular esquenta.")
        }
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), -2)
        dlg.show()
    }

    /** Liga o "Ao vivo": se a localizacao do celular estiver ligada, pede a permissao e usa a cidade. */
    private fun enableLive() {
        val lm = getSystemService(android.location.LocationManager::class.java)
        val locOn = runCatching { lm.isLocationEnabled }.getOrDefault(false)
        if (locOn && !Weather.hasLocation(this)) {
            pendingLive = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            return
        }
        if (!locOn) toast("Localização do celular desligada: o céu segue só a hora e a fase da lua.")
        switchTheme("live")
        refreshWeather()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7 && pendingLive) {
            pendingLive = false
            if (res.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("Sem a localização, o céu segue só a hora e a fase da lua.")
            switchTheme("live")
            refreshWeather()
        }
    }

    /** Busca o tempo (no maximo a cada 30 min) fora da linha principal e atualiza o ceu. */
    private fun refreshWeather() {
        if (mode != "live") return
        Thread { val changed = Weather.refresh(this); runOnUiThread { if (changed) updateSky() } }.start()
    }

    /** Ao vivo: o ceu escorrega ate a cena nova; se os cartoes precisam trocar de claro/escuro, esmaecem devagar. */
    private fun updateSky() {
        if (mode != "live") return
        val sc = Sky.scene(Weather.current(this))
        if (sc.darkUi != Ui.dark) crossfade(1600) { Ui.setDark(sc.darkUi); Ui.setSky(sc.top, sc.bottom); applyChrome(); rebuild() }
        sky.show(sc, smooth = true)
    }

    /** A cada 10 min com o app aberto: hora anda (sol/lua mudam de lugar) e, a cada 30 min, tempo novo. */
    private val skyTick = object : Runnable {
        override fun run() {
            if (mode == "live") { updateSky(); refreshWeather() }
            handler.postDelayed(this, 10 * 60_000L)
        }
    }

    /** O ceu so se mexe com o app na tela, fora da economia de bateria, com as animacoes do sistema ligadas
     *  e com o celular frio (estado termico abaixo de "moderado"). */
    private fun skyAllowed(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return hasWindowFocus() && !pm.isPowerSaveMode && pm.currentThermalStatus < android.os.PowerManager.THERMAL_STATUS_MODERATE &&
            ValueAnimator.areAnimatorsEnabled()
    }

    /** Cores da "moldura": ceu, grama da barra de baixo e barras do sistema. */
    private fun applyChrome() {
        root.background = skyBg
        nav.setBackgroundColor(Ui.GRASS)
        grassTop.setBackgroundColor(Ui.GRASS2)
        appTitle.setShadowLayer(0.01f, dp(2).toFloat(), dp(2).toFloat(), Ui.INK)
        themeBtn.invalidate()
        @Suppress("DEPRECATION") run { window.statusBarColor = sky.topColor.takeIf { it != 0 } ?: Ui.SKY1; window.navigationBarColor = Ui.GRASS2 }
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            if (Ui.dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
    }

    private fun rebuild() {
        val y = scroll.scrollY
        show(tab)
        scroll.post { scroll.scrollTo(0, y) }
    }

    /**
     * Troca os cartoes de cor sem pulo: tira uma "foto" so do conteudo (sem o ceu), poe por cima,
     * monta tudo nas cores novas por baixo e esmaece a foto. O ceu continua aparecendo (e se mexendo) atras.
     */
    private fun crossfade(ms: Long, change: () -> Unit) {
        val w = root.width; val h = root.height
        val bmp = if (w > 0 && h > 0) runCatching {
            val bg = root.background
            root.background = null
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { root.draw(android.graphics.Canvas(it)) }
                .also { root.background = bg }
        }.getOrNull() else null
        change()
        if (bmp == null) return
        shot.animate().cancel()
        shot.setImageBitmap(bmp); shot.alpha = 1f; shot.visibility = View.VISIBLE
        shot.animate().alpha(0f).setDuration(ms).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .withEndAction { shot.visibility = View.GONE; shot.setImageDrawable(null); bmp.recycle() }.start()
    }

    /**
     * Troca de tema. Claro <-> escuro: o sol se poe (ou nasce) num "relogio acelerado" de ~2,6 s, passando
     * pelas cores do entardecer/amanhecer; no meio, os cartoes esmaecem para as cores novas.
     * Com o Ao vivo: o ceu escorrega ate o de agora e os cartoes esmaecem.
     */
    private fun switchTheme(newMode: String) {
        val old = mode
        themeAnim?.cancel()
        mode = newMode
        Store.prefs(this).edit().putString("theme_mode", mode).apply()
        themeBtn.mode = mode
        themeBtn.animate().rotationBy(360f).setDuration(900)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        val dayNight = (old == "light" && newMode == "dark") || (old == "dark" && newMode == "light")
        if (!dayNight || !ValueAnimator.areAnimatorsEnabled()) {
            val sc = when (mode) { "live" -> Sky.scene(Weather.current(this)); "dark" -> Sky.dark(); else -> Sky.light() }
            crossfade(900) { Ui.setDark(sc.darkUi); Ui.setSky(sc.top, sc.bottom); applyChrome(); rebuild() }
            sky.animate = skyAllowed()
            sky.show(sc, smooth = true)
            return
        }
        val toDark = newMode == "dark"
        val (from, to) = Sky.transitionHours(toDark)
        val turn = if (toDark) 18.7 else 29.6           // hora (virtual) em que os cartoes trocam de cor
        var swapped = false
        themeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            interpolator = android.view.animation.PathInterpolator(0.45f, 0f, 0.25f, 1f)
            addUpdateListener {
                val v = from + (to - from) * (it.animatedValue as Float)
                val sc = Sky.virtual(v)
                sky.show(sc, smooth = false)
                if (!swapped && v >= turn) {
                    swapped = true
                    crossfade(800) { Ui.setDark(toDark); Ui.setSky(sc.top, sc.bottom); applyChrome(); rebuild() }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!swapped) crossfade(600) { Ui.setDark(toDark); applyChrome(); rebuild() }
                    applyPalette()
                    applyChrome()
                }
            })
            start()
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent, build = true) }

    private fun handleIntent(i: Intent, build: Boolean) {
        if (i.getBooleanExtra("resume_work", false)) {
            Work.selected(this)?.let { Work.setPaused(this, it, false, interactive = true) }
        }
        tab = i.getIntExtra("tab", tab)
        if (build) show(tab)
    }

    override fun onResume() { super.onResume(); applyPalette(); applyChrome(); show(tab); handler.post(live); handler.post(skyTick) }
    override fun onPause() { handler.removeCallbacks(live); handler.removeCallbacks(skyTick); sky.animate = false; super.onPause() }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); sky.animate = f && skyAllowed() }
    override fun onDestroy() { runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }; super.onDestroy() }

    // abas: 0 Agora, 1 Esfriar, 2 Trabalho, 3 Fotos, 4 Config, 5 Pasta Segura.
    // Trabalho e Pasta Segura so aparecem se o celular tiver esses perfis.
    private var hasWork = false
    private var secure: android.os.UserHandle? = null

    private fun buildNav() {
        nav.removeAllViews()
        val tabs = buildList {
            add(0 to "🌡️\nAgora"); add(1 to "❄️\nEsfriar")
            if (hasWork) add(2 to "💼\nTrabalho")
            if (secure != null) add(5 to "🔒\nPasta")
            add(3 to "📷\nFotos"); add(4 to "⚙️\nConfig")
        }
        tabs.forEach { (i, s) ->
            val b = Ui.button(this, s, if (i == tab) Ui.Kind.SUN_ else Ui.Kind.WHITE_) { show(i) }.apply {
                textSize = if (tabs.size > 5) 10.5f else 11.5f; setPadding(0, dp(6), 0, dp(11)); setLineSpacing(0f, 0.95f)
            }
            nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
    }

    private fun show(t0: Int) {
        hasWork = Work.profiles(this).isNotEmpty()
        secure = Work.secureFolder(this)
        val t = when { t0 == 2 && !hasWork -> 0; t0 == 5 && secure == null -> 0; else -> t0 }
        // atualizar a MESMA aba (tocar num dia, numa chave...) mantem a rolagem; trocar de aba volta ao topo
        val sameTab = t == tab && content.childCount > 0
        val y = scroll.scrollY
        tab = t
        buildNav()
        content.removeAllViews()
        liveTemp = null; liveMood = null; mascot = null; procsBox = null; tiles = emptyMap()
        when (t) { 0 -> buildNow(); 1 -> buildCold(); 2 -> buildWork(); 3 -> buildPhotos(); 5 -> buildSecure(); else -> buildConfig() }
        if (sameTab) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
    }

    // ================================================================ helpers
    private fun add(v: View, bottom: Int = 14) = content.addView(v, Ui.lp(this, bottom = bottom))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun header(title: String, sub: String) {
        add(Ui.title(this, title, 26f, Ui.PAPER).apply {
            setShadowLayer(0.01f, dp(3).toFloat(), dp(3).toFloat(), Ui.INK)
        }, 2)
        add(Ui.body(this, sub, 14f, Ui.TEXT), 14)
    }
    private fun card(face: Int = Ui.WHITE, build: LinearLayout.() -> Unit) = add(Ui.card(this, face).apply(build))
    private fun LinearLayout.t(s: String, size: Float = 17f) =
        addView(Ui.title(this@MainActivity, s, size, Ui.textOn(tag as? Int ?: Ui.WHITE)), Ui.lp(this@MainActivity, bottom = 4))
    /** Texto: cinza nos cartoes brancos, escuro nos coloridos (contraste). */
    private fun LinearLayout.p(s: CharSequence, bottom: Int = 8) = addView(
        Ui.body(this@MainActivity, s, color = Ui.mutedOn(tag as? Int ?: Ui.WHITE)),
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

    private fun check(ok: Boolean, s: String) = Ui.body(this, (if (ok) "✅  " else "⬜  ") + s, 14.5f, Ui.TEXT)

    // ================================================================ AGORA
    private fun buildNow() {
        val g = Cold.grants(this)
        card(Ui.HILITE) {
            val m = MascotView(this@MainActivity).also { mascot = it }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(this@MainActivity, "--", 44f, Ui.textOn(Ui.HILITE)).also { liveTemp = it })
                addView(Ui.title(this@MainActivity, "", 16f, Ui.textOn(Ui.HILITE)).also { liveMood = it })
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(m); addView(texts, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16) })
            })
            ObjectAnimator.ofFloat(m, "translationY", 0f, -dp(6).toFloat()).apply {
                duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = 5; start() // pula uns segundos e para: animacao infinita redesenhava a tela sem parar (gasta bateria)
            }
        }
        updateLive()
        if (!g.usage || !g.notif) card(Ui.ORANGE) {
            t("Falta uma permissão")
            p("Sem ela o app não sabe qual app está aberto nem consegue avisar. Leva 10 segundos.")
            btn("Resolver agora", Ui.Kind.WHITE_) { show(4) }
        }
        buildLiveDash()
        buildRanking()
        val recent = Store.samples(this, System.currentTimeMillis() - 3 * 3_600_000).map { it.c }
        card {
            t("Últimas 3 horas")
            addView(ChartView(this@MainActivity).apply {
                points = if (recent.size > 60) recent.chunked((recent.size + 59) / 60).map { it.max() } else recent
            }, Ui.lp(this@MainActivity, top = 4, bottom = 4))
            p(if (recent.size < 2) "Ainda medindo… volte em alguns minutos." else "Tracejado = 43 °C, onde o celular começa a se proteger.", 0)
        }
        add(Ui.title(this, "O que o app descobriu", 20f, Ui.TEXT), 8)
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

    // ---------------------------------------------------------------- painel ao vivo
    private class Tile(val value: TextView, val meter: MeterView, val sub: TextView)
    private var tiles: Map<String, Tile> = emptyMap()
    private var procsBox: LinearLayout? = null
    @Volatile private var liveBusy = false

    private fun buildLiveDash() {
        val map = HashMap<String, Tile>()
        fun tile(key: String, title: String): View = Ui.card(this, Ui.WHITE).apply {
            setPadding(dp(12), dp(10), dp(12), dp(16))
            addView(Ui.title(this@MainActivity, title.uppercase(), 11.5f, Ui.MUTED))
            // altura fixa: valor sempre em 1 linha e detalhes sempre em 2 (os 4 quadros ficam iguais e nao "pulam")
            val v = Ui.title(this@MainActivity, "—", 24f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }; addView(v)
            val m = MeterView(this@MainActivity); addView(m, Ui.lp(this@MainActivity, top = 4, bottom = 4))
            val s = Ui.body(this@MainActivity, " ", 12f).apply { setLines(2); ellipsize = android.text.TextUtils.TruncateAt.END }; addView(s)
            map[key] = Tile(v, m, s)
        }
        card {
            t("Ao vivo")
            fun pair(a: View, b: View) = Ui.row(this@MainActivity, a, b).apply {
                // os dois quadros da linha com a mesma altura
                for (i in 0 until childCount) getChildAt(i).layoutParams = (getChildAt(i).layoutParams as LinearLayout.LayoutParams).apply { height = -1 }
            }
            addView(pair(tile("bat", "Celular"), tile("cpu", "Processador")), Ui.lp(this@MainActivity, top = 4, bottom = 10))
            addView(pair(tile("mem", "Memória"), tile("pow", "Consumo")), Ui.lp(this@MainActivity, bottom = 0))
        }
        tiles = map
        procsBox = Ui.card(this).also { add(it) }
        refreshLiveDash()
    }

    private fun refreshLiveDash() {
        if (tab != 0 || liveBusy || procsBox == null) return
        liveBusy = true
        Thread {
            val s = runCatching { Live.snap(this) }.getOrNull()
            runOnUiThread {
                liveBusy = false
                if (tab == 0 && s != null) { fillLiveDash(s); updateLive() }
            }
        }.start()
    }

    private fun kindColor(k: String) = when (k) { "samsung" -> Ui.SUN; "google" -> Ui.BLUE; "app" -> Ui.ORANGE; else -> Ui.GREY2 }

    private fun fillLiveDash(s: Live.Snap) {
        val f = { v: Double -> Diagnosis.f1(v) }
        tiles["bat"]?.apply {
            // temperatura do corpo do celular (sensor da carcaca); sem ele, a da bateria
            s.sensors["Carcaça"]?.let { bodyTemp = it }
            val body = s.sensors["Carcaça"] ?: s.temp
            value.text = body?.let { f(it) + " °C" } ?: "—"
            meter.pct = body?.let { (it - 25) / 22 * 100 } ?: 0.0; meter.color = Ui.tempColor(body).first
            sub.text = "${s.level ?: "?"}%${if (s.charging) " · carregando" else ""}\nsistema ${Diagnosis.thermalName(s.thermal)}"
        }
        tiles["cpu"]?.apply {
            if (s.cpuTotal != null) {
                value.text = "${s.cpuTotal.toInt()}%"; meter.pct = s.cpuTotal; meter.color = MeterView.levelColor(s.cpuTotal)
                sub.text = (s.sensors["Processador"]?.let { "chip ${f(it)} °C" } ?: "agora") +
                    (s.headroom?.let { "\nfolga térmica ${((1 - it.coerceIn(0f, 1f)) * 100).toInt()}%" } ?: "")
            } else {
                value.text = "—"; meter.pct = 0.0
                sub.text = if (!Live.canSeeApps(this@MainActivity)) "falta liberar\n(aba Config)"
                           else if (s.charging) "não dá para medir\nno carregador" else "medindo…"
            }
        }
        tiles["mem"]?.apply {
            value.text = "${s.memPct}%"; meter.pct = s.memPct.toDouble(); meter.color = MeterView.levelColor(s.memPct.toDouble())
            sub.text = String.format(java.util.Locale("pt", "BR"), "%.1f de %.1f GB\nem uso", s.memUsed / 1e9, s.memTotal / 1e9)
        }
        tiles["pow"]?.apply {
            value.text = s.watts?.let { f(it) + " W" } ?: "—"
            val w = s.watts ?: 0.0
            meter.pct = w / 8 * 100; meter.color = if (w >= 5) Ui.RED else if (w >= 3) Ui.ORANGE else Ui.GREEN
            // sempre 2 linhas curtas (antes a 1a quebrava e o quadro mudava de tamanho)
            sub.text = (if (s.charging) "entrando" else "saindo") +
                (s.ma?.let { " · ${kotlin.math.abs(it)} mA" } ?: "") +
                "\n" + (s.temp?.let { "bateria ${f(it)} °C" } ?: if (s.charging) "no carregador" else "na bateria")
        }
        val box = procsBox ?: return
        box.removeAllViews()
        box.t("Usando o processador agora")
        val procs = s.procs
        if (procs == null && s.charging && Live.canSeeApps(this)) {
            box.p("O celular está no carregador: enquanto carrega, o Android não conta o uso de cada app " +
                "(nenhum app consegue ver). Tire do cabo e em uns 15 segundos a lista aparece.", 0)
            return
        }
        if (procs == null) {
            box.p("Um app comum não consegue ver os outros apps. Para ver quem usa o processador (e quem mais esquentou ao ligar), " +
                "faça a liberação uma vez: notebook com o CalorScan PC (passo 3 ou 4) ou Shizuku (aba Config).", 4)
            box.btn("Como liberar", Ui.Kind.WHITE_) { show(4) }
            return
        }
        box.p("% do celular inteiro, atualizado a cada 15 s.", 6)
        procRows(box, procs.filter { it.pct >= 0.3 }.take(8).map { Triple(it.label, it.kind, it.pct) }) { "${Diagnosis.f1(it)}%" }
        legend(box)
    }

    private fun procRows(box: LinearLayout, rows: List<Triple<String, String, Double>>, fmt: (Double) -> String) {
        if (rows.isEmpty()) { box.p("Nada pesando agora.", 0); return }
        val max = rows.maxOf { it.third }.coerceAtLeast(0.1)
        rows.forEach { (label, kind, v) ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Ui.body(this@MainActivity, label, 13.5f, Ui.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 0.42f))
                addView(BarView(this@MainActivity, (v / max).toFloat(), kindColor(kind)),
                    LinearLayout.LayoutParams(0, -2, 0.38f).apply { marginStart = dp(8); marginEnd = dp(8) })
                addView(Ui.title(this@MainActivity, fmt(v), 13.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 0.2f))
            }, Ui.lp(this, bottom = 6))
        }
    }

    private fun legend(box: LinearLayout) = box.addView(Ui.body(this,
        "■ cinza: sistema  ■ azul: Google  ■ amarelo: Samsung  ■ laranja: seus apps", 11.5f), Ui.lp(this, top = 4, bottom = 0))

    private fun buildRanking() {
        if (!Live.canSeeApps(this)) return
        val boot = CpuRank.top(this, boot = true)
        val day = CpuRank.top(this, boot = false)
        fun rows(l: List<Pair<String, Double>>) = l.map { (n, secs) ->
            val (name, kind) = CpuRank.describeKey(this, n); Triple(name, kind, secs)
        }
        val secsFmt = { v: Double -> if (v >= 90) "${(v / 60).toInt()} min" else "${v.toInt()} s" }
        card {
            t("Quem mais esquentou")
            p("Tempo de processador que cada um gastou: processador trabalhando = calor.", 8)
            addView(Ui.title(this@MainActivity, "Depois de ligar (primeiros 15 min)", 15f), Ui.lp(this@MainActivity, bottom = 6))
            if (boot.isEmpty()) p(if (SystemClock.elapsedRealtime() < 15 * 60_000) "Medindo… volte em alguns minutos."
                                  else "Aparece depois da próxima vez que o celular reiniciar.", 10)
            else procRows(this, rows(boot), secsFmt)
            addView(Ui.title(this@MainActivity, "Hoje", 15f), Ui.lp(this@MainActivity, top = 10, bottom = 6))
            if (day.isEmpty()) p("Medindo… volte em alguns minutos.", 6) else procRows(this, rows(day), secsFmt)
            legend(this)
            btn("Não deixar apps iniciarem sozinhos →", Ui.Kind.WHITE_, top = 10) { show(1) }
        }
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
        // no topo: temperatura do celular (corpo); sem esse sensor, a da bateria
        val t = bodyTemp ?: sampler.temp()
        mascot?.temp = t
        liveTemp?.text = t?.let { Diagnosis.f1(it) + " °C" } ?: "--"
        liveMood?.text = buildString {
            append(t?.let { "Celular " + Diagnosis.moodPhone(it) } ?: "")
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
        // cartao de situacao: recalculado a cada mudanca (antes ficava com o numero velho)
        val statusSlot = FrameLayout(this)
        add(statusSlot)
        refreshColdStatus = { statusSlot.removeAllViews(); statusSlot.addView(coldStatusCard()) }
        refreshColdStatus()

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
                    refreshColdStatus()
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
                        refreshColdStatus()
                    })
                }
                listBox.addView(Ui.body(this, "Toque no nome de um app para abrir a página dele no Android.", 12.5f), Ui.lp(this, top = 6, bottom = 0))
            }
        }.start()

        // 2 — Samsung
        val sams = Cold.SAMSUNG.filter { Cold.installed(this, it.first) }
        if (sams.isNotEmpty()) card {
            t("Recursos da Samsung")
            p("Rodam o tempo todo, em todos os perfis. Chave ligada = recurso desativado.")
            sams.forEach { (pkg, name) ->
                val on = pkg in Cold.wantOff(this@MainActivity)
                val now = if (Cold.pkgEnabled(this@MainActivity, pkg)) "agora: funcionando" else "agora: desativado ✓"
                addView(toggleRow("Desativar $name", now, on, onTitle = { appDetails(pkg) }) { v ->
                    val s = Cold.wantOff(this@MainActivity).toMutableSet(); if (v) s += pkg else s -= pkg
                    Cold.setWantOff(this@MainActivity, s)
                    refreshColdStatus()
                })
            }
            p("À mão: abra Configurações, toque na lupa e procure “Bixby” ou “Sugestões”.", 0)
        }

        // 3 — busca Wi-Fi/Bluetooth
        card {
            t("Busca de Wi-Fi e Bluetooth")
            p("Não é o Wi-Fi em si: ele continua ligado e conectando sozinho. Esta opção faz o celular procurar redes " +
                "mesmo com o Wi-Fi desligado, só para melhorar a localização. Efeito no calor: pequeno.")
            // chave mostra o estado real enquanto a pessoa nao mexeu (antes aparecia "desligada" com a chave desligada)
            val scanChecked = if (Cold.scanTouched(this@MainActivity)) Cold.wantScanOff(this@MainActivity)
                              else !Cold.scanOn(this@MainActivity)
            addView(toggleRow("Desligar a busca", if (Cold.scanOn(this@MainActivity)) "agora: buscando" else "agora: desligada ✓",
                scanChecked) { v ->
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
                if (Cold.shizukuAllowed()) applyShizuku() else { toast("Marcado. Aplique pelo notebook ou pelo Shizuku."); show(1) }
            }
        }
    }

    private var refreshColdStatus: () -> Unit = {}

    private fun coldStatusCard(): View {
        val pending = Cold.plan(this).commands.size
        val shizuku = Cold.shizukuAllowed()
        return Ui.card(this, if (pending == 0) Ui.GOOD else Ui.HILITE).apply {
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
        val paused = Work.isPaused(this, sel)
        card(if (paused) Ui.WHITE else Ui.GOOD) {
            t("Seu perfil de trabalho")
            p(if (paused) "⏸  Pausado" else "▶  Ativo", 4)
            btn(if (paused) "Ligar" else "Pausar", if (paused) Ui.Kind.GREEN_ else Ui.Kind.ORANGE_) {
                val err = Work.setPaused(this@MainActivity, sel, !paused, interactive = true)
                if (err != null && err != "precisa do seu PIN") toast("Não deu: $err. Use o atalho “Apps de trabalho” no painel rápido.")
                handler.postDelayed({ show(2) }, 800)
            }
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

    // ================================================================ apps de um perfil (trabalho / Pasta Segura)
    /**
     * Lista os apps do perfil, do que mais gastou processador hoje para o que menos gastou. Tocar abre a
     * pagina do app DENTRO do perfil, onde a pessoa toca em Desativar (ou Bateria -> Restrita). O CalorScan nao
     * desativa sozinho: a Samsung/empresa bloqueiam isso ate para o cabo (ADB).
     */
    private fun profileAppsCard(u: android.os.UserHandle, tag: String, intro: String) {
        val la = getSystemService(android.content.pm.LauncherApps::class.java)
        val acts = runCatching { la.getActivityList(null, u) }.getOrDefault(emptyList()).distinctBy { it.componentName.packageName }
        val usage = CpuRank.top(this, boot = false, n = 200).filter { it.first.endsWith(" · $tag") }
            .associate { it.first.substringBefore(" · ") to it.second }
        val total = usage.values.sum()
        card {
            t("Apps deste perfil (${acts.size})")
            p(intro, 6)
            if (Live.canSeeApps(this@MainActivity)) p("Hoje este perfil gastou ${secsText(total)} de processador" +
                (if (usage.size > acts.size) ", contando ${usage.size} processos (vários sem ícone)" else "") + ".", 6)
            if (acts.isEmpty()) { p("Nenhum app visível agora (o perfil está pausado ou trancado).", 0); return@card }
            acts.sortedWith(compareByDescending<android.content.pm.LauncherActivityInfo> { usage[it.componentName.packageName] ?: 0.0 }
                .thenBy { it.label.toString().lowercase() }).forEach { a ->
                val secs = usage[a.componentName.packageName]
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(7), 0, dp(7))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.title(this@MainActivity, a.label.toString(), 15f))
                        addView(Ui.body(this@MainActivity, secs?.let { "${secsText(it)} de processador hoje" } ?: "parado hoje", 12.5f))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.button(this@MainActivity, "Desativar…", Ui.Kind.WHITE_) {
                        runCatching { la.startAppDetailsActivity(a.componentName, u, null, null) }
                            .onSuccess { toast("Toque em Desativar (ou em Bateria → Restrita)") }
                            .onFailure { toast("O Android não deixou abrir essa página") }
                    }.apply { textSize = 13f; minHeight = dp(44); setPadding(dp(12), dp(4), dp(12), dp(10)) },
                        LinearLayout.LayoutParams(-2, -2))
                }, Ui.lp(this@MainActivity, bottom = 0))
            }
            p("Para reativar depois: abra a página de novo (Configurações do perfil → Apps → o app → Ativar).", 0)
        }
    }

    private fun secsText(v: Double) = if (v >= 90) "${(v / 60).toInt()} min" else "${v.toInt()} s"

    // ================================================================ PASTA SEGURA
    private fun buildSecure() {
        val u = secure ?: return buildNow()
        header("Pasta Segura", "Trancada, nada roda nela. Aberta, os apps dela rodam junto com os do celular.")
        val locked = Work.isPaused(this, u)
        card(if (locked) Ui.GOOD else Ui.HILITE) {
            t(if (locked) "Trancada ✓" else "Aberta agora")
            p(if (locked) "Nenhum app da Pasta Segura está rodando." else
                "Os apps dela estão rodando e gastando processador e bateria. Tranque quando não estiver usando.", 4)
            if (!locked) btn("Trancar agora", Ui.Kind.GREEN_) {
                val err = Work.setPaused(this@MainActivity, u, true, interactive = true)
                if (err != null) toast("Não deu: $err. Tranque pela própria Pasta Segura (⋮ → Bloquear).")
                handler.postDelayed({ show(5) }, 800)
            }
            btn("Abrir a Pasta Segura", Ui.Kind.WHITE_) {
                packageManager.getLaunchIntentForPackage("com.samsung.knox.securefolder")?.let { open(it) }
                    ?: toast("Abra pelo ícone da Pasta Segura")
            }
        }
        if (locked) card {
            t("Para ver e desativar os apps dela")
            p("Abra a Pasta Segura (ela pede a senha) e volte aqui: a lista aparece enquanto ela estiver aberta.", 0)
        } else profileAppsCard(u, "Pasta Segura",
            "Desative o que você não usa: o app some da Pasta Segura e para de rodar. Você escolhe quais ficam.")
        card(Ui.WHITE) {
            t("Para ela gastar menos")
            p("• Pasta Segura → ⋮ → Configurações → Bloqueio automático → “Imediatamente” ou “Quando a tela desligar”: " +
                "ela tranca sozinha e para de rodar.\n" +
                "• Dentro dela, desative os apps que vieram de fábrica e você não usa (Galeria, Contatos, Navegador, " +
                "Calendário, Notas, Play Store se não instala nada lá…).\n" +
                "• Cada app desativado ali é uma cópia a menos dos serviços do Google rodando.", 0)
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
        card(if (natural) Ui.GOOD else Ui.WHITE) {
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
            p("Avisar quando passar de:", 4)
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

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) card {
            t("Samsung: não deixar o monitor dormir")
            p("A Samsung fecha apps em segundo plano para economizar bateria, e o monitor para de medir sem avisar. " +
                "Faça uma vez:\n1. Toque no botão abaixo → Bateria → marque “Sem restrições”.\n" +
                "2. Configurações → Manutenção do aparelho (ou Bateria) → Limites de uso em segundo plano → " +
                "confira se o CalorScan NÃO está em “Apps em suspensão” nem “Apps em suspensão profunda”. " +
                "Se puder, adicione em “Apps que nunca suspendem”.")
            btn("Abrir a página do CalorScan", Ui.Kind.WHITE_) { appDetails(packageName) }
        }

        card(if (g.secure && g.quiet && g.dump) Ui.GOOD else Ui.WHITE) {
            t("2. Liberação completa (1 vez só)")
            addView(check(g.secure && g.quiet && g.dump, "Pausar o trabalho no horário, aplicar ajustes sozinho e ver quais apps usam o processador"))
            if (g.secure && g.quiet && g.dump) {
                p("Pronto! Se o modo desenvolvedor ainda estiver ligado, desligue: alguns apps de banco e do governo não abrem com ele ligado.", 4)
                btn("Abrir Opções do desenvolvedor", Ui.Kind.WHITE_) { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                p("Desligue a chave no topo da tela.", 0)
                return@card
            }
            p("Escolha um jeito. Depois as permissões continuam valendo mesmo com o modo desenvolvedor desligado.")
        }
        if (!(g.secure && g.quiet && g.dump)) {
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
        card {
            t("Tema")
            p(when (mode) {
                "live" -> "Ao vivo agora: " + Sky.scene(Weather.current(this@MainActivity)).label +
                    if (Weather.current(this@MainActivity).city) "." else ". (Sem a localização: segue só a hora e a lua.)"
                "dark" -> "Escuro."
                else -> "Claro."
            }, 4)
            btn("Escolher tema", Ui.Kind.WHITE_) { chooseTheme() }
            p("Também pelo botão de sol/lua no topo.", 0)
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
