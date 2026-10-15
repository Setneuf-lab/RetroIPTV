package com.ale.cineiptv.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import com.ale.cineiptv.BuildConfig
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Network
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Actualización automática desde GitHub Releases.
 * Al abrir la app mira la última versión publicada; si es más nueva, ofrece
 * descargarla e instalarla (Android siempre pide confirmar la instalación).
 */
object Updater {

    class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** Solo se comprueba una vez por sesión. */
    private var checked = false

    fun checkOnStart(activity: Activity, scope: CoroutineScope) {
        if (checked) return
        checked = true
        scope.launch {
            val rel = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() } ?: return@launch
            if (rel.versionCode <= BuildConfig.VERSION_CODE || activity.isFinishing) return@launch
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_title)
                .setMessage(activity.getString(R.string.update_msg, rel.versionName, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.update_now) { _, _ -> download(activity, scope, rel) }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    /** Lee la última versión de GitHub (etiqueta "build-<versionCode>"). */
    private fun fetchLatest(): Release? {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank() || repo.startsWith("USUARIO/")) return null
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        Network.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val code = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    val name = json.optString("name").removePrefix("Retro IPTV ").ifBlank { code.toString() }
                    return Release(code, name, a.optString("browser_download_url"))
                }
            }
        }
        return null
    }

    private fun download(activity: Activity, scope: CoroutineScope, rel: Release) {
        val progress: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading)
            .setMessage("0 %")
            .setCancelable(false)
            .show()

        scope.launch {
            val file = File(activity.cacheDir, "updates/RetroIPTV.apk")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val req = Request.Builder().url(rel.apkUrl).build()
                    Network.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("vacío")
                        val total = body.contentLength()
                        var done = 0L
                        var lastPct = -1
                        body.byteStream().use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (isActive) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            withContext(Dispatchers.Main) { progress.setMessage("$pct %") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            progress.dismiss()
            if (!ok) {
                Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            install(activity, file)
        }
    }

    private fun install(activity: Activity, apk: File) {
        // La primera vez Android pide permiso para "instalar apps de Retro IPTV"
        if (!activity.packageManager.canRequestPackageInstalls()) {
            try {
                Toast.makeText(activity, R.string.update_permission, Toast.LENGTH_LONG).show()
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
                pendingApk = apk
                return
            } catch (_: ActivityNotFoundException) {
                // Algunos Fire TV no tienen esta pantalla: se intenta instalar directamente
            }
        }
        launchInstaller(activity, apk)
    }

    /** APK descargada esperando a que el usuario conceda el permiso de instalación. */
    private var pendingApk: File? = null

    /** Llamar en onResume: si se acaba de conceder el permiso, continúa la instalación. */
    fun resumePendingInstall(activity: Activity) {
        val apk = pendingApk ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        if (apk.exists()) launchInstaller(activity, apk)
    }

    private fun launchInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }
}
