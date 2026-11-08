package br.calorscan

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ceu do app, em blocos (como o mascote).
 *
 * - Claro: dia de sol. Escuro: noite com lua (fase real) e estrelas. Trocar entre eles anima o por/nascer do sol.
 * - Ao vivo: cor pela hora real (dia, nascer/por do sol, noite), nuvens e chuva do tempo real, raios em
 *   tempestade, vento. Mudancas de tempo/hora escorregam devagar.
 *
 * Peso: so a faixa do ceu se mexe, a 12-20 quadros/s, e so quando ha algo para mexer (nuvem, chuva, raio,
 * vento ou uma transicao). Quem usa liga/desliga a animacao com [SkyDrawable.animate] (o app desliga fora da
 * tela, na economia de bateria e quando o celular esquenta).
 */
object Sky {
    data class Scene(
        val top: Int, val mid: Int, val bottom: Int, val darkUi: Boolean,
        val sun: Pair<Float, Float>?, val golden: Float,     // posicao (0..1 da largura, 0..1 do arco)
        val moon: Pair<Float, Float>?, val moonAge: Double, val moonDay: Boolean,
        val stars: Boolean, val clouds: Int, val cloudDark: Float,
        val rain: Double, val thunder: Boolean, val wind: Boolean, val fog: Boolean,
        val label: String,
        val veil: Float = 0f,   // 0 = ceu limpo; 1 = fechado (sol e lua somem atras das nuvens)
    )

    private val DAY = intArrayOf(0xFF4FB3F4.toInt(), 0xFF8ED4FA.toInt(), 0xFFC6EFFF.toInt())
    private val GOLD = intArrayOf(0xFF3E4C9E.toInt(), 0xFFE7789A.toInt(), 0xFFFFA45C.toInt())
    private val NIGHT = intArrayOf(0xFF070C24.toInt(), 0xFF111A42.toInt(), 0xFF26305E.toInt())
    private val GREY_DAY = intArrayOf(0xFF5E6779.toInt(), 0xFF7D8596.toInt(), 0xFF9AA2B2.toInt())
    private val GREY_NIGHT = intArrayOf(0xFF0E1220.toInt(), 0xFF181C2C.toInt(), 0xFF262B3B.toInt())

    fun scene(wx: Weather.Now, now: Long = System.currentTimeMillis()): Scene {
        val rise = wx.sunrise; val set = wx.sunset
        val isDay = now in rise..set
        val toEvent = minOf(abs(now - rise), abs(now - set)) / 60_000.0
        val golden = (1 - toEvent / 60.0).coerceIn(0.0, 1.0).toFloat()
        val base = if (isDay) mix(DAY, GOLD, golden) else mix(NIGHT, GOLD, golden * 0.8f)
        val k = (wx.cloud / 100.0 * 0.45 + wx.rain * 0.45 + if (wx.thunder) 0.15 else 0.0).coerceIn(0.0, 0.9).toFloat()
        val sky = mix(base, if (isDay) GREY_DAY else GREY_NIGHT, k)
        val nightish = !isDay && golden < 0.35f
        val darkUi = nightish || k > 0.62f
        val sun = if (isDay) {
            val p = ((now - rise).toDouble() / (set - rise)).toFloat()
            (0.08f + 0.84f * p) to (1f - sin(PI * p).toFloat())
        } else null
        val age = Weather.moonAge(now)
        val hourNow = Calendar.getInstance().run { timeInMillis = now; get(Calendar.HOUR_OF_DAY) + get(Calendar.MINUTE) / 60.0 }
        var h = hourNow - Weather.moonTransitHour(age); if (h > 12) h -= 24; if (h < -12) h += 24
        val moon = if (abs(h) < 6 && Weather.moonLit(age) > 0.04) {
            val p = ((h + 6) / 12).toFloat(); (0.08f + 0.84f * p) to (1f - sin(PI * p).toFloat())
        } else null
        val clouds = when {
            wx.rain > 0 || wx.thunder -> maxOf(3, (wx.cloud / 22.0).toInt())
            else -> (wx.cloud / 22.0).toInt()
        }.coerceAtMost(4)
        val label = buildString {
            append(when { golden > 0.3f && now < rise + 3_600_000 && now > rise - 3_600_000 -> "nascer do sol"
                          golden > 0.3f -> "pôr do sol"; isDay -> "dia"; else -> "noite" })
            if (wx.thunder) append(" · tempestade") else if (wx.rain >= 0.65) append(" · chuva forte")
            else if (wx.rain > 0) append(" · chuva") else if (wx.cloud > 70) append(" · nublado")
            if (wx.wind >= 28) append(" · vento")
            if (!isDay || moon != null) append(" · ${Weather.moonPhaseName(age)}")
        }
        return Scene(sky[0], sky[1], sky[2], darkUi, sun, golden, moon, age, isDay, nightish && wx.cloud < 60,
            clouds, (wx.rain * 0.8 + if (wx.thunder) 0.2 else 0.0).toFloat().coerceIn(0f, 1f) + if (!isDay) 0.3f else 0f,
            wx.rain, wx.thunder, wx.wind >= 28, wx.fog, label,
            veil = (wx.cloud / 100f * 0.8f + wx.rain.toFloat() * 0.3f).coerceIn(0f, 0.92f))
    }

    // ---------------------------------------------------------------- temas Claro e Escuro
    private const val DAY_H = 10.5    // claro = 10:30 de um dia bonito
    private const val NIGHT_H = 22.5  // escuro = 22:30 de noite limpa

    private fun fakeWx(v: Double, cloud: Int): Weather.Now {
        val day = Math.floorDiv(v.toLong(), 24L)
        val base = Weather.dayStart() + day * 86_400_000L
        return Weather.Now(if (cloud > 0) 2 else 0, cloud, 0.0, 0.0, base + 6 * 3_600_000L, base + 18 * 3_600_000L, 0, false)
    }

    /** Ceu na "hora virtual" v (em horas a partir de hoje 00:00). A lua sobe depois do por do sol. */
    fun virtual(v: Double): Scene {
        val hourOfDay = ((v % 24) + 24) % 24
        // nuvens: 1 no dia (bonito), nenhuma na noite
        val cloud = if (hourOfDay in 7.0..17.0) 25 else 0
        val sc = scene(fakeWx(v, cloud), Weather.dayStart() + (v * 3_600_000).toLong())
        // lua fixa e alta na noite; nasce no fim da tarde e se poe de madrugada (fica bonita no escuro)
        val moon = when (hourOfDay) {
            in 18.0..24.0 -> { val p = ((hourOfDay - 18) / 4.5).coerceIn(0.0, 1.0).toFloat(); (0.94f - 0.22f * p) to (1f - 0.75f * p) }
            in 0.0..5.5 -> { val p = (hourOfDay / 5.5).toFloat(); (0.72f - 0.64f * p) to (0.25f + 0.75f * p) }
            else -> null
        }
        return sc.copy(moon = moon, moonDay = false, stars = hourOfDay >= 18.8 || hourOfDay <= 5.2,
            label = if (sc.sun != null) "dia" else "noite · ${Weather.moonPhaseName(sc.moonAge)}")
    }

    fun light() = virtual(DAY_H)
    fun dark() = virtual(NIGHT_H)
    /** Caminho da animacao: do claro para o escuro passa pelo por do sol; do escuro para o claro, pelo nascer. */
    fun transitionHours(toDark: Boolean) = if (toDark) DAY_H to NIGHT_H else NIGHT_H to (DAY_H + 24)

    private fun mix(a: IntArray, b: IntArray, t: Float) = IntArray(3) { lerp(a[it], b[it], t) }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * u).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Fundo da tela inteira: so o degrade (parado). Mesmas posicoes de cor da faixa, para nao ter emenda. */
class SkyBackground : Drawable() {
    private val p = Paint()
    private var colors = intArrayOf(0xFF7FD3FF.toInt(), 0xFF9FDDFF.toInt(), 0xFFC6EFFF.toInt())
    private var key = 0

    fun set(top: Int, mid: Int, bottom: Int) {
        colors = intArrayOf(top, mid, bottom); key = 0; invalidateSelf()
    }

    override fun draw(c: Canvas) {
        val h = bounds.height()
        if (key != h) {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), colors, floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP); key = h
        }
        c.drawRect(bounds, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** O desenho do ceu. "band" = altura da faixa de cima (barra do topo), onde ficam sol, lua, nuvens e chuva. */
class SkyDrawable(private val d: Float) : Drawable(), Runnable {
    /** Valores atuais (vao escorregando ate a cena-alvo). */
    private class Cur(
        var top: Int, var mid: Int, var bottom: Int,
        var sunX: Float, var sunY: Float, var sunA: Float, var golden: Float,
        var moonX: Float, var moonY: Float, var moonA: Float, var moonAge: Double, var moonDay: Boolean,
        var stars: Float, var clouds: Float, var cloudDark: Float, var rain: Float, var wind: Float, var fog: Float,
        var thunder: Boolean, var veil: Float,
    )

    var target: Sky.Scene? = null; private set
    private var cur: Cur? = null
    var band = 0f
        set(v) { field = v; invalidateSelf() }

    /** Pode se mexer? (o app desliga fora da tela, na economia de bateria e com o celular quente) */
    var animate = false
        set(v) { field = v; kick() }

    /** Cor de cima do ceu agora (para a barra de status acompanhar). */
    val topColor get() = cur?.top ?: 0
    /** Avisa as 3 cores do degrade quando mudam: o fundo da tela (parado) usa as mesmas. */
    var onColors: ((Int, Int, Int) -> Unit)? = null
    /** A faixa fica no meio do degrade da tela: onde ela comeca (y) e a altura da tela toda. */
    var gradOffset = 0f
    var gradHeight = 0f
    private var lastColors = 0L

    private val p = Paint()
    private val sky = Paint()
    private var shaderKey = 0L
    private var last = 0L
    private val t0 = SystemClock.uptimeMillis()
    private var flashUntil = 0L
    private var nextFlash = 0L
    private var seed = 99L

    /** Mostra uma cena: [smooth] = escorrega ate ela (ao vivo); senao, pula direto (claro/escuro e animacao do por do sol). */
    fun show(sc: Sky.Scene, smooth: Boolean) {
        target = sc
        val c = cur
        // sem animacao (economia de bateria, celular quente...) a mudanca e direta
        if (c == null || !smooth || !animate) cur = from(sc, c).also { tellColors(it) } else kick()
        invalidateSelf()
    }

    private fun from(s: Sky.Scene, old: Cur?) = Cur(
        s.top, s.mid, s.bottom,
        s.sun?.first ?: old?.sunX ?: 0.9f, s.sun?.second ?: old?.sunY ?: 1f, if (s.sun != null) 1f else 0f, s.golden,
        s.moon?.first ?: old?.moonX ?: 0.1f, s.moon?.second ?: old?.moonY ?: 1f, if (s.moon != null) 1f else 0f, s.moonAge, s.moonDay,
        if (s.stars) 1f else 0f, s.clouds.toFloat(), s.cloudDark, s.rain.toFloat(), if (s.wind) 1f else 0f, if (s.fog) 1f else 0f,
        s.thunder, s.veil,
    )

    /** Ha algo para mexer? */
    private fun moving(): Boolean {
        val c = cur ?: return false; val s = target ?: return false
        val settling = c.top != s.top || c.bottom != s.bottom || abs(c.clouds - s.clouds) > 0.02f || abs(c.rain - s.rain.toFloat()) > 0.02f ||
            abs(c.cloudDark - s.cloudDark) > 0.02f || abs(c.sunA - (if (s.sun != null) 1f else 0f)) > 0.02f
        return animate && (settling || c.clouds > 0.05f || c.rain > 0.02f || c.thunder || c.wind > 0.05f)
    }

    private fun kick() {
        if (moving()) { unscheduleSelf(this); scheduleSelf(this, SystemClock.uptimeMillis() + frameMs()) }
    }

    /** Chuva/raio a 15 quadros/s; so nuvem/vento a 8 (movimento lento; em blocos fica natural). */
    private fun frameMs() = cur?.let { if (it.rain > 0.02f || it.thunder || it.wind > 0.05f) 66L else 125L } ?: 125L

    override fun run() { invalidateSelf() }

    private fun step(now: Long) {
        val c = cur ?: return; val s = target ?: return
        val dt = if (last == 0L) 0L else (now - last).coerceAtMost(500)
        last = now
        if (dt == 0L || !animate) return
        // escorrega ate a cena nova em ~3 s (constante de tempo 1,2 s)
        val k = (1 - exp(-dt / 1200.0)).toFloat()
        // encaixa no destino quando chega perto: senao o arredondamento deixava a transicao "sem fim" e redesenhava sempre
        fun f(a: Float, b: Float) = if (abs(b - a) < 0.01f) b else a + (b - a) * k
        c.top = approach(c.top, s.top, k); c.mid = approach(c.mid, s.mid, k); c.bottom = approach(c.bottom, s.bottom, k)
        s.sun?.let { c.sunX = f(c.sunX, it.first); c.sunY = f(c.sunY, it.second) }
        c.sunA = f(c.sunA, if (s.sun != null) 1f else 0f); c.golden = f(c.golden, s.golden)
        s.moon?.let { c.moonX = f(c.moonX, it.first); c.moonY = f(c.moonY, it.second) }
        c.moonA = f(c.moonA, if (s.moon != null) 1f else 0f); c.moonAge = s.moonAge; c.moonDay = s.moonDay
        c.stars = f(c.stars, if (s.stars) 1f else 0f); c.clouds = f(c.clouds, s.clouds.toFloat())
        c.cloudDark = f(c.cloudDark, s.cloudDark); c.rain = f(c.rain, s.rain.toFloat())
        c.wind = f(c.wind, if (s.wind) 1f else 0f); c.fog = f(c.fog, if (s.fog) 1f else 0f); c.thunder = s.thunder; c.veil = f(c.veil, s.veil)
        tellColors(c)
    }

    override fun draw(cv: Canvas) {
        val c = cur ?: return
        val now = SystemClock.uptimeMillis()
        step(now)
        val tSec = (now - t0) / 1000f
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val gh = if (gradHeight > 0) gradHeight else h
        val key = (c.top.toLong() shl 32) xor (c.bottom.toLong() * 31 + c.mid) xor h.toLong() xor (gh.toLong() shl 20) xor gradOffset.toLong()
        if (key != shaderKey) {
            sky.shader = LinearGradient(0f, -gradOffset, 0f, gh - gradOffset, intArrayOf(c.top, c.mid, c.bottom), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            shaderKey = key
        }
        cv.drawRect(0f, 0f, w, h, sky)
        val bandH = if (band > 0) band else h
        val u = 3.2f * d
        val top = bandH * 0.14f; val horizon = bandH * 0.96f
        fun arcY(f: Float) = top + (horizon - top) * f

        if (c.stars > 0.02f) {
            p.color = ((0xCC * c.stars).toInt() shl 24) or 0xFFFFFF
            STARS.forEach { (fx, fy, big) -> val x = fx * w; val y = fy * bandH * 0.9f; cv.drawRect(x, y, x + u * big, y + u * big, p) }
        }
        val clear = 1f - c.veil
        if (c.moonA * clear > 0.02f) moon(cv, c.moonX * w, arcY(c.moonY), u, c.moonAge, ((if (c.moonDay) 0x99 else 0xFF) * c.moonA * clear).toInt())
        if (c.sunA * clear > 0.02f) sun(cv, c.sunX * w, arcY(c.sunY), u, c.golden, c.sunA * clear)

        // nuvens andando devagar para a direita (mais rapido com vento); entram de novo pela esquerda
        val cloudColor = Sky.lerp(0xFFFFFFFF.toInt(), 0xFF3A4156.toInt(), c.cloudDark.coerceIn(0f, 1f))
        val speed = (6f + 22f * c.wind) * d            // px por segundo
        val cw = 12 * u
        val n = c.clouds
        CLOUD_SPOTS.forEachIndexed { i, (fx, fy) ->
            val a = (n - i).coerceIn(0f, 1f)            // nuvem "nova" aparece aos poucos
            if (a <= 0.02f) return@forEachIndexed
            val scale = if (i % 2 == 0) 1.15f else 0.9f
            val span = w + cw * scale
            val x = ((fx * w + tSec * speed * (0.7f + 0.15f * i)) % span) - cw * scale
            val y = fy * bandH
            cloud(cv, x, y, u * scale, withAlpha(cloudColor, a), withAlpha(if (c.cloudDark > 0.5f) 0xFF262B3C.toInt() else 0x33000000, a))
            if (c.thunder && i == 1 && now < flashUntil) bolt(cv, x + 3 * u * scale, y + 5 * u * scale, u)
        }
        if (c.wind > 0.05f) {
            p.color = ((0x99 * c.wind).toInt() shl 24) or 0xFFFFFF
            listOf(0.18f to 0.52f, 0.55f to 0.42f, 0.70f to 0.72f).forEachIndexed { i, (fx, fy) ->
                val x = ((fx * w + tSec * (40f + 10 * i) * d) % (w + 12 * u)) - 12 * u; val y = fy * bandH * 0.8f
                cv.drawRect(x, y, x + 9 * u, y + u * 0.8f, p); cv.drawRect(x + 9 * u, y - u, x + 10 * u, y, p)
                cv.drawRect(x + 2 * u, y + 2 * u, x + 7 * u, y + 2.8f * u, p)
            }
        }
        if (c.rain > 0.02f) {
            // gotas caindo das nuvens, na faixa do ceu
            val alpha = (c.rain * 3).coerceAtMost(1f)
            p.color = withAlpha(if (c.cloudDark > 0.5f) 0xFF9FC3F0.toInt() else 0xFFDDEBFF.toInt(), 0.7f * alpha)
            val count = (70 * c.rain).toInt() + 16
            val y0 = bandH * 0.40f; val range = bandH - y0
            val fall = (180f + 260f * c.rain) * d          // px por segundo
            for (i in 0 until count) {
                val fx = DROPS[i % DROPS.size].first; val fy = DROPS[i % DROPS.size].second
                val y = y0 + ((fy * range + tSec * fall * (0.85f + 0.3f * fx)) % range)
                val x = (fx * w - (y - y0) * 0.18f + w) % w
                cv.drawRect(x, y, x + u * 0.6f, y + u * 1.8f, p)
            }
        }
        // raio: um clarao de vez em quando (a cada 4-12 s) quando ha tempestade
        if (c.thunder && animate) {
            if (now >= nextFlash) {
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                flashUntil = now + 140 + (seed % 120)
                nextFlash = now + 4000 + (seed % 8000)
            }
            if (now < flashUntil) { p.color = 0x55FFFFFF; cv.drawRect(0f, 0f, w, bandH, p) }
        }
        if (c.fog > 0.02f) { p.color = ((0x40 * c.fog).toInt() shl 24) or 0xFFFFFF; cv.drawRect(0f, bandH * 0.4f, w, h, p) }

        // um agendamento so por vez (antes cada desenho somava mais um e a animacao acelerava sem parar)
        unscheduleSelf(this)
        if (moving()) scheduleSelf(this, now + frameMs())
    }

    /** Cor que anda ate o destino e chega exatamente nele (sem ficar 1 unidade antes por arredondamento). */
    private fun approach(a: Int, b: Int, k: Float): Int {
        fun ch(sh: Int): Int { val x = a shr sh and 255; val y = b shr sh and 255; val d = y - x
            return if (abs(d) <= 2) y else x + Math.round(d * k) }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun tellColors(c: Cur) {
        val k = (c.top.toLong() shl 32) xor (c.mid.toLong() * 131) xor c.bottom.toLong()
        if (k != lastColors) { lastColors = k; onColors?.invoke(c.top, c.mid, c.bottom) }
    }

    private fun withAlpha(color: Int, a: Float) = ((((color ushr 24) * a).toInt().coerceIn(0, 255)) shl 24) or (color and 0xFFFFFF)

    private fun px(c: Canvas, ox: Float, oy: Float, u: Float, x: Int, y: Int, color: Int) {
        p.color = color; c.drawRect(ox + x * u, oy + y * u, ox + (x + 1) * u, oy + (y + 1) * u, p)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, u: Float, golden: Float, a: Float) {
        val core = withAlpha(Sky.lerp(Ui.SUN, 0xFFFF8A3D.toInt(), golden), a)
        val rim = withAlpha(Sky.lerp(Ui.SUN2, 0xFFE0552B.toInt(), golden), a)
        val shine = withAlpha(0xFFFFF4C2.toInt(), a)
        val ox = cx - 6 * u; val oy = cy - 6 * u
        for (y in 4..7) for (x in 4..7) px(c, ox, oy, u, x, y, core)
        for (i in 4..7) { px(c, ox, oy, u, i, 3, rim); px(c, ox, oy, u, i, 8, rim); px(c, ox, oy, u, 3, i, rim); px(c, ox, oy, u, 8, i, rim) }
        px(c, ox, oy, u, 4, 4, shine); px(c, ox, oy, u, 5, 4, shine)
        if (golden < 0.8f) {
            for (i in 5..6) { px(c, ox, oy, u, i, 0, core); px(c, ox, oy, u, i, 11, core); px(c, ox, oy, u, 0, i, core); px(c, ox, oy, u, 11, i, core) }
            listOf(1 to 1, 10 to 1, 1 to 10, 10 to 10).forEach { (x, y) -> px(c, ox, oy, u, x, y, rim) }
        }
    }

    /** Lua com a fase de verdade: ilumina o lado direito crescendo e o esquerdo minguando. */
    private fun moon(c: Canvas, cx: Float, cy: Float, u: Float, age: Double, alpha: Int) {
        val phi = 2 * PI * age / 29.530588853
        val lit = (alpha shl 24) or 0xF4E7B5
        val shade = ((alpha * 0.28).toInt() shl 24) or 0xC9D2F2
        val ox = cx - 5.5f * u; val oy = cy - 5.5f * u
        for (y in 0..10) for (x in 0..10) {
            val xn = (x - 5.0) / 5.2; val yn = (y - 5.0) / 5.2
            if (xn * xn + yn * yn > 1) continue
            val edge = kotlin.math.cos(phi) * sqrt(1 - yn * yn)
            val on = if (phi < PI) xn > edge else xn < -edge
            px(c, ox, oy, u, x, y, if (on) lit else shade)
        }
        if (age in 11.0..18.0) { px(c, ox, oy, u, 3, 4, (alpha shl 24) or 0xDCCD92); px(c, ox, oy, u, 6, 7, (alpha shl 24) or 0xDCCD92) }
    }

    private fun cloud(c: Canvas, x: Float, y: Float, u: Float, color: Int, shadow: Int) {
        p.color = color
        CLOUD.forEachIndexed { row, line ->
            var col = 0
            while (col < line.length) {        // junta blocos seguidos num retangulo so (menos chamadas de desenho)
                if (line[col] != 'X') { col++; continue }
                val start = col
                while (col < line.length && line[col] == 'X') col++
                c.drawRect(x + start * u, y + row * u, x + col * u, y + (row + 1) * u, p)
            }
        }
        p.color = shadow; c.drawRect(x + u, y + CLOUD.size * u, x + (CLOUD[0].length - 1) * u, y + (CLOUD.size + 0.6f) * u, p)
    }

    private fun bolt(c: Canvas, x: Float, y: Float, u: Float) {
        listOf(2 to 0, 1 to 1, 0 to 2, 1 to 2, 2 to 2, 1 to 3, 0 to 4).forEach { (bx, by) -> px(c, x, y, u, bx, by, Ui.SUN) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private val CLOUD = listOf(
            "...XXX......",
            "..XXXXX.XX..",
            ".XXXXXXXXXX.",
            "XXXXXXXXXXXX",
            ".XXXXXXXXXX.",
        )
        private val CLOUD_SPOTS = listOf(0.58f to 0.12f, 0.18f to 0.26f, 0.80f to 0.34f, 0.38f to 0.06f)
        private val STARS = listOf(
            Triple(0.06f, 0.10f, 1f), Triple(0.18f, 0.42f, 0.7f), Triple(0.31f, 0.18f, 0.7f), Triple(0.44f, 0.50f, 1f),
            Triple(0.52f, 0.12f, 0.7f), Triple(0.66f, 0.36f, 0.7f), Triple(0.74f, 0.08f, 1f), Triple(0.88f, 0.48f, 0.7f),
            Triple(0.94f, 0.20f, 0.7f), Triple(0.12f, 0.62f, 0.7f), Triple(0.38f, 0.70f, 0.7f), Triple(0.80f, 0.66f, 0.7f),
        )
        /** Posicoes fixas das gotas (0..1), sorteadas uma vez. */
        private val DROPS: List<Pair<Float, Float>> = run {
            var s = 7L
            List(90) {
                s = (s * 1103515245 + 12345) and 0x7fffffff; val a = (s % 1000) / 1000f
                s = (s * 1103515245 + 12345) and 0x7fffffff; val b = (s % 1000) / 1000f
                a to b
            }
        }
    }
}
