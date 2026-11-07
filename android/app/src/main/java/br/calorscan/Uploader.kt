package br.calorscan

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Envia o relatorio direto para o repositorio no GitHub, onde o Claude le. */
object Uploader {
    const val REPO = "lukas-lkc/App-mobile-performance"
    const val BRANCH = "ccr-e3a2eeb0-ekv3ov"

    data class Result(val ok: Boolean, val text: String)

    fun token(ctx: Context) = Session.settings(ctx).getString("gh_token", "")!!.trim()
    fun configured(ctx: Context) = token(ctx).isNotEmpty()

    private fun conn(path: String, method: String, token: String) =
        (URL("https://api.github.com/repos/$REPO$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CalorScan")
        }

    /** Confere se o token consegue escrever no repositorio. */
    fun check(token: String): Result = try {
        val c = conn("", "GET", token)
        val code = c.responseCode
        if (code != 200) Result(false, "GitHub respondeu $code — token inválido ou sem acesso ao repositório")
        else {
            val perms = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("permissions")
            if (perms?.optBoolean("push") == true) Result(true, "Token OK: envio direto configurado ✅")
            else Result(false, "Token sem permissão de escrita (precisa de Contents: Read and write)")
        }
    } catch (e: Exception) {
        Result(false, "Sem conexão: ${e.message}")
    }

    fun uploadReport(ctx: Context): Result = try {
        val json = Report(ctx).build().toString(1)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val kind = if (Session.isBoot(ctx)) "boot" else "uso"
        val path = "reports/app/calorscan_${stamp}_${kind}_${Build.MODEL}.json"
        val body = JSONObject()
            .put("message", "Relatorio Calor Scan $stamp ($kind)")
            .put("branch", BRANCH)
            .put("content", Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP))
            .toString()
        val c = conn("/contents/$path", "PUT", token(ctx))
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code == 200 || code == 201) {
            Session.markUploaded(ctx)
            Result(true, path)
        } else {
            Result(false, "GitHub respondeu $code")
        }
    } catch (e: Exception) {
        Result(false, e.message ?: "erro desconhecido")
    }
}
