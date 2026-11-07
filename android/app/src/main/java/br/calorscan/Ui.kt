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
    const val INK = 0xFF2B2D42.toInt()
    const val SKY1 = 0xFF7FD3FF.toInt()
    const val SKY2 = 0xFFC6EFFF.toInt()
    const val GRASS = 0xFF62D26F.toInt()
    const val GRASS2 = 0xFF3FA452.toInt()
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
    const val GREY = 0xFFCFD5DD.toInt()
    const val GREY2 = 0xFF9AA3AD.toInt()
    const val MUTED = 0xFF5D6177.toInt()
    const val WHITE = Color.WHITE

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

    enum class Kind(val face: Int, val side: Int, val text: Int) {
        BLUE_(BLUE, BLUE2, WHITE), GREEN_(GREEN, GREEN2, WHITE), ORANGE_(ORANGE, ORANGE2, WHITE),
        RED_(RED, RED2, WHITE), SUN_(SUN, SUN2, INK), WHITE_(WHITE, GREY2, INK)
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

    fun title(ctx: Context, s: String, size: Float = 18f, color: Int = INK) = TextView(ctx).apply {
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
        p.style = Paint.Style.FILL; p.color = 0xFFF3F8FF.toInt()
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
