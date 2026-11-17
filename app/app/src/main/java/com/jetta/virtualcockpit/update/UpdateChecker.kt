package com.jetta.virtualcockpit.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização dentro do próprio app, sem depender da Play Store.
 *
 * Como funciona:
 * 1. O app baixa um arquivo `version.json` de um endereço configurado
 *    nas configurações (ex.: um arquivo num repositório GitHub).
 * 2. Se `versionCode` no JSON for maior que o instalado, oferece a
 *    atualização, baixa o APK e abre o instalador do Android.
 * 3. A instalação por cima preserva todas as configurações do app
 *    (adaptador salvo, modo demo, carro/cor escolhidos).
 *
 * Formato do version.json:
 * {
 *   "versionCode": 4,
 *   "versionName": "0.4.0",
 *   "apkUrl": "https://github.com/<usuario>/<repo>/releases/download/v0.4.0/app.apk",
 *   "notes": "O que mudou nesta versão."
 * }
 *
 * Usa apenas APIs do framework Android (sem dependências externas).
 */
class UpdateChecker(
    private val context: Context,
    private val prefs: SharedPreferences
) {

    companion object {
        const val PREF_UPDATE_URL = "update_url"
        private const val APK_FILE_NAME = "update.apk"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val notes: String?
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        object UpToDate : CheckResult
        object NotConfigured : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    /** Endereço do version.json, ou null quando ainda não foi configurado. */
    fun updateUrl(): String? =
        prefs.getString(PREF_UPDATE_URL, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setUpdateUrl(url: String) {
        prefs.edit().putString(PREF_UPDATE_URL, url.trim()).apply()
    }

    fun currentVersionCode(): Long {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun currentVersionName(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: ""
    }

    /** Verifica se há versão nova. O callback sempre roda na thread principal. */
    fun check(callback: (CheckResult) -> Unit) {
        val url = updateUrl()
        if (url == null) {
            post { callback(CheckResult.NotConfigured) }
            return
        }
        Thread {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                }
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val info = UpdateInfo(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    notes = json.optString("notes", null)
                )
                if (info.versionCode > currentVersionCode()) {
                    post { callback(CheckResult.Available(info)) }
                } else {
                    post { callback(CheckResult.UpToDate) }
                }
            } catch (e: Exception) {
                post { callback(CheckResult.Failed(e.message ?: "erro desconhecido")) }
            }
        }.start()
    }

    /**
     * Baixa o APK para o cache do app. [onProgress] recebe 0–100.
     * [onDone] recebe o arquivo baixado, ou null em caso de falha.
     */
    fun download(
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onDone: (File?) -> Unit
    ) {
        Thread {
            try {
                val file = File(context.cacheDir, APK_FILE_NAME)
                val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                }
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                var read = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buffer = ByteArray(8192)
                        var n: Int
                        while (input.read(buffer).also { n = it } > 0) {
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                post { onProgress(pct) }
                            }
                        }
                    }
                }
                post { onDone(file) }
            } catch (e: Exception) {
                post { onDone(null) }
            }
        }.start()
    }

    /**
     * Abre o instalador do Android para o APK baixado.
     * Retorna false quando ainda falta autorizar "instalar apps
     * desconhecidos" — nesse caso abre a tela dessa permissão e o
     * usuário pode tentar de novo depois de autorizar.
     */
    fun install(apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(settingsIntent)
            return false
        }
        val uri = UpdateFileProvider.uriForFile(apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }
}
