package br.calorscan

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/** Uma leitura de temperatura. */
data class Sample(
    val t: Long,          // hora (ms)
    val up: Long,         // segundos desde que o celular ligou
    val c: Double,        // temperatura da bateria (°C)
    val st: Int,          // estado termico do sistema (0..6)
    val plug: Boolean,    // carregando
    val scr: Boolean,     // tela ligada
    val fg: String?,      // app na tela
) {
    fun json(): String = JSONObject().put("t", t).put("up", up).put("c", c).put("st", st)
        .put("plug", plug).put("scr", scr).put("fg", fg ?: JSONObject.NULL).toString()

    companion object {
        fun parse(s: String): Sample? = runCatching {
            val o = JSONObject(s)
            Sample(o.getLong("t"), o.getLong("up"), o.getDouble("c"), o.getInt("st"),
                o.getBoolean("plug"), o.getBoolean("scr"), if (o.isNull("fg")) null else o.getString("fg"))
        }.getOrNull()
    }
}

object Store {
    private const val MAX_LINES = 6000 // ~2 dias

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("calorscan", Context.MODE_PRIVATE)
    private fun log(ctx: Context) = File(ctx.filesDir, "temps.jsonl")

    @Synchronized
    fun append(ctx: Context, s: Sample) {
        val f = log(ctx)
        f.appendText(s.json() + "\n")
        if (f.length() > 900_000) { // corta o historico antigo
            val keep = f.readLines().takeLast(MAX_LINES / 2)
            f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun samples(ctx: Context, sinceMs: Long = 0): List<Sample> {
        val f = log(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { Sample.parse(it) }.filter { it.t >= sinceMs }
    }

    /** Leituras desde o ultimo boot. */
    fun bootSamples(ctx: Context): List<Sample> {
        val bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        return samples(ctx, bootAt - 5_000)
    }

    // ------------------------------------------------------------- configuracoes
    fun monitorOn(ctx: Context) = prefs(ctx).getBoolean("monitor", true)
    fun setMonitor(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("monitor", v).apply()
    fun alertAt(ctx: Context) = prefs(ctx).getInt("alert_at", 43)
    fun setAlertAt(ctx: Context, v: Int) = prefs(ctx).edit().putInt("alert_at", v).apply()

    fun getSet(ctx: Context, key: String): Set<String> = prefs(ctx).getStringSet(key, emptySet())!!.toSet()
    fun putSet(ctx: Context, key: String, v: Set<String>) = prefs(ctx).edit().putStringSet(key, v).apply()
    fun getBool(ctx: Context, key: String, def: Boolean = false) = prefs(ctx).getBoolean(key, def)
    fun putBool(ctx: Context, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(key, v).apply()
    fun getStr(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)
    fun putStr(ctx: Context, key: String, v: String?) = prefs(ctx).edit().putString(key, v).apply()
}
