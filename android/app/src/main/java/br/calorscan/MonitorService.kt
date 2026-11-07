package br.calorscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.io.File

/** Guarda as amostras da sessao atual em disco (uma linha JSON por amostra). */
object Session {
    const val INTERVAL_MS = 20_000L
    private const val PREFS = "session"

    fun file(ctx: Context) = File(ctx.filesDir, "samples.jsonl")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(ctx: Context, durationMs: Long) {
        file(ctx).delete()
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong("start", now).putLong("planned_end", now + durationMs)
            .putLong("end", 0).putBoolean("running", true).apply()
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().putLong("end", System.currentTimeMillis()).putBoolean("running", false).apply()
    }

    fun running(ctx: Context) = prefs(ctx).getBoolean("running", false)
    fun startTime(ctx: Context) = prefs(ctx).getLong("start", 0)
    fun plannedEnd(ctx: Context) = prefs(ctx).getLong("planned_end", 0)
    fun endTime(ctx: Context): Long = prefs(ctx).getLong("end", 0).takeIf { it > 0 } ?: System.currentTimeMillis()

    fun append(ctx: Context, o: JSONObject) = file(ctx).appendText(o.toString() + "\n")

    fun samples(ctx: Context): List<JSONObject> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }
}

class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sampler: Sampler
    private var count = 0
    private var cameraCb: CameraManager.AvailabilityCallback? = null

    private val tick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= Session.plannedEnd(this@MonitorService)) {
                finish(); return
            }
            try {
                val s = sampler.sample()
                Session.append(this@MonitorService, s)
                count++
                notify("${s.optDouble("batt_c")} °C · $count amostras")
            } catch (_: Exception) {
            }
            handler.postDelayed(this, Session.INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sampler = Sampler(this)
        val cm = getSystemService(CameraManager::class.java)
        cameraCb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) { sampler.camerasInUse.remove(id) }
            override fun onCameraUnavailable(id: String) { sampler.camerasInUse.add(id) }
        }
        cm.registerAvailabilityCallback(cameraCb!!, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(); return START_NOT_STICKY
        }
        val n = buildNotification("Iniciando…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!Session.running(this)) { stopSelf(); return START_NOT_STICKY }
        count = Session.samples(this).size
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        Session.stop(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID + 1,
            Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Calor Scan: monitoramento concluído")
                .setContentText("Abra o app para exportar o relatório.")
                .setContentIntent(openApp()).setAutoCancel(true).build()
        )
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        cameraCb?.let { getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(it) }
        super.onDestroy()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Monitoramento", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Calor Scan monitorando")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    companion object {
        const val CHANNEL = "monitor"
        const val NOTIF_ID = 7
        const val ACTION_STOP = "br.calorscan.STOP"
    }
}
