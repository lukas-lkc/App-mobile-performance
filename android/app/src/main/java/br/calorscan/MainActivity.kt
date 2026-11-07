package br.calorscan

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sampler: Sampler

    private lateinit var live: TextView
    private lateinit var perms: TextView
    private lateinit var btnUsage: Button
    private lateinit var btnBattery: Button
    private lateinit var startRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var btnStop: Button
    private lateinit var summary: TextView
    private lateinit var exportRow: LinearLayout
    private lateinit var ghStatus: TextView
    private lateinit var tokenField: EditText

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sampler = Sampler(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        col.addView(text(26f, true).apply { text = "Calor Scan" })
        col.addView(text(14f).apply {
            text = "Descobre o que faz seu celular esquentar. Inicie o monitoramento e use o celular " +
                "normalmente — principalmente do jeito que ele costuma esquentar."
        })

        live = text(20f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)) }
        col.addView(live)

        col.addView(text(16f, true).apply { text = "1. Permissões" })
        perms = text(14f)
        col.addView(perms)
        btnUsage = button("Permitir acesso aos dados de uso") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "Ative \"Calor Scan\" na lista", Toast.LENGTH_LONG).show()
        }
        btnBattery = button("Permitir rodar em segundo plano") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        col.addView(btnUsage)
        col.addView(btnBattery)

        col.addView(text(16f, true).apply { text = "2. Monitorar" })
        status = text(14f)
        col.addView(status)
        startRow = row(
            button("30 min") { start(30) },
            button("1 hora") { start(60) },
            button("3 horas") { start(180) },
        )
        col.addView(startRow)
        col.addView(button("Até eu parar (máx. 24 h)") { start(24 * 60) }.also { it.tag = "long" })
        col.addView(CheckBox(this).apply {
            text = "Monitorar sozinho toda vez que o celular ligar (20 min)"
            isChecked = Session.settings(this@MainActivity).getBoolean("auto_boot", false)
            setOnCheckedChangeListener { _, on ->
                Session.settings(this@MainActivity).edit().putBoolean("auto_boot", on).apply()
                if (on) Toast.makeText(
                    this@MainActivity,
                    "Pronto! Reinicie o celular. Ao desbloquear, o monitoramento começa sozinho.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        btnStop = button("Parar monitoramento") {
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
            handler.postDelayed({ refresh(); showSummary() }, 500)
        }
        col.addView(btnStop)

        col.addView(text(16f, true).apply { text = "3. Resultado" })
        exportRow = row(
            button("Enviar relatório") { export() },
            button("Copiar resumo") { copySummary() },
        )
        col.addView(exportRow)
        col.addView(button("Atualizar resumo") { showSummary() })
        summary = text(13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(summary)

        col.addView(text(16f, true).apply { text = "Envio direto para o Claude (GitHub)" })
        ghStatus = text(14f)
        col.addView(ghStatus)
        tokenField = EditText(this).apply {
            hint = "Cole aqui o token do GitHub"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(tokenField)
        col.addView(button("Salvar e testar token") { saveToken() })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
        showSummary()
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun batteryUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refresh() {
        val t = sampler.batteryTempC()
        live.text = "🌡️ ${t ?: "?"} °C  ·  ${Report.thermalName(sampler.thermalStatus())}"

        val usage = hasUsageAccess()
        val batt = batteryUnrestricted()
        perms.text = (if (usage) "✅" else "❌") + " Dados de uso (qual app está aberto, internet por app)\n" +
            (if (batt) "✅" else "⚠️") + " Rodar em segundo plano sem ser fechado pela Samsung"
        btnUsage.visibility = if (usage) View.GONE else View.VISIBLE
        btnBattery.visibility = if (batt) View.GONE else View.VISIBLE

        val running = Session.running(this)
        val n = Session.samples(this).size
        status.text = when {
            running -> "Monitorando desde ${Report.hm(Session.startTime(this))} · $n amostras · " +
                "termina às ${Report.hm(Session.plannedEnd(this))}.\nPode sair do app e usar o celular normalmente."
            n > 0 -> "Último monitoramento: $n amostras. Iniciar outro apaga o anterior."
            else -> "Escolha por quanto tempo monitorar:"
        }
        startRow.visibility = if (running) View.GONE else View.VISIBLE
        (startRow.parent as LinearLayout).findViewWithTag<View>("long").visibility =
            if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        exportRow.visibility = if (n > 0) View.VISIBLE else View.GONE
        ghStatus.text = if (Uploader.configured(this))
            "✅ Configurado: \"Enviar relatório\" manda direto para o Claude, e o monitoramento ao ligar envia sozinho." +
                if (n > 0 && !running && Session.uploaded(this)) "\nÚltimo relatório já enviado." else ""
        else "Sem token: \"Enviar relatório\" abre o menu de compartilhar. Veja no README como criar o token."
    }

    private fun saveToken() {
        val t = tokenField.text.toString().trim()
        if (t.isEmpty()) return
        Toast.makeText(this, "Testando…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = Uploader.check(t)
            runOnUiThread {
                if (r.ok) {
                    Session.settings(this).edit().putString("gh_token", t).apply()
                    tokenField.setText("")
                }
                Toast.makeText(this, r.text, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun start(minutes: Int) {
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Dê a permissão de dados de uso primeiro", Toast.LENGTH_LONG).show()
            return
        }
        Session.start(this, minutes * 60_000L)
        startForegroundService(Intent(this, MonitorService::class.java))
        summary.text = ""
        handler.postDelayed({ refresh() }, 500)
    }

    private fun showSummary() {
        Thread {
            val txt = Report(this).let {
                it.summary(Session.samples(this), Session.startTime(this), Session.endTime(this))
            }
            runOnUiThread { summary.text = txt }
        }.start()
    }

    private fun copySummary() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Calor Scan", summary.text))
        Toast.makeText(this, "Resumo copiado", Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        if (Uploader.configured(this)) {
            Toast.makeText(this, "Enviando para o GitHub…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = Uploader.uploadReport(this)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (r.ok) "Enviado ✅ É só avisar o Claude." else "Falha no envio: ${r.text}",
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            }.start()
            return
        }
        Toast.makeText(this, "Gerando relatório…", Toast.LENGTH_SHORT).show()
        Thread {
            val uri = runCatching { Report(this).export() }.getOrNull()
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, "Não consegui salvar o relatório", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, "Salvo em Downloads/CalorScan", Toast.LENGTH_LONG).show()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Relatório Calor Scan")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Enviar relatório para…"))
            }
        }.start()
    }
}
