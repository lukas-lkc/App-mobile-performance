package br.calorscan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import org.json.JSONObject
import java.util.Calendar

/** Agenda de pausa/reativacao do perfil de trabalho. */
object Work {
    /** Dias em Calendar.DAY_OF_WEEK (1 = domingo ... 7 = sabado). */
    data class Day(val on: Boolean, val start: Int, val end: Int) // minutos do dia

    val DAY_NAMES = mapOf(2 to "Seg", 3 to "Ter", 4 to "Qua", 5 to "Qui", 6 to "Sex", 7 to "Sáb", 1 to "Dom")

    /** Todos os perfis extras (trabalho, Pasta Segura, Espaço privado...). */
    fun allProfiles(ctx: Context): List<UserHandle> {
        val um = ctx.getSystemService(UserManager::class.java)
        return um.userProfiles.filter { it != Process.myUserHandle() }
    }

    /**
     * So os perfis de TRABALHO. No Android 15+ a Pasta Segura da Samsung e o Espaco privado tambem aparecem
     * como "perfil" e o app pausava o perfil errado. O tipo vem do "dumpsys user" (com a permissao de leitura)
     * ou do Android 15 (LauncherApps); sem nenhum dos dois, descarta os numeros da Pasta Segura (150 em diante).
     */
    fun profiles(ctx: Context): List<UserHandle> {
        val all = allProfiles(ctx)
        val types = profileTypes(ctx)
        return all.filter { u ->
            val t = types[serial(ctx, u)] ?: launcherType(ctx, u)
            if (t != null) t.endsWith("profile.MANAGED") else serial(ctx, u) < 150
        }
    }

    /** A Pasta Segura da Samsung, se existir. */
    fun secureFolder(ctx: Context): UserHandle? = allProfiles(ctx).firstOrNull { profileName(ctx, it) == "Pasta Segura" }

    /** Nome amigavel de qualquer perfil. */
    fun profileName(ctx: Context, u: UserHandle): String {
        val t = profileTypes(ctx)[serial(ctx, u)] ?: launcherType(ctx, u) ?: ""
        val s = serial(ctx, u)
        return when {
            t.endsWith("profile.MANAGED") -> "Perfil de trabalho"
            s >= 150 || t.contains("SECURE", true) -> "Pasta Segura"
            t.endsWith("profile.PRIVATE") -> "Espaço privado"
            t.endsWith("profile.CLONE") -> "Apps duplicados"
            else -> "Perfil $s"
        }
    }

    private fun serial(ctx: Context, u: UserHandle) = ctx.getSystemService(UserManager::class.java).getSerialNumberForUser(u)

    private var typesCache: Pair<Long, Map<Long, String>>? = null

    /** serial do perfil -> tipo ("android.os.usertype.profile.MANAGED"...), pelo "dumpsys user". */
    private fun profileTypes(ctx: Context): Map<Long, String> {
        typesCache?.let { if (System.currentTimeMillis() - it.first < 60_000) return it.second }
        if (ctx.checkSelfPermission("android.permission.DUMP") != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyMap()
        val out = runCatching {
            val p = ProcessBuilder("/system/bin/dumpsys", "user").redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText(); p.waitFor(); t
        }.getOrDefault("")
        val map = HashMap<Long, String>()
        var cur: Long? = null
        out.lineSequence().forEach { l ->
            Regex("""UserInfo\{\d+:.*?\} serialNo=(\d+)""").find(l)?.let { cur = it.groupValues[1].toLong() }
            Regex("""^\s*Type: (android\.os\.usertype\.\S+)""").find(l)?.let { m -> cur?.let { map.putIfAbsent(it, m.groupValues[1]) } }
        }
        typesCache = System.currentTimeMillis() to map
        return map
    }

    private fun launcherType(ctx: Context, u: UserHandle): String? = if (Build.VERSION.SDK_INT >= 35) runCatching {
        ctx.getSystemService(LauncherApps::class.java).getLauncherUserInfo(u)?.userType
    }.getOrNull() else null

    fun profileApps(ctx: Context, user: UserHandle): List<String> = runCatching {
        ctx.getSystemService(LauncherApps::class.java).getActivityList(null, user).map { it.label.toString() }.distinct()
    }.getOrDefault(emptyList())

    fun isPaused(ctx: Context, user: UserHandle) = ctx.getSystemService(UserManager::class.java).isQuietModeEnabled(user)

    fun canControl(ctx: Context) = ctx.checkSelfPermission("android.permission.MODIFY_QUIET_MODE") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun selected(ctx: Context): UserHandle? {
        val list = profiles(ctx)
        if (list.isEmpty()) return null
        val um = ctx.getSystemService(UserManager::class.java)
        val serial = Store.prefs(ctx).getLong("work_serial", -1)
        return list.firstOrNull { um.getSerialNumberForUser(it) == serial } ?: list.first()
    }

    fun select(ctx: Context, user: UserHandle) = Store.prefs(ctx).edit()
        .putLong("work_serial", ctx.getSystemService(UserManager::class.java).getSerialNumberForUser(user)).apply()

    /** Pausa (true) ou liga (false). Retorna null se ok, ou o motivo da falha. */
    fun setPaused(ctx: Context, user: UserHandle, pause: Boolean, interactive: Boolean): String? = try {
        val um = ctx.getSystemService(UserManager::class.java)
        val ok = if (!pause && !interactive && Build.VERSION.SDK_INT >= 30)
            um.requestQuietModeEnabled(false, user, UserManager.QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED)
        else um.requestQuietModeEnabled(pause, user)
        if (ok || isPaused(ctx, user) == pause) null else "precisa do seu PIN"
    } catch (e: SecurityException) {
        "o app ainda não foi liberado"
    } catch (e: Exception) {
        e.message ?: "erro"
    }

    // ------------------------------------------------------------- agenda
    fun enabled(ctx: Context) = Store.getBool(ctx, "work_sched_on")
    fun setEnabled(ctx: Context, v: Boolean) {
        Store.putBool(ctx, "work_sched_on", v); reschedule(ctx)
        if (v) enforce(ctx, !shouldBeOn(ctx)) // ja aplica o horario de agora (antes esperava o proximo alarme)
    }

    fun days(ctx: Context): Map<Int, Day> {
        val o = Store.getStr(ctx, "work_days")?.let { JSONObject(it) }
        return (1..7).associateWith { d ->
            val j = o?.optJSONObject(d.toString())
            if (j != null) Day(j.getBoolean("on"), j.getInt("s"), j.getInt("e"))
            else Day(d in 2..6, 8 * 60, 20 * 60)
        }
    }

    fun setDay(ctx: Context, d: Int, day: Day) {
        val o = JSONObject()
        days(ctx).forEach { (k, v) ->
            val x = if (k == d) day else v
            o.put(k.toString(), JSONObject().put("on", x.on).put("s", x.start).put("e", x.end))
        }
        Store.putStr(ctx, "work_days", o.toString())
        reschedule(ctx)
        if (enabled(ctx)) enforce(ctx, !shouldBeOn(ctx))
    }

    /** Proximo evento: (hora em ms, pausar?) */
    fun next(ctx: Context, from: Long = System.currentTimeMillis()): Pair<Long, Boolean>? {
        val ds = days(ctx)
        var best: Pair<Long, Boolean>? = null
        for (i in 0..7) {
            val c = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.DAY_OF_YEAR, i) }
            val d = ds[c.get(Calendar.DAY_OF_WEEK)] ?: continue
            if (!d.on) continue
            for ((min, pause) in listOf(d.start to false, d.end to true)) {
                c.set(Calendar.HOUR_OF_DAY, min / 60); c.set(Calendar.MINUTE, min % 60)
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis > from + 30_000 && (best == null || c.timeInMillis < best.first)) best = c.timeInMillis to pause
            }
        }
        return best
    }

    /** Pelo horario, o perfil deveria estar ligado agora? */
    fun shouldBeOn(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val d = days(ctx)[c.get(Calendar.DAY_OF_WEEK)] ?: return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return d.on && m >= d.start && m < d.end
    }

    private fun pending(ctx: Context, pause: Boolean) = PendingIntent.getBroadcast(ctx, 7,
        Intent(ctx, WorkReceiver::class.java).putExtra("pause", pause),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, false))
        if (!enabled(ctx) || selected(ctx) == null) return
        val (t, pause) = next(ctx) ?: return
        val pi = pending(ctx, pause)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, t, 10 * 60_000, pi)
    }

    /** Aplica o estado que o horario manda (usado no boot e nos alarmes). */
    fun enforce(ctx: Context, pause: Boolean) {
        val user = selected(ctx) ?: return
        if (isPaused(ctx, user) == pause) return
        val err = setPaused(ctx, user, pause, interactive = false)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        GuardService.channels(ctx)
        val (title, text) = when {
            err == null && pause -> "Apps de trabalho pausados" to "Pelo seu horário. Voltam no próximo horário de trabalho."
            err == null -> "Apps de trabalho ligados" to "Bom expediente!"
            !pause && err == "precisa do seu PIN" -> "Toque para ligar os apps de trabalho" to "O Android pede seu PIN para isso."
            err == "o app ainda não foi liberado" -> "Hora de ${if (pause) "pausar" else "ligar"} os apps de trabalho" to
                "Puxe o painel rápido e toque em “Apps de trabalho”."
            else -> "Não consegui ${if (pause) "pausar" else "ligar"} os apps de trabalho" to "Motivo: $err. Abra o CalorScan."
        }
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java)
            .putExtra("tab", 2).putExtra("resume_work", err != null && !pause), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(30, Notification.Builder(ctx, GuardService.CH_WORK).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}

class WorkReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Work.enabled(ctx)) Work.enforce(ctx, intent.getBooleanExtra("pause", true))
        Work.reschedule(ctx)
    }
}

/** Ao ligar: inicia o monitor, reagenda o perfil e ja aplica o horario (pausar cedo alivia o boot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        GuardService.start(ctx)
        if (Work.enabled(ctx) && intent.action == Intent.ACTION_BOOT_COMPLETED) Work.enforce(ctx, !Work.shouldBeOn(ctx))
        Work.reschedule(ctx)
    }
}
