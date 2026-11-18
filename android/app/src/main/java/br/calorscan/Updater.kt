package br.calorscan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizar o app pelo proprio app (aba Config), so quando a pessoa toca no botao.
 * 1. Le apk/VERSION_CODE e apk/VERSION do GitHub (a mesma fonte do QR e do CalorScan PC).
 * 2. Baixa o APK para a pasta interna do app e confere se e mesmo o CalorScan e se e mais novo.
 * 3. Entrega ao instalador oficial do Android (PackageInstaller), que pede a confirmacao.
 *    O Android so aceita se a assinatura for a mesma do app instalado.
 */
object Updater {
    private const val BASE = "https://raw.githubusercontent.com/lukas-lkc/App-mobile-performance/ccr-e3a2eeb0-ekv3ov/apk/"
    private const val ACTION = "br.calorscan.UPDATE_STATUS"
    private const val MAX = 60L * 1024 * 1024

    enum class Step { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

    @Volatile var step = Step.IDLE; private set
    @Volatile var msg = ""; private set
    @Volatile var newName = ""; private set
    @Volatile var newCode = 0L; private set
    @Volatile var progress = 0; private set
    private var receiver: BroadcastReceiver? = null

    fun installedCode(ctx: Context): Long = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

    /** Confere no GitHub. [done] roda na thread de fundo; a tela redesenha por conta propria. */
    fun check(ctx: Context, done: () -> Unit) {
        if (step == Step.CHECKING || step == Step.DOWNLOADING || step == Step.INSTALLING) return
        step = Step.CHECKING; msg = "Conferindo no GitHub…"
        Thread {
            try {
                val t = System.currentTimeMillis()  // evita resposta guardada (cache) de horas atras
                val code = text(BASE + "VERSION_CODE?t=$t").trim().toLong()
                val name = runCatching { text(BASE + "VERSION?t=$t").trim() }.getOrDefault(code.toString())
                newCode = code; newName = name
                if (code > installedCode(ctx)) { step = Step.AVAILABLE; msg = "Versão $name disponível." }
                else { step = Step.UP_TO_DATE; msg = "É a mais nova ✓" }
            } catch (e: Exception) {
                step = Step.ERROR; msg = "Não deu para conferir. Veja se a internet está ligada e tente de novo."
            }
            done()
        }.start()
    }

    /** Baixa e manda instalar. Antes, a pessoa precisa permitir "instalar apps desta fonte". */
    fun downloadAndInstall(ctx: Context, refresh: () -> Unit) {
        if (step == Step.DOWNLOADING || step == Step.INSTALLING) return
        step = Step.DOWNLOADING; progress = 0; msg = "Baixando a versão $newName…"; refresh()
        val app = ctx.applicationContext
        Thread {
            try {
                val f = File(app.cacheDir, "update.apk").apply { delete() }
                download(BASE + "CalorScan.apk?t=${System.currentTimeMillis()}", f) { p -> progress = p; refresh() }
                // confere o arquivo antes de entregar ao instalador
                val info = app.packageManager.getPackageArchiveInfo(f.path, 0)
                    ?: throw IllegalStateException("O arquivo baixado veio estragado. Tente de novo.")
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                if (info.packageName != app.packageName) throw IllegalStateException("O arquivo baixado não é o CalorScan.")
                if (code <= installedCode(app)) throw IllegalStateException("O arquivo baixado não é mais novo que o seu.")
                step = Step.INSTALLING; msg = "Confirme na janela do Android: toque em Atualizar."; refresh()
                install(app, f, refresh)
            } catch (e: Exception) {
                step = Step.ERROR
                msg = e.message?.takeIf { e is IllegalStateException } ?: "O download falhou. Veja a internet e tente de novo."
                refresh()
            }
        }.start()
    }

    private fun install(ctx: Context, f: File, refresh: () -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("CalorScan.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            listen(ctx, refresh)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), flags)
            s.commit(pending.intentSender)
        }
    }

    /** O Android responde aqui: pede a confirmacao da pessoa, depois avisa se deu certo. */
    private fun listen(ctx: Context, refresh: () -> Unit) {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { c.startActivity(it) } }
                        return
                    }
                    PackageInstaller.STATUS_SUCCESS -> { step = Step.IDLE; msg = "Atualizado ✓" }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> { step = Step.AVAILABLE; msg = "Atualização cancelada. Toque de novo quando quiser." }
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                        step = Step.ERROR
                        msg = "O Android recusou: o app instalado foi feito com outra assinatura. Use o CalorScan PC → “Reinstalar”."
                    }
                    else -> { step = Step.ERROR; msg = "O Android não instalou (${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro"})." }
                }
                runCatching { c.unregisterReceiver(this) }; receiver = null
                File(c.cacheDir, "update.apk").delete()
                refresh()
            }
        }
        receiver = r
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(r, filter)
    }

    private fun open(u: String): HttpURLConnection {
        var url = URL(u)
        repeat(5) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CalorScan-Android")
            }
            if (c.responseCode in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect() } else return c
        }
        throw IllegalStateException("Endereço do download mudou demais. Tente mais tarde.")
    }

    private fun text(u: String): String {
        val c = open(u)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText().take(64) }
        } finally { c.disconnect() }
    }

    private fun download(u: String, f: File, onProgress: (Int) -> Unit) {
        val c = open(u)
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            if (total > MAX) throw IllegalStateException("Arquivo grande demais; não parece o CalorScan.")
            var got = 0L; var last = -1
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got > MAX) throw IllegalStateException("Arquivo grande demais; não parece o CalorScan.")
                        val p = if (total > 0) (got * 100 / total).toInt() else 0
                        if (p >= last + 10) { last = p; onProgress(p) }
                    }
                }
            }
        } finally { c.disconnect() }
    }
}
