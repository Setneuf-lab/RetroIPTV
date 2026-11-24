package com.ale.cineiptv.util

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.ale.cineiptv.BuildConfig
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Network
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * Actualización automática desde GitHub Releases, sin preguntar:
 *  - La APK se descarga en segundo plano al abrir la app.
 *  - Solo se instala con la pantalla de inicio delante (nunca durante la reproducción):
 *    si la descarga acaba mientras se ve algo, espera a que se vuelva al inicio.
 *  - Android 12+: instalación silenciosa (la primera vez Android pide confirmar).
 *  - Android 11 o menos: solo aparece el diálogo "Instalar" del sistema.
 *  - Si algo falla no se avisa: se reintenta la próxima vez que se abra la app.
 */
object Updater {

    private class Release(val versionCode: Int, val apkUrl: String)

    /** Dentro de una misma ejecución de la app, no reintentar antes de este tiempo. */
    private const val RETRY_AFTER_MS = 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var busy = false
    private var lastCheckAt = 0L
    private var lastInstallAt = 0L
    private var readyApk: File? = null
    private var waitingPermission = false
    /** Confirmación pedida por Android cuando el inicio no estaba delante. */
    private var pendingConfirm: Intent? = null
    private var home: WeakReference<Activity>? = null

    private fun resumedHome(): Activity? = home?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun due(last: Long) = last == 0L || SystemClock.elapsedRealtime() - last >= RETRY_AFTER_MS

    /** Llamar en onResume de la pantalla de inicio. */
    fun onHomeResumed(activity: Activity) {
        home = WeakReference(activity)
        pendingConfirm?.let {
            pendingConfirm = null
            showConfirm(activity, it)
            return
        }
        val apk = readyApk?.takeIf { it.exists() }
        if (waitingPermission) {
            waitingPermission = false
            if (apk != null && activity.packageManager.canRequestPackageInstalls()) {
                startInstall(activity, apk)
                return
            }
        }
        if (busy) return
        if (apk != null) {
            if (due(lastInstallAt)) install(activity, apk)
            return
        }
        if (due(lastCheckAt)) checkAndDownload(activity)
    }

    /** Llamar en onPause de la pantalla de inicio. */
    fun onHomePaused() {
        home = null
    }

    private fun checkAndDownload(activity: Activity) {
        val ctx = activity.applicationContext
        busy = true
        lastCheckAt = SystemClock.elapsedRealtime()
        scope.launch {
            val apk = withContext(Dispatchers.IO) { runCatching { prepareUpdate(ctx) }.getOrNull() }
            busy = false
            readyApk = apk
            if (apk != null) resumedHome()?.let { install(it, apk) }
        }
    }

    /** Devuelve la APK lista para instalar, o null si no hay versión nueva. */
    private fun prepareUpdate(ctx: Context): File? {
        val rel = fetchLatest() ?: return null
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val name = "RetroIPTV-${rel.versionCode}.apk"
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }
        if (rel.versionCode <= BuildConfig.VERSION_CODE) return null
        val apk = File(dir, name)
        if (isUsable(ctx, apk)) return apk
        download(rel.apkUrl, apk)
        if (isUsable(ctx, apk)) return apk
        apk.delete()
        return null
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
                if (a.optString("name").endsWith(".apk")) return Release(code, a.optString("browser_download_url"))
            }
        }
        return null
    }

    private fun download(url: String, target: File) {
        val part = File(target.path + ".part")
        Network.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("vacío")
            val total = body.contentLength()
            body.byteStream().use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (total > 0 && part.length() != total) error("descarga incompleta")
        }
        if (!part.renameTo(target)) error("no se pudo guardar")
    }

    /** La APK está completa, es de esta app y es más nueva que la instalada. */
    private fun isUsable(ctx: Context, apk: File): Boolean {
        if (!apk.exists()) return false
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        return info.packageName == ctx.packageName &&
            PackageInfoCompat.getLongVersionCode(info) > BuildConfig.VERSION_CODE
    }

    private fun install(activity: Activity, apk: File) {
        lastInstallAt = SystemClock.elapsedRealtime()
        if (activity.packageManager.canRequestPackageInstalls()) startInstall(activity, apk)
        else explainPermission(activity, apk)
    }

    /** Android exige permitir que esta app instale actualizaciones. */
    private fun explainPermission(activity: Activity, apk: File) {
        if (activity.isFinishing) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_msg)
            .setPositiveButton(R.string.update_permission_open) { _, _ ->
                try {
                    waitingPermission = true
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                } catch (_: ActivityNotFoundException) {
                    // Algunos Fire TV no tienen esta pantalla: se intenta instalar directamente
                    waitingPermission = false
                    startInstall(activity, apk)
                }
            }
            .setNegativeButton(R.string.update_permission_later, null)
            .show()
    }

    private fun startInstall(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) installInBackground(activity, apk)
        else openSystemInstaller(activity, apk)
    }

    /** Android 11 o menos: el diálogo "Instalar" del sistema. */
    private fun openSystemInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Se reintentará la próxima vez
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun installInBackground(activity: Activity, apk: File) {
        val ctx = activity.applicationContext
        val installer = ctx.packageManager.packageInstaller
        busy = true
        scope.launch {
            val sessionId = withContext(Dispatchers.IO) { runCatching { writeSession(ctx, installer, apk) }.getOrNull() }
            busy = false
            if (sessionId == null) return@launch
            // Instalar cierra la app: si ya no se está en el inicio, se espera a volver
            val current = resumedHome()
            if (current == null) {
                runCatching { installer.abandonSession(sessionId) }
                lastInstallAt = 0L
                return@launch
            }
            Toast.makeText(current, R.string.update_installing, Toast.LENGTH_LONG).show()
            runCatching {
                installer.openSession(sessionId).use { it.commit(statusSender(ctx, sessionId)) }
            }.onFailure { runCatching { installer.abandonSession(sessionId) } }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun writeSession(ctx: Context, installer: PackageInstaller, apk: File): Int {
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("RetroIPTV.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    private fun statusSender(ctx: Context, sessionId: Int): IntentSender =
        PendingIntent.getBroadcast(
            ctx,
            sessionId,
            Intent(ctx, UpdateInstallReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    /** Resultado de la instalación. Solo importa cuando Android pide confirmación. */
    internal fun onInstallStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
        val current = resumedHome()
        if (current != null) showConfirm(current, confirm) else pendingConfirm = confirm
    }

    private fun showConfirm(activity: Activity, confirm: Intent) {
        try {
            activity.startActivity(confirm)
        } catch (_: ActivityNotFoundException) {
            // Se reintentará la próxima vez
        }
    }
}

/** Recibe de Android el resultado de la instalación en segundo plano. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallStatus(intent)
}
