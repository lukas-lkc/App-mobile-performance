package br.calorscan

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

data class Finding(val level: Int, val title: String, val text: String, val tab: Int = -1) {
    companion object { const val OK = 0; const val INFO = 1; const val WARN = 2; const val DANGER = 3 }
}

object Diagnosis {
    private val BR = Locale("pt", "BR")
    private val labels = HashMap<String, String>()

    fun mood(t: Double) = when {
        t < 35 -> "fresquinho"; t < 40 -> "morno"; t < 43 -> "quente"; else -> "muito quente"
    }

    fun thermalName(s: Int) = when (s) {
        0 -> "normal"; 1 -> "leve"; 2 -> "moderado"; 3 -> "severo (reduzindo desempenho)"
        4 -> "crítico"; 5 -> "emergência"; 6 -> "desligando"; else -> "?"
    }

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    fun f1(v: Double) = String.format(BR, "%.1f", v)

    /** Minutos representados por cada leitura (limitado para nao contar o celular desligado). */
    private fun weights(s: List<Sample>): List<Double> = s.mapIndexed { i, x ->
        if (i == 0) 0.5 else ((x.t - s[i - 1].t) / 60000.0).coerceIn(0.0, 2.0)
    }

    fun findings(ctx: Context): List<Finding> {
        val out = mutableListOf<Finding>()
        val now = System.currentTimeMillis()
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val today = Store.samples(ctx, midnight)
        val boot = Store.bootSamples(ctx)
        val up = SystemClock.elapsedRealtime() / 1000

        // 1. depois de ligar
        val bootWin = boot.filter { it.up <= 900 }
        if (bootWin.size >= 3) {
            val peak = bootWin.maxBy { it.c }
            val first = bootWin.first()
            val lvl = if (peak.c >= 43) Finding.DANGER else if (peak.c >= 40) Finding.WARN else Finding.OK
            out += Finding(lvl, "Depois de ligar: pico de ${f1(peak.c)} °C",
                "Aos ${peak.up / 60} min ${peak.up % 60} s ligado (primeira leitura: ${f1(first.c)} °C aos ${first.up} s). " +
                    if (peak.c >= 40) "É a fase em que tudo inicia junto. Veja em Esfriar quais apps iniciam sozinhos." else "Normal.",
                if (peak.c >= 40) 1 else -1)
        } else if (up < 900) {
            out += Finding(Finding.INFO, "Celular ligou há ${up / 60} min", "Medindo a fase de inicialização…")
        }

        // 2. hoje
        if (today.size >= 3) {
            val w = weights(today)
            val m40 = today.indices.filter { today[it].c >= 40 }.sumOf { w[it] }
            val m43 = today.indices.filter { today[it].c >= 43 }.sumOf { w[it] }
            val max = today.maxBy { it.c }
            out += Finding(if (m43 >= 5) Finding.DANGER else if (m40 >= 20) Finding.WARN else Finding.OK,
                "Hoje: máxima de ${f1(max.c)} °C às ${hm(max.t)}",
                "Tempo acima de 40 °C: ${m40.roundToInt()} min · acima de 43 °C: ${m43.roundToInt()} min.")
        }

        // 3. apps que mais esquentam com a tela ligada
        val byApp = today.filter { it.scr && it.fg != null }.groupBy { it.fg!! }
            .filter { it.value.size >= 3 }
            .map { (pkg, l) -> Triple(pkg, l.map { it.c }.average(), l.size) }
            .sortedByDescending { it.second }.take(3)
        if (byApp.isNotEmpty() && byApp.first().second >= 39) {
            out += Finding(if (byApp.first().second >= 42) Finding.WARN else Finding.INFO, "Apps mais quentes hoje",
                byApp.joinToString(" · ") { "${label(ctx, it.first)}: média ${f1(it.second)} °C" } +
                    ". Jogos, vídeo e câmera esquentam por natureza; dê pausas.")
        }

        // 4. carregando
        val ch = today.filter { it.plug }; val nch = today.filter { !it.plug }
        if (ch.size >= 5 && nch.size >= 5) {
            val diff = ch.map { it.c }.average() - nch.map { it.c }.average()
            if (diff >= 1.5) out += Finding(Finding.INFO, "Carregando fica ${f1(diff)} °C mais quente",
                "Evite jogar ou gravar vídeo enquanto carrega. Ligar \"Proteger bateria\" também ajuda.")
        }

        // 5. apps trabalhando em segundo plano desde que ligou
        val starters = backgroundSinceBoot(ctx).filterKeys { it !in Cold.restricted(ctx) && it !in Cold.exceptions(ctx) }
        if (starters.isNotEmpty()) {
            out += Finding(Finding.WARN, "Rodando em segundo plano desde que ligou",
                starters.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { label(ctx, it.key) } +
                    ". Os que não precisam de notificação na hora podem ser restringidos.", 1)
        }

        // 6. perfis
        val profiles = Work.profiles(ctx)
        if (profiles.isNotEmpty()) {
            out += Finding(Finding.INFO, "O celular roda ${profiles.size + 1} perfis",
                "Cada perfil (trabalho, Pasta Segura…) inicia sua própria cópia dos serviços do Google. " +
                    "Pausar o perfil de trabalho fora do expediente alivia o celular.", 2)
        }

        // 7. Samsung
        val sams = Cold.SAMSUNG.filter { Cold.pkgEnabled(ctx, it.first) }.map { it.second }
        if (sams.isNotEmpty()) {
            out += Finding(Finding.INFO, "Recursos da Samsung ligados", sams.joinToString(", ") +
                ". Pesam pouco, mas rodam o tempo todo (e em todos os perfis).", 1)
        }
        return out
    }

    /** Pacotes com servico em segundo plano ou internet em segundo plano desde o boot (bytes/pontos). */
    fun backgroundSinceBoot(ctx: Context): Map<String, Long> {
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val score = HashMap<String, Long>()
        val pm = ctx.packageManager
        fun userApp(p: String) = runCatching {
            pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
        }.getOrDefault(false) && p != ctx.packageName
        runCatching {
            val ev = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(bootAt, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START && userApp(e.packageName))
                    score[e.packageName] = (score[e.packageName] ?: 0) + 1_000_000
            }
        }
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        @Suppress("DEPRECATION")
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) runCatching {
            val st = nsm.querySummary(type, null, bootAt, now)
            val b = NetworkStats.Bucket()
            while (st.hasNextBucket()) {
                st.getNextBucket(b)
                if (b.state == NetworkStats.Bucket.STATE_FOREGROUND) continue
                pm.getPackagesForUid(b.uid)?.filter { userApp(it) }?.forEach { p ->
                    score[p] = (score[p] ?: 0) + b.rxBytes + b.txBytes
                }
            }
            st.close()
        }
        return score.filterValues { it >= 1_000_000 }
    }

    private fun hm(t: Long) = String.format(BR, "%tH:%<tM", t)

    // ------------------------------------------------------------- resumo para IA
    fun markdown(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("# Pedido de análise: celular esquentando").appendLine()
        sb.appendLine("Você é um especialista em desempenho e temperatura de celulares Android. Abaixo estão dados " +
            "reais medidos pelo app CalorScan no meu celular. Responda em português, direto e sem jargão:")
        sb.appendLine("1. O que mais provavelmente está causando o aquecimento, em ordem de impacto.")
        sb.appendLine("2. O que eu devo mudar, com o caminho exato no menu (One UI, se for Samsung).")
        sb.appendLine("3. O que é normal e não precisa de preocupação.")
        sb.appendLine("Não invente dados que não estão aqui. Se algo for incerto, diga.").appendLine()
        sb.appendLine("## Aparelho")
        sb.appendLine("- ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}" +
            if (Build.VERSION.SDK_INT >= 31) " (${Build.SOC_MODEL})" else "")
        sb.appendLine("- Ligado há ${SystemClock.elapsedRealtime() / 60000} min").appendLine()
        sb.appendLine("## Conclusões do app")
        findings(ctx).forEach { sb.appendLine("- **${it.title}** — ${it.text}") }
        sb.appendLine().appendLine("## Ajustes já feitos")
        val r = Cold.restricted(ctx)
        sb.appendLine("- Apps restringidos em segundo plano: " +
            if (r.isEmpty()) "nenhum" else r.joinToString(", ") { label(ctx, it) })
        sb.appendLine("- Exceções (precisam de notificação na hora): " +
            Cold.exceptions(ctx).joinToString(", ") { label(ctx, it) }.ifEmpty { "nenhuma" })
        sb.appendLine("- Perfis extras: ${Work.profiles(ctx).size}")
        val boot = Store.bootSamples(ctx).filter { it.up <= 900 }
        if (boot.isNotEmpty()) {
            sb.appendLine().appendLine("## Temperatura depois de ligar (s ligado → °C)")
            sb.appendLine(boot.filterIndexed { i, _ -> i % maxOf(1, boot.size / 30) == 0 }
                .joinToString(", ") { "${it.up}s→${f1(it.c)}" })
        }
        val last = Store.samples(ctx, System.currentTimeMillis() - 3 * 3_600_000)
        if (last.isNotEmpty()) {
            sb.appendLine().appendLine("## Últimas 3 horas (hora → °C, app na tela)")
            last.filterIndexed { i, _ -> i % maxOf(1, last.size / 40) == 0 }.forEach {
                sb.appendLine("- ${hm(it.t)} → ${f1(it.c)} °C${if (it.plug) ", carregando" else ""}" +
                    (it.fg?.let { p -> ", ${label(ctx, p)}" } ?: if (!it.scr) ", tela desligada" else ""))
            }
        }
        return sb.toString()
    }
}
