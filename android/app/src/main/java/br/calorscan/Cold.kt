package br.calorscan

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Ajustes que esfriam o celular: o que o usuario quer x o que ja esta aplicado. */
object Cold {
    val SAMSUNG = listOf(
        "com.samsung.android.bixby.wakeup" to "\"Oi, Bixby\" (escuta de voz)",
        "com.samsung.android.smartsuggestions" to "Sugestões inteligentes",
    )
    private val SUGGEST = Regex(
        "(^br\\.gov|caixa|fgts|bank|banco|itau|bradesco|santander|nu\\.production|intermedium|picpay|meliuz|" +
            "ifood|shopee|mercadolib|amazon\\.mShop|magazineluiza|americanas|aliexpress|temu|shein|" +
            "instagram\\.barcelona|facebook\\.katana|tiktok|musically|kwai|pinterest|linkedin|rappi|99app|ubercab)",
        RegexOption.IGNORE_CASE)
    private val DEFAULT_EXCEPTIONS = setOf("com.whatsapp", "org.telegram.messenger", "com.whatsapp.w4b")
    private const val SCAN_KEYS = "wifi_scan_always_enabled|ble_scan_always_enabled"

    fun wanted(ctx: Context) = Store.getSet(ctx, "want_restrict")
    fun setWanted(ctx: Context, v: Set<String>) = Store.putSet(ctx, "want_restrict", v)
    fun restricted(ctx: Context) = Store.getSet(ctx, "applied_restrict")
    fun exceptions(ctx: Context): Set<String> =
        if (Store.getBool(ctx, "exc_init")) Store.getSet(ctx, "exceptions") else DEFAULT_EXCEPTIONS
    fun setExceptions(ctx: Context, v: Set<String>) {
        Store.putSet(ctx, "exceptions", v); Store.putBool(ctx, "exc_init", true)
    }
    fun wantOff(ctx: Context) = Store.getSet(ctx, "want_off")
    fun setWantOff(ctx: Context, v: Set<String>) = Store.putSet(ctx, "want_off", v)
    fun wantScanOff(ctx: Context) = Store.getBool(ctx, "want_scan_off")

    fun pkgEnabled(ctx: Context, p: String): Boolean = runCatching {
        val s = ctx.packageManager.getApplicationEnabledSetting(p)
        s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false) && installed(ctx, p)

    fun installed(ctx: Context, p: String) = runCatching { ctx.packageManager.getApplicationInfo(p, 0); true }.getOrDefault(false)

    fun scanOn(ctx: Context) = SCAN_KEYS.split("|").any {
        Settings.Global.getInt(ctx.contentResolver, it, 0) == 1
    }

    // ------------------------------------------------------------- lista de apps
    data class AppItem(val pkg: String, val label: String, val reason: String?, val suggested: Boolean)

    fun candidates(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val bg = Diagnosis.backgroundSinceBoot(ctx)
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launch, 0).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
            .map { ai ->
                val p = ai.packageName
                val reason = when {
                    bg.containsKey(p) -> "rodou em 2º plano desde que ligou"
                    SUGGEST.containsMatchIn(p) -> "banco, governo, compras ou rede social"
                    else -> null
                }
                AppItem(p, pm.getApplicationLabel(ai).toString(), reason, reason != null && p !in exceptions(ctx))
            }
            .sortedWith(compareByDescending<AppItem> { it.reason != null }.thenBy { it.label.lowercase() })
    }

    // ------------------------------------------------------------- permissoes
    data class Grants(val secure: Boolean, val quiet: Boolean, val usage: Boolean, val alarm: Boolean,
                      val battery: Boolean, val notif: Boolean, val dump: Boolean = false) {
        val all get() = secure && quiet && usage && alarm && battery && notif && dump
    }

    fun grants(ctx: Context): Grants {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        return Grants(
            has(Manifest.permission.WRITE_SECURE_SETTINGS), has("android.permission.MODIFY_QUIET_MODE"), usage,
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS), Live.PERMS.all { has(it) })
    }

    // ------------------------------------------------------------- plano de comandos
    data class Plan(val id: String, val commands: List<String>)

    fun plan(ctx: Context): Plan {
        val me = ctx.packageName
        val g = grants(ctx)
        val c = mutableListOf<String>()
        if (!g.secure) c += "pm grant $me android.permission.WRITE_SECURE_SETTINGS"
        if (!g.quiet) c += "pm grant $me android.permission.MODIFY_QUIET_MODE"
        if (!g.notif) c += "pm grant $me android.permission.POST_NOTIFICATIONS"
        // ver quem usa o processador (painel e ranking)
        Live.PERMS.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.forEach { c += "pm grant $me $it" }
        if (!g.usage) c += "appops set $me GET_USAGE_STATS allow"
        if (!g.alarm) c += "appops set $me SCHEDULE_EXACT_ALARM allow"
        if (!g.battery) c += "dumpsys deviceidle whitelist +$me"
        val want = wanted(ctx).filter { installed(ctx, it) }.toSet()
        val applied = restricted(ctx)
        for (p in want - applied) {
            c += "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore"; c += "am set-standby-bucket $p restricted"
        }
        for (p in applied - want) {
            c += "cmd appops set $p RUN_ANY_IN_BACKGROUND allow"; c += "am set-standby-bucket $p active"
        }
        for ((p, _) in SAMSUNG) {
            if (!installed(ctx, p)) continue
            val off = p in wantOff(ctx)
            if (off && pkgEnabled(ctx, p)) c += "pm disable-user --user 0 $p"
            if (!off && !pkgEnabled(ctx, p)) c += "pm enable $p"
        }
        if (!g.secure && scanTouched(ctx) && wantScanOff(ctx) == scanOn(ctx)) {
            val v = if (wantScanOff(ctx)) 0 else 1
            c += "settings put global wifi_scan_always_enabled $v"; c += "settings put global ble_scan_always_enabled $v"
        }
        val id = Integer.toHexString((c.joinToString() + want.sorted()).hashCode())
        Store.putStr(ctx, "plan_$id", JSONArray(want.toList()).toString())
        return Plan(id, c)
    }

    /** Chamado quando o plano foi executado (pelo Shizuku ou pelo notebook). */
    fun markApplied(ctx: Context, id: String?) {
        val snap = id?.let { Store.getStr(ctx, "plan_$it") } ?: JSONArray(wanted(ctx).toList()).toString()
        val arr = JSONArray(snap)
        Store.putSet(ctx, "applied_restrict", (0 until arr.length()).map { arr.getString(it) }.toSet())
        applyDirect(ctx)
    }

    /** O que da para fazer sem ajuda (depois de liberado): busca de Wi-Fi/Bluetooth. */
    fun applyDirect(ctx: Context): Boolean {
        if (!scanTouched(ctx)) return false // so mexe se o usuario mudou essa opcao
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        val v = if (wantScanOff(ctx)) 0 else 1
        SCAN_KEYS.split("|").forEach { Settings.Global.putInt(ctx.contentResolver, it, v) }
        return true
    }

    fun scanTouched(ctx: Context) = Store.getBool(ctx, "scan_touched")

    fun setWantScanOff(ctx: Context, v: Boolean) {
        if (!scanTouched(ctx)) { // guarda como estava para o "Desfazer tudo"
            Store.putBool(ctx, "scan_orig_on", scanOn(ctx)); Store.putBool(ctx, "scan_touched", true)
        }
        Store.putBool(ctx, "want_scan_off", v); applyDirect(ctx)
    }

    /** Volta a busca de Wi-Fi/Bluetooth para como estava antes do app. */
    fun undoScan(ctx: Context) {
        if (scanTouched(ctx)) setWantScanOff(ctx, !Store.getBool(ctx, "scan_orig_on", true))
    }

    // ------------------------------------------------------------- Shizuku
    fun shizukuRunning() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun shizukuAllowed() = shizukuRunning() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun sh(cmd: String): Int {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        p.inputStream.readBytes(); p.errorStream.readBytes()
        return p.waitFor()
    }

    /** Executa o plano pelo Shizuku. Retorna mensagem para o usuario. */
    fun applyWithShizuku(ctx: Context): String {
        val plan = plan(ctx)
        if (plan.commands.isEmpty()) return "Nada para aplicar: está tudo em dia ✓"
        val failedPkgs = mutableSetOf<String>()
        var fails = 0
        plan.commands.forEach { c ->
            if (runCatching { sh(c) }.getOrDefault(1) != 0) {
                fails++
                Regex("^(?:cmd appops set|am set-standby-bucket) (\\S+)").find(c)?.let { failedPkgs += it.groupValues[1] }
            }
        }
        markApplied(ctx, plan.id)
        if (failedPkgs.isNotEmpty()) { // nao marca como "restrito" o que nao funcionou: fica pendente para tentar de novo
            val wantNow = wanted(ctx)
            val applied = restricted(ctx).toMutableSet()
            failedPkgs.forEach { p -> if (p in wantNow) applied -= p else applied += p }
            Store.putSet(ctx, "applied_restrict", applied)
        }
        return if (fails == 0) "Pronto! ${plan.commands.size} ajustes aplicados ✓" else "Aplicado, mas $fails comando(s) falharam."
    }
}

/** Conversa com o programa do notebook (so o shell do ADB pode chamar: permissao DUMP). */
class BridgeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "br.calorscan.PLAN" -> {
                val p = Cold.plan(ctx)
                val json = JSONObject().put("id", p.id).put("commands", JSONArray(p.commands)).toString()
                resultData = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
            }
            "br.calorscan.APPLIED" -> {
                Cold.markApplied(ctx, intent.getStringExtra("id"))
                Work.reschedule(ctx)
                GuardService.start(ctx)
            }
        }
    }
}
