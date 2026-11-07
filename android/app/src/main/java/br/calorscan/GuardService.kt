package br.calorscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

/**
 * Monitor leve que roda sozinho: reage a mudancas de temperatura da bateria e do
 * estado termico (eventos do sistema) e grava uma leitura a cada 10 s nos primeiros
 * 15 min depois de ligar e a cada 60 s no resto do tempo.
 */
class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pm: PowerManager
    private lateinit var usm: UsageStatsManager
    private var temp: Double? = null
    private var plugged = false
    private var lastSave = 0L
    private var lastStatus = -1
    private var lastText = ""
    private var fg: String? = null
    private var lastQuery = System.currentTimeMillis() - 3_600_000

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != Int.MIN_VALUE) temp = t / 10.0
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            maybeSave(force = false)
        }
    }
    private val thermal = PowerManager.OnThermalStatusChangedListener { maybeSave(force = true) }

    private val tick = object : Runnable {
        override fun run() {
            maybeSave(force = true)
            val boot = SystemClock.elapsedRealtime() < 15 * 60_000
            handler.postDelayed(this, if (boot) 10_000 else 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pm = getSystemService(PowerManager::class.java)
        usm = getSystemService(UsageStatsManager::class.java)
        channels(this)
        val n = notification("Iniciando…", "")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        pm.addThermalStatusListener(mainExecutor, thermal)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Store.monitorOn(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(battery) }
        pm.removeThermalStatusListener(thermal)
        super.onDestroy()
    }

    private fun updateForeground() {
        val now = System.currentTimeMillis()
        runCatching {
            val ev = usm.queryEvents(lastQuery, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fg = e.packageName
            }
            lastQuery = now
        }
    }

    private fun maybeSave(force: Boolean) {
        val t = temp ?: return
        val now = System.currentTimeMillis()
        val st = pm.currentThermalStatus
        if (!force && now - lastSave < 8_000 && st == lastStatus) { updateNotification(t, st); return }
        lastSave = now; lastStatus = st
        updateForeground()
        val screen = pm.isInteractive
        val s = Sample(now, SystemClock.elapsedRealtime() / 1000, t, st, plugged, screen, if (screen) fg else null)
        Store.append(this, s)
        updateNotification(t, st)
        checkAlert(s)
    }

    private fun updateNotification(t: Double, st: Int) {
        val text = String.format(Locale("pt", "BR"), "%.1f °C · %s", t, Diagnosis.mood(t))
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java)
            .notify(ID, notification(text, "Sistema: ${Diagnosis.thermalName(st)}${if (plugged) " · carregando" else ""}"))
    }

    private fun checkAlert(s: Sample) {
        val limit = Store.alertAt(this)
        if (s.c < limit) return
        val p = Store.prefs(this)
        if (System.currentTimeMillis() - p.getLong("last_alert", 0) < 20 * 60_000) return
        p.edit().putLong("last_alert", System.currentTimeMillis()).apply()
        val what = buildList {
            s.fg?.let { add("${Diagnosis.label(this@GuardService, it)} aberto") }
            if (s.plug) add("carregando")
            if (s.up < 600) add("celular ligou há ${s.up / 60} min")
        }.joinToString(" · ").ifEmpty { "tela desligada" }
        getSystemService(NotificationManager::class.java).notify(ID + 1,
            Notification.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(String.format(Locale("pt", "BR"), "Celular quente: %.1f °C", s.c))
                .setContentText("Agora: $what. Toque para ver o que fazer.")
                .setContentIntent(open(this)).setAutoCancel(true).build())
    }

    private fun notification(title: String, text: String): Notification =
        Notification.Builder(this, CH_LIVE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setOngoing(true)
            .setContentIntent(open(this)).build()

    companion object {
        const val ID = 11
        const val CH_LIVE = "ao_vivo"
        const val CH_ALERT = "alertas"
        const val CH_WORK = "trabalho"

        fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Temperatura ao vivo", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alertas de calor", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_WORK, "Perfil de trabalho", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun start(ctx: Context) {
            if (Store.monitorOn(ctx)) ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, GuardService::class.java))
    }
}
