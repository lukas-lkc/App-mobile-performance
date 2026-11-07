package br.calorscan

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class Report(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private val labels = HashMap<String, String>()

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private fun uidName(uid: Int): String = when (uid) {
        1000 -> "Sistema Android"
        -4 -> "Apps removidos"
        -5 -> "Roteador/hotspot"
        0 -> "root/kernel"
        else -> pm.getPackagesForUid(uid)?.joinToString(",") { it } ?: "uid $uid"
    }

    // ----------------------------------------------------------------- por app (tela)
    class AppHeat(val key: String) {
        var samples = 0
        var tempSum = 0.0
        var tempMax = 0.0
        var dTemp = 0.0
        var dMin = 0.0
        val avg get() = if (samples > 0) tempSum / samples else 0.0
        /** graus por 10 minutos */
        val rate get() = if (dMin >= 2) dTemp / dMin * 10 else Double.NaN
    }

    private fun key(s: JSONObject): String {
        val base = when {
            !s.optBoolean("screen_on") -> "(tela desligada)"
            s.isNull("fg") -> "(desconhecido)"
            else -> s.getString("fg")
        }
        return if (s.optString("plugged", "none") != "none") "$base + carregando" else base
    }

    fun heatByApp(samples: List<JSONObject>): List<AppHeat> {
        val m = LinkedHashMap<String, AppHeat>()
        for ((i, s) in samples.withIndex()) {
            val t = s.optDouble("batt_c")
            if (t.isNaN()) continue
            val k = key(s)
            val a = m.getOrPut(k) { AppHeat(k) }
            a.samples++; a.tempSum += t; a.tempMax = max(a.tempMax, t)
            if (i > 0) {
                val p = samples[i - 1]
                val dt = (s.getLong("t") - p.getLong("t")) / 60000.0
                val pt = p.optDouble("batt_c")
                if (!pt.isNaN() && dt > 0 && dt < 3 * Session.interval(ctx) / 60000.0 && key(p) == k) {
                    a.dTemp += t - pt; a.dMin += dt
                }
            }
        }
        return m.values.toList()
    }

    // ----------------------------------------------------------------- eventos de uso
    class AppUse { var fgMs = 0L; var fgsMs = 0L; var fgsStarts = 0 }

    fun usageInWindow(start: Long, end: Long): Map<String, AppUse> {
        val out = HashMap<String, AppUse>()
        val openFg = HashMap<String, Long>()
        val openFgs = HashMap<String, Long>()
        try {
            val ev = usm.queryEvents(start, end)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                val p = e.packageName
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> openFg.putIfAbsent(p, e.timeStamp)
                    UsageEvents.Event.ACTIVITY_PAUSED -> openFg.remove(p)?.let {
                        out.getOrPut(p) { AppUse() }.fgMs += e.timeStamp - it
                    }
                    UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                        openFgs.putIfAbsent("$p/${e.className}", e.timeStamp)
                        out.getOrPut(p) { AppUse() }.fgsStarts++
                    }
                    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> openFgs.remove("$p/${e.className}")?.let {
                        out.getOrPut(p) { AppUse() }.fgsMs += e.timeStamp - it
                    }
                }
            }
        } catch (_: Exception) {
        }
        openFg.forEach { (p, t) -> out.getOrPut(p) { AppUse() }.fgMs += end - t }
        openFgs.forEach { (k, t) -> out.getOrPut(k.substringBefore('/')) { AppUse() }.fgsMs += end - t }
        // servicos iniciados antes do scan e ainda rodando nao geram START na janela:
        // usa o agregado do dia como complemento (ver today_usage no relatorio).
        return out
    }

    // ----------------------------------------------------------------- rede por app
    class Net { var fgBytes = 0L; var bgBytes = 0L; val total get() = fgBytes + bgBytes }

    @Suppress("DEPRECATION") // TYPE_WIFI/TYPE_MOBILE ainda sao o parametro aceito por querySummary
    fun networkByApp(start: Long, end: Long): Map<String, Net> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val out = HashMap<String, Net>()
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val stats = nsm.querySummary(type, null, start, end)
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val n = out.getOrPut(uidName(b.uid)) { Net() }
                    val bytes = b.rxBytes + b.txBytes
                    if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) n.fgBytes += bytes else n.bgBytes += bytes
                }
                stats.close()
            } catch (_: Exception) {
            }
        }
        return out
    }

    // ----------------------------------------------------------------- relatorio
    fun build(): JSONObject {
        val samples = Session.samples(ctx)
        val start = Session.startTime(ctx)
        val end = Session.endTime(ctx)
        val r = JSONObject()
        r.put("app", "CalorScan 1.2")
        r.put("generated", iso(System.currentTimeMillis()))
        r.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("device", Build.DEVICE)
            put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
            put("build", Build.DISPLAY)
            if (Build.VERSION.SDK_INT >= 31) { put("soc", Build.SOC_MODEL); put("soc_maker", Build.SOC_MANUFACTURER) }
            put("cores", Runtime.getRuntime().availableProcessors())
        })
        r.put("settings", settingsSnapshot())
        r.put("session", JSONObject().apply {
            put("start", iso(start)); put("end", iso(end))
            put("minutes", ((end - start) / 60000.0).roundToInt())
            put("interval_s", Session.interval(ctx) / 1000); put("samples", samples.size)
            put("started_at_boot", Session.isBoot(ctx))
            put("uptime_at_start_s", Session.uptimeAtStart(ctx))
        })
        r.put("summary_text", summary(samples, start, end))

        r.put("heat_by_app", JSONArray().apply {
            heatByApp(samples).sortedByDescending { it.avg }.forEach {
                put(JSONObject().apply {
                    put("app", it.key); put("label", label(it.key.substringBefore(" + ")))
                    put("samples", it.samples); put("avg_c", r1(it.avg)); put("max_c", it.tempMax)
                    if (!it.rate.isNaN()) put("c_per_10min", r1(it.rate))
                })
            }
        })
        r.put("usage_in_window", JSONArray().apply {
            usageInWindow(start, end).entries.sortedByDescending { it.value.fgMs + it.value.fgsMs }.take(40)
                .forEach { (p, u) ->
                    put(JSONObject().apply {
                        put("pkg", p); put("label", label(p))
                        put("screen_min", r1(u.fgMs / 60000.0))
                        put("fg_service_min", r1(u.fgsMs / 60000.0)); put("fg_service_starts", u.fgsStarts)
                    })
                }
        })
        r.put("network_in_window", JSONArray().apply {
            networkByApp(start, end).entries.sortedByDescending { it.value.total }.take(25).forEach { (n, v) ->
                put(JSONObject().apply {
                    put("app", n); put("fg_mb", r1(v.fgBytes / 1e6)); put("bg_mb", r1(v.bgBytes / 1e6))
                })
            }
        })
        r.put("today_usage", todayUsage())
        r.put("samsung_ai_suspects", JSONArray(suspects(start, end)))
        r.put("samples", JSONArray(samples))
        return r
    }

    /** Apps da Samsung e de IA que costumam aquecer o Galaxy S24. */
    fun suspects(start: Long, end: Long): List<JSONObject> {
        val use = usageInWindow(start, end)
        val net = networkByApp(start, end)
        val today = todayUsage()
        val todayMap = (0 until today.length()).associate {
            today.getJSONObject(it).let { o -> o.getString("pkg") to o.getDouble("fg_service_min") }
        }
        val pkgs = (use.keys + todayMap.keys + net.keys.flatMap { it.split(",") }).filter(::isSuspect).toSet()
        return pkgs.map { p ->
            val u = use[p]
            val bg = net.entries.filter { p in it.key.split(",") }.sumOf { it.value.bgBytes }
            JSONObject().apply {
                put("pkg", p); put("label", label(p))
                put("screen_min", r1((u?.fgMs ?: 0) / 60000.0))
                put("fg_service_min", r1((u?.fgsMs ?: 0) / 60000.0))
                put("bg_mb", r1(bg / 1e6))
                put("today_fg_service_min", todayMap[p] ?: 0.0)
            }
        }.filter {
            it.getDouble("screen_min") + it.getDouble("fg_service_min") + it.getDouble("bg_mb") +
                it.getDouble("today_fg_service_min") > 0
        }.sortedByDescending { it.getDouble("fg_service_min") + it.getDouble("bg_mb") }
    }

    private fun todayUsage(): JSONArray {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val arr = JSONArray()
        try {
            usm.queryAndAggregateUsageStats(c.timeInMillis, System.currentTimeMillis()).values
                .sortedByDescending { it.totalTimeInForeground + it.totalTimeForegroundServiceUsed }
                .take(60).forEach {
                    arr.put(JSONObject().apply {
                        put("pkg", it.packageName); put("label", label(it.packageName))
                        put("screen_min", r1(it.totalTimeInForeground / 60000.0))
                        put("fg_service_min", r1(it.totalTimeForegroundServiceUsed / 60000.0))
                    })
                }
        } catch (_: Exception) {
        }
        return arr
    }

    private fun settingsSnapshot(): JSONObject {
        val o = JSONObject()
        val cr = ctx.contentResolver
        fun sys(k: String) = runCatching { Settings.System.getString(cr, k) }.getOrNull()
        fun glob(k: String) = runCatching { Settings.Global.getString(cr, k) }.getOrNull()
        fun sec(k: String) = runCatching { Settings.Secure.getString(cr, k) }.getOrNull()
        o.put("brightness_mode_auto", sys(Settings.System.SCREEN_BRIGHTNESS_MODE))
        o.put("screen_off_timeout_ms", sys(Settings.System.SCREEN_OFF_TIMEOUT))
        o.put("refresh_rate_mode_samsung", sys("refresh_rate_mode") ?: sec("refresh_rate_mode"))
        o.put("protect_battery_samsung", glob("protect_battery"))
        o.put("wifi_scan_always", glob("wifi_scan_always_enabled"))
        o.put("ble_scan_always", glob("ble_scan_always_enabled"))
        o.put("mobile_data", glob("mobile_data"))
        o.put("airplane", glob(Settings.Global.AIRPLANE_MODE_ON))
        return o
    }

    // ----------------------------------------------------------------- resumo legivel
    fun summary(samples: List<JSONObject>, start: Long, end: Long): String {
        if (samples.isEmpty()) return "Nenhuma amostra coletada ainda."
        val sb = StringBuilder()
        val temps = samples.map { it.optDouble("batt_c") }.filter { !it.isNaN() }
        val minutes = (end - start) / 60000.0
        sb.appendLine("CALOR SCAN · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        sb.appendLine("Período: ${hm(start)}–${hm(end)} (${minutes.roundToInt()} min, ${samples.size} amostras)")
        if (Session.isBoot(ctx)) {
            sb.appendLine("Iniciado automaticamente ao ligar (${Session.uptimeAtStart(ctx)} s após ligar)")
            val first = samples.firstOrNull { !it.optDouble("batt_c").isNaN() }
            val peak = samples.maxByOrNull { it.optDouble("batt_c", 0.0) }
            if (first != null && peak != null) {
                sb.appendLine("Ao ligar: ${first.optDouble("batt_c")} °C → pico ${peak.optDouble("batt_c")} °C " +
                    "aos ${peak.optLong("uptime_s") / 60} min ligado")
            }
        }
        if (temps.isNotEmpty()) {
            sb.appendLine("Bateria: mín ${temps.min()} °C · média ${r1(temps.average())} °C · máx ${temps.max()} °C")
            val per = minutes / samples.size
            val above40 = temps.count { it >= 40 } * per
            val above43 = temps.count { it >= 43 } * per
            sb.appendLine("Tempo ≥40 °C: ${above40.roundToInt()} min · ≥43 °C: ${above43.roundToInt()} min")
        }
        val worst = samples.maxOf { it.optInt("thermal_status") }
        sb.appendLine("Pior estado térmico do sistema: ${thermalName(worst)}")
        val lv = samples.mapNotNull { if (it.isNull("level")) null else it.optInt("level") }
        val unplugged = samples.filter { it.optString("plugged") == "none" }
        if (lv.size >= 2 && minutes > 5 && unplugged.size == samples.size) {
            sb.appendLine("Consumo: ${r1((lv.first() - lv.last()) / minutes * 60)} %/hora")
        }
        val charging = samples.size - unplugged.size
        if (charging > 0) sb.appendLine("Carregando em $charging de ${samples.size} amostras")

        sb.appendLine()
        sb.appendLine("TEMPERATURA POR SITUAÇÃO (ordem: mais quente primeiro)")
        heatByApp(samples).filter { it.samples >= 2 }.sortedByDescending { it.avg }.take(12).forEach {
            val name = it.key.split(" + ").let { p -> label(p[0]) + if (p.size > 1) " + carregando" else "" }
            val rate = if (it.rate.isNaN()) "" else " · ${if (it.rate >= 0) "+" else ""}${r1(it.rate)} °C/10min"
            val min = (it.samples * minutes / samples.size).roundToInt()
            sb.appendLine("• $name: média ${r1(it.avg)} °C, máx ${it.tempMax} °C ($min min)$rate")
        }

        val use = usageInWindow(start, end)
        val bgWork = use.entries.filter { it.value.fgsMs > 60_000 }.sortedByDescending { it.value.fgsMs }.take(8)
        if (bgWork.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("APPS TRABALHANDO EM SEGUNDO PLANO (serviço ativo)")
            bgWork.forEach { (p, u) -> sb.appendLine("• ${label(p)}: ${(u.fgsMs / 60000.0).roundToInt()} min") }
        }

        val net = networkByApp(start, end).entries.filter { it.value.bgBytes > 5_000_000 }
            .sortedByDescending { it.value.bgBytes }.take(6)
        if (net.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("INTERNET EM SEGUNDO PLANO (aprox.)")
            net.forEach { (n, v) ->
                val nm = n.split(",").joinToString(", ") { label(it) }
                sb.appendLine("• $nm: ${r1(v.bgBytes / 1e6)} MB")
            }
        }

        sb.appendLine()
        sb.appendLine("SUSPEITOS: SAMSUNG / IA")
        val sus = suspects(start, end)
        if (sus.isEmpty()) {
            sb.appendLine("• Nenhum apareceu como ativo. Obs.: serviços internos podem rodar sem")
            sb.appendLine("  aparecer para apps comuns; o modo ADB confirma pelo uso de CPU.")
        }
        sus.forEach { sb.appendLine("• ${it.getString("label")}: tela ${it.getDouble("screen_min")} min, " +
            "2º plano ${it.getDouble("fg_service_min")} min, internet 2º plano ${it.getDouble("bg_mb")} MB, " +
            "hoje: ${it.getDouble("today_fg_service_min")} min em 2º plano") }

        sb.appendLine()
        sb.appendLine("OUTROS FATORES (% das amostras)")
        fun pct(f: (JSONObject) -> Boolean) = samples.count(f) * 100 / samples.size
        val on = samples.filter { it.optBoolean("screen_on") }
        sb.appendLine("• Tela ligada: ${pct { it.optBoolean("screen_on") }}%")
        if (on.isNotEmpty()) {
            val hz = on.mapNotNull { it.optDouble("refresh_hz").takeIf { h -> !h.isNaN() } }
            if (hz.isNotEmpty()) sb.appendLine("• Taxa de atualização média com tela ligada: ${hz.average().roundToInt()} Hz")
            val br = on.mapNotNull { if (it.has("brightness")) it.optInt("brightness") else null }
            if (br.isNotEmpty()) sb.appendLine("• Brilho médio: ${br.average().roundToInt()} (escala 0–255)")
        }
        sb.appendLine("• Câmera em uso: ${pct { it.optBoolean("camera_in_use") }}%")
        sb.appendLine("• Áudio tocando: ${pct { it.optBoolean("audio_active") }}%")
        sb.appendLine("• Localização ligada: ${pct { it.optBoolean("location_on") }}%")
        sb.appendLine("• Dados móveis em uso (>1 MB por amostra): ${pct { it.optLong("mobile_kb") > 1024 }}%")
        sb.appendLine("• Economia de energia ligada: ${pct { it.optBoolean("power_save") }}%")
        return sb.toString()
    }

    // ----------------------------------------------------------------- exportacao
    /** Salva em Downloads/CalorScan e devolve o Uri para compartilhar. */
    fun export(): Uri? {
        val json = build().toString(1)
        val name = "calorscan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CalorScan")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        return uri
    }

    companion object {
        private val SUSPECT_EXACT = setOf(
            "com.samsung.android.scs",                  // Samsung Core Services (busca/IA)
            "com.samsung.android.smartsuggestions",     // Sugestoes inteligentes
            "com.samsung.android.offline.languagemodel",// modelos do Galaxy AI
            "com.samsung.android.intellivoiceservice",  // traducao/voz do Galaxy AI
            "com.samsung.android.visionintelligence",   // Bixby Vision
            "com.samsung.android.app.routines",         // Modos e Rotinas
            "com.samsung.android.rubin.app",            // Customization Service
            "com.samsung.android.mobileservice",        // Samsung Experience Service
            "com.samsung.cmh",                          // analise de fotos da Galeria
            "com.samsung.mediasearch",
            "com.samsung.android.app.aodservice",       // Always On Display
            "com.google.android.aicore",                // Gemini Nano
            "com.google.android.as",                    // Android System Intelligence
            "com.google.android.apps.bard",             // Gemini
            "com.google.android.googlequicksearchbox",  // Google/Assistente
        )
        fun isSuspect(p: String) = p in SUSPECT_EXACT || "bixby" in p.lowercase()

        fun r1(v: Double) = (v * 10).roundToInt() / 10.0
        fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(t))
        fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
        fun thermalName(s: Int) = when (s) {
            0 -> "normal"; 1 -> "leve"; 2 -> "moderado (já reduz desempenho)"; 3 -> "severo"
            4 -> "crítico"; 5 -> "emergência"; 6 -> "desligamento"; else -> "?"
        }
    }
}
