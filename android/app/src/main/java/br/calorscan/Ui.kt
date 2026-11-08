package br.calorscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tema "blocos": cores fortes, bordas grossas e sombra solida embaixo. */
object Ui {
    // ---- cores que mudam com o tema (claro = dia no ceu azul; escuro = noite) ----
    var dark = false; private set
    var INK = 0xFF2B2D42.toInt(); private set      // bordas e sombras dos blocos
    var TEXT = 0xFF2B2D42.toInt(); private set     // texto principal
    var MUTED = 0xFF5D6177.toInt(); private set    // texto secundario
    var WHITE = Color.WHITE; private set           // cor dos cartoes
    var SKY1 = 0xFF7FD3FF.toInt(); private set
    var SKY2 = 0xFFC6EFFF.toInt(); private set
    var GRASS = 0xFF62D26F.toInt(); private set
    var GRASS2 = 0xFF3FA452.toInt(); private set
    var GREY = 0xFFCFD5DD.toInt(); private set
    var GREY2 = 0xFF9AA3AD.toInt(); private set
    var EMPTY = 0xFFE9EEF5.toInt(); private set    // blocos vazios dos medidores
    var CHART = 0xFFF3F8FF.toInt(); private set    // fundo dos graficos
    // cartoes de destaque em azul (no lugar do amarelo e do verde)
    var HILITE = 0xFF9ED8FB.toInt(); private set   // destaque / esperando (era amarelo)
    var GOOD = 0xFF4FB3F4.toInt(); private set     // tudo certo / ativo (era verde)
    const val PAPER = Color.WHITE                  // branco de verdade (texto em botao colorido)

    fun setDark(d: Boolean) {
        dark = d
        if (d) {
            INK = 0xFF0C0E1C.toInt(); TEXT = 0xFFEEF0FA.toInt(); MUTED = 0xFFA9AFCB.toInt()
            WHITE = 0xFF262B48.toInt(); SKY1 = 0xFF0F1530.toInt(); SKY2 = 0xFF27305A.toInt()
            GRASS = 0xFF2F7D46.toInt(); GRASS2 = 0xFF1E5A31.toInt()
            GREY = 0xFF454B6E.toInt(); GREY2 = 0xFF6A7096.toInt(); EMPTY = 0xFF353A5C.toInt(); CHART = 0xFF1C2140.toInt()
            HILITE = 0xFF2C4F86.toInt(); GOOD = 0xFF1F6FB2.toInt()
        } else {
            INK = 0xFF2B2D42.toInt(); TEXT = INK; MUTED = 0xFF5D6177.toInt()
            WHITE = Color.WHITE; SKY1 = 0xFF7FD3FF.toInt(); SKY2 = 0xFFC6EFFF.toInt()
            GRASS = 0xFF62D26F.toInt(); GRASS2 = 0xFF3FA452.toInt()
            GREY = 0xFFCFD5DD.toInt(); GREY2 = 0xFF9AA3AD.toInt(); EMPTY = 0xFFE9EEF5.toInt(); CHART = 0xFFF3F8FF.toInt()
            HILITE = 0xFF9ED8FB.toInt(); GOOD = 0xFF4FB3F4.toInt()
        }
    }

    /** Tema "Ao vivo": a cor do ceu vem da hora e do tempo. */
    fun setSky(top: Int, bottom: Int) { SKY1 = top; SKY2 = bottom }

    /** Texto que vai em cima de um cartao: escuro nos coloridos (amarelo, verde...), do tema nos normais. */
    /** Texto legivel em cima de qualquer cartao: claro nos fundos escuros, escuro nos claros. */
    fun textOn(face: Int) = if (face == WHITE) TEXT else if (lum(face) < 0.42) 0xFFF2F5FF.toInt() else 0xFF1E2A44.toInt()
    fun mutedOn(face: Int) = if (face == WHITE) MUTED else if (lum(face) < 0.42) 0xFFC9D8F2.toInt() else 0xFF26375A.toInt()
    private fun lum(c: Int) = (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)) / 255

    // ---- cores de destaque: iguais nos dois temas ----
    const val DIRT = 0xFFB7794A.toInt()
    const val SUN = 0xFFFFD23F.toInt()
    const val SUN2 = 0xFFE0A800.toInt()
    const val BLUE = 0xFF3FA9F5.toInt()
    const val BLUE2 = 0xFF1F7CC2.toInt()
    const val GREEN = 0xFF45C463.toInt()
    const val GREEN2 = 0xFF2B8F44.toInt()
    const val ORANGE = 0xFFFF9A3C.toInt()
    const val ORANGE2 = 0xFFD26B12.toInt()
    const val RED = 0xFFFF5A4E.toInt()
    const val RED2 = 0xFFC2342A.toInt()

    val HEAD: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val BODY: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun Context.dpf(v: Float) = v * resources.displayMetrics.density

    fun tempColor(t: Double?): Pair<Int, Int> = when {
        t == null -> 0xFF9FB3C8.toInt() to 0xFF7D93A8.toInt()
        t < 35 -> 0xFF4FC3F7.toInt() to 0xFF2A8FC4.toInt()
        t < 40 -> GRASS to GRASS2
        t < 43 -> 0xFFFFB13D.toInt() to 0xFFD68412.toInt()
        else -> RED to RED2
    }

    private fun rect(ctx: Context, color: Int, stroke: Boolean = true) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dpf(7f)
        if (stroke) setStroke(ctx.dp(3), INK)
    }

    /** Bloco com "sombra" solida embaixo (efeito 3D). */
    fun block(ctx: Context, face: Int, side: Int = INK, depth: Int = 5): Drawable {
        val d = ctx.dp(depth)
        val base = rect(ctx, side)
        val top = rect(ctx, face)
        return LayerDrawable(arrayOf(base, top)).apply {
            setLayerInset(0, 0, d, 0, 0)
            setLayerInset(1, 0, 0, 0, d)
        }
    }

    private fun pressable(ctx: Context, face: Int, side: Int): Drawable {
        val d = ctx.dp(5)
        val normal = LayerDrawable(arrayOf(rect(ctx, INK), rect(ctx, side), rect(ctx, face))).apply {
            setLayerInset(0, 0, ctx.dp(3), 0, 0)
            setLayerInset(1, 0, ctx.dp(3), 0, 0)
            setLayerInset(2, 0, 0, 0, d)
        }
        val pressed = LayerDrawable(arrayOf(rect(ctx, INK), rect(ctx, face))).apply {
            setLayerInset(0, 0, d, 0, 0)
            setLayerInset(1, 0, d - ctx.dp(1), 0, 0)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(-android.R.attr.state_enabled), LayerDrawable(arrayOf(rect(ctx, INK), rect(ctx, GREY))).apply {
                setLayerInset(0, 0, d, 0, 0); setLayerInset(1, 0, 0, 0, d)
            })
            addState(intArrayOf(), normal)
        }
    }

    /** Tipos de botao. As cores sao lidas na hora (o botao "branco" vira escuro no tema escuro). */
    enum class Kind {
        BLUE_, GREEN_, ORANGE_, RED_, SUN_, WHITE_;
        val face get() = when (this) { BLUE_ -> BLUE; GREEN_ -> GREEN; ORANGE_ -> ORANGE; RED_ -> RED; SUN_ -> SUN; WHITE_ -> WHITE }
        val side get() = when (this) { BLUE_ -> BLUE2; GREEN_ -> GREEN2; ORANGE_ -> ORANGE2; RED_ -> RED2; SUN_ -> SUN2; WHITE_ -> GREY2 }
        val text get() = when (this) { SUN_ -> 0xFF2B2D42.toInt(); WHITE_ -> TEXT; else -> PAPER }
    }

    fun button(ctx: Context, label: String, kind: Kind = Kind.BLUE_, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            typeface = HEAD
            textSize = 15f
            setTextColor(kind.text)
            background = pressable(ctx, kind.face, kind.side)
            stateListAnimator = null
            minHeight = ctx.dp(52)
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(14))
            setOnClickListener { onClick() }
        }

    fun title(ctx: Context, s: String, size: Float = 18f, color: Int = TEXT) = TextView(ctx).apply {
        text = s; typeface = HEAD; textSize = size; setTextColor(color)
    }

    fun body(ctx: Context, s: CharSequence, size: Float = 14.5f, color: Int = MUTED) = TextView(ctx).apply {
        text = s; typeface = BODY; textSize = size; setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    fun card(ctx: Context, face: Int = WHITE): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        tag = face
        background = block(ctx, face)
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(19))
    }

    fun pill(ctx: Context, s: String, face: Int = SUN, txt: Int = INK) = TextView(ctx).apply {
        text = s; typeface = HEAD; textSize = 11.5f; setTextColor(txt)
        background = GradientDrawable().apply { setColor(face); cornerRadius = ctx.dpf(4f); setStroke(ctx.dp(2), INK) }
        setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(3))
    }

    fun lp(ctx: Context, top: Int = 0, bottom: Int = 12, w: Int = LinearLayout.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(top); bottomMargin = ctx.dp(bottom)
        }

    fun row(ctx: Context, vararg v: View, gap: Int = 10) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEachIndexed { i, view ->
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = ctx.dp(gap)
            })
        }
    }
}

/**
 * Botao do tema, em blocos: no tema claro mostra a LUA (toque para a noite); no escuro, o SOL (toque para o dia).
 * Desenhado em uma grade 12x12, igual ao mascote.
 */
class ThemeToggle(ctx: Context) : View(ctx) {
    /** Tema atual: "light" mostra o sol, "dark" a lua, "live" o sol com nuvem. */
    var mode = "light"
        set(v) { field = v; invalidate() }
    private val p = Paint()
    private val d = ctx.resources.displayMetrics.density

    init { isClickable = true; contentDescription = "Escolher tema" }

    override fun onDraw(c: Canvas) {
        val s = 3 * d
        val w = width.toFloat(); val h = height.toFloat() - 4 * d
        // bloco com sombra
        p.style = Paint.Style.FILL
        p.color = Ui.INK; c.drawRoundRect(0f, 4 * d, w, h + 4 * d, 7 * d, 7 * d, p)
        p.color = if (Ui.dark) 0xFF2E3560.toInt() else Ui.PAPER; c.drawRoundRect(0f, 0f, w, h, 7 * d, 7 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s; p.color = Ui.INK
        c.drawRoundRect(s / 2, s / 2, w - s / 2, h - s / 2, 7 * d, 7 * d, p)
        p.style = Paint.Style.FILL
        // grade de pixels
        val u = (minOf(w, h) - 14 * d) / 12f
        val ox = (w - 12 * u) / 2; val oy = (h - 12 * u) / 2
        fun px(x: Int, y: Int, color: Int) { p.color = color; c.drawRect(ox + x * u, oy + y * u, ox + (x + 1) * u, oy + (y + 1) * u, p) }
        if (mode != "dark") {
            // SOL: miolo amarelo com borda laranja e 8 raios (no "ao vivo", com uma nuvem na frente)
            for (y in 4..7) for (x in 4..7) px(x, y, Ui.SUN)
            for (i in 4..7) { px(i, 3, Ui.SUN2); px(i, 8, Ui.SUN2); px(3, i, Ui.SUN2); px(8, i, Ui.SUN2) }
            px(4, 4, 0xFFFFF1A8.toInt()); px(5, 4, 0xFFFFF1A8.toInt()); px(4, 5, 0xFFFFF1A8.toInt())
            for (i in 5..6) { px(i, 0, Ui.SUN); px(i, 11, Ui.SUN); px(0, i, Ui.SUN); px(11, i, Ui.SUN) }
            listOf(1 to 1, 10 to 1, 1 to 10, 10 to 10, 2 to 2, 9 to 2, 2 to 9, 9 to 9).forEach { (x, y) -> px(x, y, Ui.ORANGE) }
            if (mode == "live") {
                listOf("..XXX.....", ".XXXXXXX..", "XXXXXXXXXX").forEachIndexed { r, line ->
                    line.forEachIndexed { col, ch -> if (ch == 'X') px(col + 2, r + 8, 0xFFE9EEF8.toInt()) }
                }
                for (x in 2..11) px(x, 11, 0xFF9AA6C9.toInt())
            }
        } else {
            // LUA: circulo menos um circulo deslocado = crescente, com 2 crateras e estrelinhas
            for (y in 0..11) for (x in 0..11) {
                val inA = (x - 5.5) * (x - 5.5) + (y - 6.0) * (y - 6.0) <= 22.0
                val inB = (x - 8.2) * (x - 8.2) + (y - 4.2) * (y - 4.2) <= 17.0
                if (inA && !inB) px(x, y, 0xFF8E97C9.toInt())
            }
            for (y in 0..11) for (x in 0..11) {
                val inA = (x - 5.5) * (x - 5.5) + (y - 6.0) * (y - 6.0) <= 16.0
                val inB = (x - 8.2) * (x - 8.2) + (y - 4.2) * (y - 4.2) <= 21.0
                if (inA && !inB) px(x, y, 0xFFF4E7B5.toInt())
            }
            px(3, 7, 0xFFD9CA8E.toInt()); px(4, 9, 0xFFD9CA8E.toInt())
            px(10, 9, Ui.SUN); px(11, 1, Ui.SUN); px(8, 11, 0xFFB8C2F0.toInt())
        }
    }
}

/** Medidor de 10 blocos (como no CalorScan PC). */
class MeterView(ctx: Context) : View(ctx) {
    var pct = 0.0
        set(v) { field = v; invalidate() }
    var color = Ui.GREEN
        set(v) { field = v; invalidate() }
    private val p = Paint()
    private val d = ctx.resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (14 * d).toInt())

    override fun onDraw(c: Canvas) {
        val gap = 3 * d
        val bw = (width - gap * 9) / 10f
        val on = Math.round(pct / 10).toInt()
        for (i in 0 until 10) {
            val x = i * (bw + gap)
            p.color = Ui.INK; c.drawRect(x, 0f, x + bw, height.toFloat(), p)
            p.color = if (i < on) color else Ui.EMPTY
            c.drawRect(x + 2 * d, 2 * d, x + bw - 2 * d, height - 2 * d, p)
        }
    }

    companion object {
        fun levelColor(pct: Double) = when { pct >= 85 -> Ui.RED; pct >= 60 -> Ui.ORANGE; pct >= 35 -> Ui.SUN; else -> Ui.GREEN }
    }
}

/** Barra horizontal com borda (lista de quem usa o processador). */
class BarView(ctx: Context, private val frac: Float, private val fill: Int) : View(ctx) {
    private val p = Paint()
    private val d = ctx.resources.displayMetrics.density
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (16 * d).toInt())
    override fun onDraw(c: Canvas) {
        val w = (width * frac.coerceIn(0.03f, 1f))
        p.color = Ui.INK; c.drawRect(0f, 0f, w, height.toFloat(), p)
        p.color = fill; c.drawRect(2 * d, 2 * d, w - 2 * d, height - 2 * d, p)
    }
}

/** Chave liga/desliga em blocos. */
class BlockToggle(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    init {
        isClickable = true
        contentDescription = "chave"
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((58 * d).toInt(), (36 * d).toInt())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && isEnabled) {
            checked = !checked; onChange?.invoke(checked); performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(c: Canvas) {
        val s = 3 * d
        val w = width.toFloat(); val h = height.toFloat() - 4 * d
        p.color = Ui.INK; c.drawRoundRect(0f, 4 * d, w, h + 4 * d, 6 * d, 6 * d, p)
        p.color = if (!isEnabled) Ui.GREY else if (checked) Ui.GREEN else Ui.GREY
        c.drawRoundRect(s, s, w - s, h - s, 4 * d, 4 * d, p)
        p.color = Ui.INK; c.drawRoundRect(0f, 0f, w, h, 6 * d, 6 * d, p.apply { style = Paint.Style.STROKE; strokeWidth = s })
        p.style = Paint.Style.FILL
        val k = h - 2 * s - 6 * d
        val x = if (checked) w - s - 3 * d - k else s + 3 * d
        p.color = Ui.INK; c.drawRect(x, s + 3 * d + 2 * d, x + k, s + 3 * d + k + 2 * d, p)
        p.color = Color.WHITE; c.drawRect(x, s + 3 * d, x + k, s + 3 * d + k, p)
        p.color = Ui.INK; c.drawRect(x, s + 3 * d, x + k, s + 3 * d + k, p.apply { style = Paint.Style.STROKE; strokeWidth = 2 * d })
        p.style = Paint.Style.FILL
    }
}

/** Mascote termometro feito de blocos; muda de cor e expressao com a temperatura. */
class MascotView(ctx: Context) : View(ctx) {
    var temp: Double? = null
        set(v) { field = v; invalidate() }
    private val p = Paint()

    override fun onMeasure(w: Int, h: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension((16 * 5 * d).toInt(), (22 * 5 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val u = width / 16f
        fun b(x: Int, y: Int, w: Int, h: Int, color: Int) {
            p.color = color; c.drawRect(x * u, y * u, (x + w) * u, (y + h) * u, p)
        }
        val (face, side) = Ui.tempColor(temp)
        val t = temp
        b(5, 1, 6, 13, Ui.INK); b(6, 2, 4, 11, 0xFFEEF4FB.toInt()); b(9, 2, 1, 11, 0xFFD3DDE8.toInt())
        val lvl = if (t == null) 6 else ((t - 30) / 1.6).toInt().coerceIn(2, 10)
        b(6, 13 - lvl, 4, lvl, face); b(9, 13 - lvl, 1, lvl, side)
        b(2, 12, 12, 9, Ui.INK); b(3, 13, 10, 7, face); b(11, 13, 2, 7, side); b(3, 13, 8, 1, 0x59FFFFFF)
        b(5, 15, 2, 2, Ui.INK); b(9, 15, 2, 2, Ui.INK); b(5, 15, 1, 1, Color.WHITE); b(9, 15, 1, 1, Color.WHITE)
        b(6, 18, 4, 1, Ui.INK)
        if (t == null || t < 43) { b(5, 17, 1, 1, Ui.INK); b(10, 17, 1, 1, Ui.INK) }
        if (t != null && t >= 40) { b(13, 9, 1, 2, 0xFF4FC3F7.toInt()); b(14, 10, 1, 1, 0xFF4FC3F7.toInt()) }
        if (t != null && t >= 43) { b(1, 8, 1, 2, 0xFF4FC3F7.toInt()); b(0, 9, 1, 1, 0xFF4FC3F7.toInt()) }
    }
}

/** Grafico de barras em blocos da temperatura recente. */
class ChartView(ctx: Context) : View(ctx) {
    var points: List<Double> = emptyList()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (110 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = Ui.CHART
        c.drawRoundRect(0f, 0f, w, h, 6 * d, 6 * d, p)
        if (points.size >= 2) {
            val lo = (points.min() - 1).coerceAtMost(36.0)
            val hi = (points.max() + 0.5).coerceAtLeast(44.0)
            val bw = (w - 8 * d) / points.size
            points.forEachIndexed { i, t ->
                val bh = ((t - lo) / (hi - lo) * (h - 12 * d)).toFloat()
                p.color = Ui.tempColor(t).first
                c.drawRect(4 * d + i * bw, h - 4 * d - bh, 4 * d + (i + 1) * bw - d, h - 4 * d, p)
            }
            val y = (h - 4 * d - (43 - lo) / (hi - lo) * (h - 12 * d)).toFloat()
            p.color = Ui.INK
            var x = 4 * d
            while (x < w - 4 * d) { c.drawRect(x, y - d, x + 6 * d, y + d, p); x += 11 * d }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = Ui.INK
        c.drawRoundRect(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d, 6 * d, 6 * d, p)
        p.style = Paint.Style.FILL
    }
}
