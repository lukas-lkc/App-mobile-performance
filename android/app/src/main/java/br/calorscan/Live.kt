package br.calorscan

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Painel ao vivo do proprio celular.
 *
 * Bateria, consumo, memoria e estado termico qualquer app le. Quem esta usando o processador, nao.
 * Com a permissao de leitura do sistema (DUMP, liberada 1 vez pelo notebook ou Shizuku) o app le o
 * "dumpsys batterystats --checkin": o tempo de processador ACUMULADO de cada app (uid). Duas leituras
 * seguidas dizem quanto cada app usou no intervalo. (O "dumpsys cpuinfo" e bloqueado para apps pelo SELinux.)
 */
object Live {
    data class Proc(val key: String, val label: String, val kind: String, val pct: Double)

    data class Snap(
        val temp: Double?, val level: Int?, val charging: Boolean, val ma: Int?, val watts: Double?,
        val memPct: Int, val memUsed: Long, val memTotal: Long, val thermal: Int, val headroom: Float?,
        val cpuTotal: Double?, val procs: List<Proc>?, val sensors: Map<String, Double>,
    )

    val PERMS = listOf("android.permission.DUMP", "android.permission.INTERACT_ACROSS_USERS")

    fun canSeeApps(ctx: Context) = PERMS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // leitura anterior do painel (para calcular o uso no intervalo)
    @Volatile private var prev: Pair<Long, Map<Int, Long>>? = null
    @Volatile private var lastApps: Triple<Long, Double?, List<Proc>?>? = null
    @Volatile private var lastSensors: Map<String, Double> = emptyMap()
    private const val APPS_EVERY_MS = 15_000L
    @Volatile private var sensorsAt = 0L

    // ------------------------------------------------------------------ leitura
    fun snap(ctx: Context): Snap {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val level = b?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) l * 100 / s else null
        }
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val mv = b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val raw = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // o Android pede microampere, mas varios aparelhos (Samsung) devolvem miliampere
        val ma = if (raw == Int.MIN_VALUE || raw == 0) null else if (kotlin.math.abs(raw) > 20_000) raw / 1000 else raw
        val watts = if (ma != null && mv > 0) kotlin.math.abs(ma) / 1000.0 * mv / 1000.0 else null

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = mi.totalMem - mi.availMem
        val pm = ctx.getSystemService(PowerManager::class.java)
        val head = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() } else null

        var cpu: Double? = null
        var procs: List<Proc>? = null
        var sensors = emptyMap<String, Double>()
        val now0 = SystemClock.elapsedRealtime()
        val cached = lastApps
        if (canSeeApps(ctx) && charging) {
            // no carregador o Android NAO conta o uso de cada app (as estatisticas so andam na bateria):
            // nem pede (economiza processador) e avisa na tela
            prev = null; lastApps = null
            if (now0 - sensorsAt > APPS_EVERY_MS) { lastSensors = thermalSensors(); sensorsAt = now0 }
            sensors = lastSensors
        } else if (canSeeApps(ctx) && cached != null && now0 - cached.first < APPS_EVERY_MS) {
            // ler o batterystats custa processador: no maximo a cada 15 s (o resto do painel segue a cada 3 s)
            cpu = cached.second; procs = cached.third; sensors = lastSensors
        } else if (canSeeApps(ctx)) {
            val now = now0
            uidCpu()?.let { cur ->
                val p = prev
                prev = now to cur
                if (p != null && now - p.first >= 1500) {
                    val ncpu = Runtime.getRuntime().availableProcessors()
                    val win = (now - p.first).toDouble() * ncpu
                    val d = delta(p.second, cur)
                    cpu = (d.values.sum() / win * 100).coerceIn(0.0, 100.0)
                    procs = d.entries.filter { it.value > 0 }
                        .map { (uid, ms) -> describe(ctx, uid).let { (k, l, kind) -> Proc(k, l, kind, ms / win * 100) } }
                        .groupBy { it.key }.map { (_, l) -> l.first().copy(pct = l.sumOf { it.pct }) }
                        .sortedByDescending { it.pct }
                } else procs = emptyList()
            }
            sensors = thermalSensors()
            lastSensors = sensors
            if (procs != null) lastApps = Triple(now, cpu, procs)  // guarda ate vazio: nao repete a leitura a cada 3 s
        }
        return Snap(temp, level, charging, ma, watts, (used * 100 / mi.totalMem).toInt(), used, mi.totalMem,
            pm.currentThermalStatus, head, cpu, procs, sensors)
    }

    /** Tempo de processador acumulado (ms) de cada uid, do "dumpsys batterystats --checkin". */
    fun uidCpu(): Map<Int, Long>? = runCatching {
        val out = run("batterystats", "--checkin") ?: return null
        val m = HashMap<Int, Long>()
        out.lineSequence().forEach { l ->
            if (!l.contains(",l,cpu,")) return@forEach
            val p = l.split(',')
            // 9,<uid>,l,cpu,<usuario ms>,<sistema ms>,...
            if (p.size >= 6 && p[2] == "l" && p[3] == "cpu") {
                val uid = p[1].toIntOrNull() ?: return@forEach
                m[uid] = (m[uid] ?: 0) + (p[4].toLongOrNull() ?: 0) + (p[5].toLongOrNull() ?: 0)
            }
        }
        m.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Diferenca entre duas leituras (se as estatisticas zeraram no meio, ignora o uid). */
    fun delta(old: Map<Int, Long>, cur: Map<Int, Long>): Map<Int, Long> =
        cur.mapNotNull { (uid, ms) -> val o = old[uid] ?: 0; if (ms >= o) uid to (ms - o) else null }.toMap()

    /** Temperaturas dos sensores (chip, carcaca...) pelo "dumpsys thermalservice". */
    fun thermalSensors(): Map<String, Double> = runCatching {
        val out = run("thermalservice") ?: return emptyMap()
        val part = out.substringAfter("Current temperatures from HAL", "").substringBefore("Current cooling devices")
        Regex("""mValue=([-\d.]+), mType=(\d+), mName=([^,]+)""").findAll(part).mapNotNull { m ->
            val v = m.groupValues[1].toDouble()
            val name = when (m.groupValues[2]) { "0" -> "Processador"; "1" -> "Vídeo"; "3" -> "Carcaça"; else -> null }
            if (name != null && v > -20 && v < 150) name to v else null
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
    }.getOrDefault(emptyMap())

    private fun run(vararg args: String): String? {
        val p = ProcessBuilder("/system/bin/dumpsys", *args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroy(); return null }
        val head = text.take(300)
        return if (head.contains("Permission Denial") || head.contains("Security exception") ||
            head.startsWith("Can't find service")) null else text
    }

    // ------------------------------------------------------------------ nomes
    private val SYSTEM_UIDS = mapOf(
        0 to "Núcleo do Android (root)", 1000 to "Sistema Android", 1001 to "Telefone e rede móvel", 1002 to "Bluetooth",
        1010 to "Wi-Fi", 1013 to "Mídia", 1021 to "GPS", 1027 to "NFC", 1036 to "Registro do sistema", 1041 to "Áudio",
        1046 to "Mídia", 1047 to "Câmera (sistema)", 1068 to "Rede", 1072 to "Rede", 2000 to "ADB (cabo)",
    )
    private val cache = HashMap<Int, Triple<String, String, String>>()

    /** (chave, nome bonito, tipo) de um uid. Apps de outros perfis ganham a etiqueta do perfil. */
    fun describe(ctx: Context, uid: Int): Triple<String, String, String> = cache.getOrPut(uid) {
        val user = uid / 100_000
        val appId = uid % 100_000
        val suffix = when { user == 0 -> ""; user >= 150 -> " · Pasta Segura"; else -> " · trabalho" }
        if (appId < 10_000) {
            val name = SYSTEM_UIDS[appId] ?: "Sistema Android"
            return@getOrPut Triple("sys:$name$suffix", name + suffix, "sistema")
        }
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: pm.getPackagesForUid(appId) ?: emptyArray()
        val pkg = pkgs.firstOrNull() ?: return@getOrPut Triple("uid:$appId$suffix", "App $appId$suffix", "app")
        Triple("$pkg$suffix", Diagnosis.label(ctx, pkg) + suffix, kind(ctx, pkg))
    }

    fun kind(ctx: Context, pkg: String): String = when {
        pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.") -> "samsung"
        pkg.startsWith("com.google.") || pkg == "com.android.vending" -> "google"
        !pkg.contains('.') || pkg.startsWith("com.android.") || pkg.startsWith("android") -> "sistema"
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .getOrDefault(false) -> "app"
        else -> "sistema"
    }
}

/**
 * Ranking de quem mais usou o processador: desde que o monitor iniciou depois de ligar (15 min) e no dia.
 * Alimentado pelo monitor (GuardService). Guarda segundos de processador por app (chave = pacote + perfil).
 */
object CpuRank {
    private const val BOOT_WINDOW_S = 15 * 60

    fun sample(ctx: Context) {
        if (!Live.canSeeApps(ctx)) return
        // no carregador as estatisticas nao andam; ao tirar do cabo elas zeram (a comparacao abaixo ignora a queda)
        if (charging(ctx)) return
        val cur = Live.uidCpu() ?: return
        val p = Store.prefs(ctx)
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val prev = p.getString("rank_prev", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        // so compara com a leitura anterior do MESMO boot
        val sameBoot = prev != null && kotlin.math.abs(prev.optLong("boot") - bootAt) < 60_000
        if (sameBoot) {
            val o = prev!!.getJSONObject("u")
            val old = o.keys().asSequence().associate { it.toInt() to o.getLong(it) }
            val add = HashMap<String, Double>()
            Live.delta(old, cur).forEach { (uid, ms) ->
                if (ms <= 0) return@forEach
                val (key, _, _) = Live.describe(ctx, uid)
                add[key] = (add[key] ?: 0.0) + ms / 1000.0
            }
            if (SystemClock.elapsedRealtime() / 1000 <= BOOT_WINDOW_S + 120) merge(ctx, "rank_boot", bootAt.toString(), add, true)
            merge(ctx, "rank_day", today(), add, false)
        }
        val u = JSONObject(); cur.forEach { (k, v) -> u.put(k.toString(), v) }
        p.edit().putString("rank_prev", JSONObject().put("boot", bootAt).put("u", u).toString()).apply()
    }

    private fun charging(ctx: Context) = (ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun today() = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }

    private fun merge(ctx: Context, key: String, id: String, add: Map<String, Double>, bootKey: Boolean) {
        val p = Store.prefs(ctx)
        val old = p.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val same = old != null && (old.optString("id") == id ||
            (bootKey && kotlin.math.abs((old.optString("id").toLongOrNull() ?: 0) - id.toLong()) < 60_000))
        val map = if (same) old!!.getJSONObject("s") else JSONObject()
        add.forEach { (k, v) -> map.put(k, map.optDouble(k, 0.0) + v) }
        val keep = map.keys().asSequence().map { it to map.getDouble(it) }.sortedByDescending { it.second }.take(40)
        val m2 = JSONObject().apply { keep.forEach { (k, v) -> put(k, v) } }
        p.edit().putString(key, JSONObject().put("id", if (same) old!!.getString("id") else id).put("s", m2).toString()).apply()
    }

    /** (chave do app, segundos) do boot atual ou de hoje. */
    fun top(ctx: Context, boot: Boolean, n: Int = 8): List<Pair<String, Double>> {
        val o = Store.getStr(ctx, if (boot) "rank_boot" else "rank_day")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        if (boot) {
            val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (kotlin.math.abs((o.optString("id").toLongOrNull() ?: 0) - bootAt) > 60_000) return emptyList()
        } else if (o.optString("id") != today()) return emptyList()
        val s = o.getJSONObject("s")
        return s.keys().asSequence().map { it to s.getDouble(it) }.filter { it.second >= 0.5 }
            .sortedByDescending { it.second }.take(n).toList()
    }

    /** Nome bonito e tipo de uma chave do ranking ("com.app", "com.app · trabalho", "sys:Sistema Android"). */
    fun describeKey(ctx: Context, key: String): Pair<String, String> {
        if (key.startsWith("sys:")) return key.removePrefix("sys:") to "sistema"
        if (key.startsWith("uid:")) return "App ${key.removePrefix("uid:")}" to "app"
        val pkg = key.substringBefore(" · ")
        val suffix = key.substringAfter(" · ", "").let { if (it.isEmpty()) "" else " · $it" }
        return Diagnosis.label(ctx, pkg) + suffix to Live.kind(ctx, pkg)
    }
}
