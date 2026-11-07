package br.calorscan

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Le o estado atual do aparelho. Tudo aqui funciona sem root. */
class Sampler(private val ctx: Context) {
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private val dm = ctx.getSystemService(DisplayManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)

    private var lastEventQuery = System.currentTimeMillis() - 3 * 3600_000L
    var foreground: String? = null
        private set

    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    private var lastMobile = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()

    /** Cameras em uso por qualquer app (preenchido pelo servico). */
    val camerasInUse = mutableSetOf<String>()

    fun battery(): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun batteryTempC(i: Intent? = battery()): Double? {
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: return null
        return if (t == Int.MIN_VALUE) null else t / 10.0
    }

    fun thermalStatus(): Int = pm.currentThermalStatus

    private fun updateForeground() {
        val now = System.currentTimeMillis()
        try {
            val events = usm.queryEvents(lastEventQuery, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.packageName
            }
            lastEventQuery = now
        } catch (_: Exception) {
        }
    }

    fun sample(): JSONObject {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        o.put("t", now)
        o.put("uptime_s", android.os.SystemClock.elapsedRealtime() / 1000)

        val b = battery()
        o.put("batt_c", batteryTempC(b))
        b?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            o.put("level", if (level >= 0) level * 100 / scale else null)
            o.put("plugged", when (it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "none"
                else -> "other"
            })
            o.put("voltage_mv", it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
        }
        o.put("current_now", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))

        o.put("thermal_status", pm.currentThermalStatus)
        if (Build.VERSION.SDK_INT >= 30) {
            val h = pm.getThermalHeadroom(30)
            if (!h.isNaN()) o.put("headroom_30s", h.toDouble())
        }
        o.put("power_save", pm.isPowerSaveMode)

        val screenOn = pm.isInteractive
        o.put("screen_on", screenOn)
        try {
            o.put("brightness", Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        } catch (_: Exception) {
        }
        dm.getDisplay(Display.DEFAULT_DISPLAY)?.let { o.put("refresh_hz", it.refreshRate.toDouble()) }

        updateForeground()
        o.put("fg", if (screenOn) foreground else null)

        o.put("audio_active", audio.isMusicActive)
        o.put("camera_in_use", camerasInUse.isNotEmpty())
        o.put("location_on", lm.isLocationEnabled)
        o.put("wifi_on", wifi.isWifiEnabled)
        o.put("bt_on", Settings.Global.getInt(ctx.contentResolver, "bluetooth_on", -1))

        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val mobile = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        o.put("net_kb", (rx - lastRx + tx - lastTx) / 1024)
        o.put("mobile_kb", (mobile - lastMobile) / 1024)
        lastRx = rx; lastTx = tx; lastMobile = mobile

        cpuFreqs()?.let { o.put("cpu_khz", it) }
        thermalZones()?.let { o.put("zones", it) }
        return o
    }

    private fun readSys(path: String): String? = try {
        File(path).readText().trim()
    } catch (_: Exception) {
        null
    }

    private fun cpuFreqs(): JSONArray? {
        val arr = JSONArray()
        var any = false
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val v = readSys("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull()
            if (v != null) any = true
            arr.put(v ?: JSONObject.NULL)
        }
        return if (any) arr else null
    }

    /** Normalmente bloqueado pelo Android para apps comuns; tenta mesmo assim. */
    private fun thermalZones(): JSONObject? {
        val dirs = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        val o = JSONObject()
        for (d in dirs) {
            val temp = readSys("${d.path}/temp")?.toDoubleOrNull() ?: continue
            val type = readSys("${d.path}/type") ?: d.name
            val c = if (kotlin.math.abs(temp) > 1000) temp / 1000 else temp
            if (c > 0 && c < 150) o.put(type, c)
        }
        return if (o.length() > 0) o else null
    }
}
