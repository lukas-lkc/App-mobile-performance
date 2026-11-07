package br.calorscan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Inicia o monitoramento sozinho quando o celular liga (se a opcao estiver ligada). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Session.settings(ctx)
        if (!prefs.getBoolean("auto_boot", false)) return
        val minutes = prefs.getInt("boot_minutes", 20)
        Session.start(ctx, minutes * 60_000L, boot = true)
        ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
    }
}
