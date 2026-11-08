package br.calorscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Tempo e hora para o tema "Ao vivo".
 *
 * So e usado quando a pessoa escolhe esse tema. Pega a localizacao APROXIMADA (arredondada para ~10 km) e
 * consulta o open-meteo.com (gratis, sem cadastro) no maximo a cada 30 min e so com o app aberto.
 * Sem localizacao: usa so a hora (nascer 06:00 / por do sol 18:00) e a fase da lua (calculada no celular).
 */
object Weather {
    data class Now(
        val code: Int,          // codigo WMO do tempo (0 limpo ... 95-99 tempestade)
        val cloud: Int,         // % de nuvens
        val precip: Double,     // mm/h
        val wind: Double,       // km/h
        val sunrise: Long,      // ms de hoje
        val sunset: Long,
        val at: Long,           // quando foi consultado
        val city: Boolean,      // veio da internet (true) ou e so o relogio (false)
    ) {
        val thunder get() = code in 95..99
        /** 0 = sem chuva ... 1 = chuva muito forte. */
        val rain: Double get() = when {
            code in 95..99 || code == 65 || code == 82 || precip >= 7.6 -> 1.0
            code == 63 || code == 81 || precip >= 2.5 -> 0.65
            code in 51..67 || code in 80..82 || precip >= 0.2 -> 0.35
            else -> 0.0
        }
        val fog get() = code == 45 || code == 48
    }

    private const val EVERY_MS = 30 * 60_000L

    fun hasLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** O que ja se sabe (sem internet: so o relogio). */
    fun current(ctx: Context): Now {
        val p = Store.prefs(ctx)
        val js = p.getString("wx", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val today = dayStart()
        if (js != null && System.currentTimeMillis() - js.getLong("at") < 6 * 3_600_000L) {
            // horarios do sol guardados como minutos do dia: valem para hoje tambem
            return Now(js.getInt("code"), js.getInt("cloud"), js.getDouble("precip"), js.getDouble("wind"),
                today + js.getInt("rise") * 60_000L, today + js.getInt("set") * 60_000L, js.getLong("at"), true)
        }
        val rise = js?.optInt("rise", 360) ?: 360; val set = js?.optInt("set", 1080) ?: 1080
        return Now(0, 0, 0.0, 0.0, today + rise * 60_000L, today + set * 60_000L, 0, false)
    }

    fun stale(ctx: Context): Boolean {
        val at = Store.prefs(ctx).getString("wx", null)?.let { runCatching { JSONObject(it).getLong("at") }.getOrNull() } ?: 0
        return System.currentTimeMillis() - at > EVERY_MS
    }

    /** Consulta o tempo (fora da linha principal). Devolve true se mudou algo. */
    fun refresh(ctx: Context): Boolean = runCatching {
        if (!hasLocation(ctx) || !stale(ctx)) return false
        val lm = ctx.getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return false
        // arredonda para 1 casa (~10 km): o servico so precisa da cidade
        val lat = Math.round(loc.latitude * 10) / 10.0
        val lon = Math.round(loc.longitude * 10) / 10.0
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=weather_code,cloud_cover,precipitation,wind_speed_10m&daily=sunrise,sunset&timezone=auto&forecast_days=1")
        val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(body)
        val cur = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        fun minutes(iso: String) = iso.substringAfter('T').split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        val js = JSONObject()
            .put("code", cur.optInt("weather_code", 0)).put("cloud", cur.optInt("cloud_cover", 0))
            .put("precip", cur.optDouble("precipitation", 0.0)).put("wind", cur.optDouble("wind_speed_10m", 0.0))
            .put("rise", minutes(daily.getJSONArray("sunrise").getString(0)))
            .put("set", minutes(daily.getJSONArray("sunset").getString(0)))
            .put("at", System.currentTimeMillis())
        Store.prefs(ctx).edit().putString("wx", js.toString()).apply()
        true
    }.getOrDefault(false)

    fun dayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ------------------------------------------------------------------ lua
    private const val SYNODIC = 29.530588853
    private const val NEW_MOON_2000 = 947182440000L // 06/01/2000 18:14 UTC

    /** Idade da lua em dias (0 = nova, ~14,8 = cheia). */
    fun moonAge(t: Long = System.currentTimeMillis()): Double {
        val days = (t - NEW_MOON_2000) / 86_400_000.0
        return days - floor(days / SYNODIC) * SYNODIC
    }

    /** Fracao iluminada (0..1). */
    fun moonLit(age: Double) = (1 - cos(2 * PI * age / SYNODIC)) / 2

    fun moonPhaseName(age: Double) = when {
        age < 1.5 || age > 28 -> "lua nova"
        age < 6.4 -> "lua crescente"
        age < 8.4 -> "quarto crescente"
        age < 13.8 -> "gibosa crescente"
        age < 15.8 -> "lua cheia"
        age < 21.1 -> "gibosa minguante"
        age < 23.1 -> "quarto minguante"
        else -> "lua minguante"
    }

    /** Lua passa no ponto mais alto do ceu ~ ao meio-dia na lua nova e ~ a meia-noite na cheia. */
    fun moonTransitHour(age: Double) = (12 + 24 * age / SYNODIC) % 24
}
