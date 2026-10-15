package com.ale.cineiptv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ale.cineiptv.R
import com.ale.cineiptv.data.PlaylistException
import com.ale.cineiptv.data.PlaylistRepository
import com.ale.cineiptv.data.PlaylistRepository.Progress
import com.ale.cineiptv.data.Prefs
import com.ale.cineiptv.data.RemoteDevice
import com.ale.cineiptv.data.RemotePlaylist
import com.ale.cineiptv.data.RemoteRepository
import com.ale.cineiptv.data.SourceType
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.ale.cineiptv.data.XtreamCreds
import com.ale.cineiptv.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * Pantalla de configuración: Device ID + las 3 formas de cargar la lista.
 * Si ya hay una lista guardada, la abre automáticamente al iniciar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedFileUri: Uri? = null
    private var loadJob: Job? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.textDeviceId.text = prefs.deviceId
        b.textDeviceKey.text = getString(R.string.device_key_fmt, prefs.deviceKey)

        restoreFields()

        b.toggleSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) showPanel(checkedId)
        }
        val initial = when (prefs.sourceType) {
            SourceType.URL -> R.id.btnSourceUrl
            SourceType.FILE -> R.id.btnSourceFile
            SourceType.XTREAM -> R.id.btnSourceXtream
        }
        b.toggleSource.check(initial)
        showPanel(initial)

        b.btnPickFile.setOnClickListener { openFilePicker() }
        b.btnCancelLoad.setOnClickListener {
            startupJob?.cancel()
            loadJob?.cancel()
            setLoading(false)
            b.btnLoad.requestFocus()
        }
        b.btnLoad.setOnClickListener { startLoad() }
        b.btnOpenCache.setOnClickListener { loadCache() }
        b.btnOpenCache.isVisible = PlaylistRepository.hasCache(this)

        val editMode = intent.getBooleanExtra(EXTRA_EDIT, false)
        val refresh = intent.getBooleanExtra(EXTRA_REFRESH, false)
        b.panelRemote.isVisible = RemoteRepository.isConfigured
        when {
            refresh -> startup(forceDownload = true)
            editMode -> {
                b.btnLoad.requestFocus()
                if (RemoteRepository.isConfigured) {
                    lifecycleScope.launch { showRemoteList(RemoteRepository.fetch(prefs)) }
                }
            }
            else -> startup(forceDownload = false)
        }
    }

    // ---------------------------------------------------------------- Panel web

    /**
     * Arranque: primero consulta el panel web. Si allí se ha cambiado algo (o la lista
     * actual ya venía del panel), se aplica la lista activa del panel. Si no hay panel
     * o no hay conexión, se usa la lista local como siempre.
     */
    private fun startup(forceDownload: Boolean) {
        if (!RemoteRepository.isConfigured) {
            if (forceDownload) startLoad() else localStartup()
            return
        }
        setLoading(true)
        setStatus(getString(R.string.status_panel))
        startupJob = lifecycleScope.launch {
            val remote = RemoteRepository.fetch(prefs)
            setLoading(false, keepOverlay = true)   // sin parpadeo si a continuación se carga la lista
            showRemoteList(remote)
            val playlist = remote?.activePlaylist
            val panelChanged = remote != null && remote.updatedAt != prefs.remoteSeenUpdatedAt
            if (remote != null && playlist != null && (panelChanged || prefs.useRemote)) {
                prefs.remoteSeenUpdatedAt = remote.updatedAt
                loadRemote(playlist, forceDownload)
            } else if (forceDownload) {
                startLoad()
            } else {
                localStartup()
            }
            // Si no se ha empezado a cargar nada, se muestra el formulario
            if (loadJob?.isActive != true) b.panelLoading.isVisible = false
        }
    }

    private var startupJob: Job? = null

    private fun localStartup() {
        when {
            PlaylistRepository.isLoaded -> goHome()
            PlaylistRepository.hasCache(this) -> loadCache()
            else -> b.btnLoad.requestFocus()
        }
    }

    private fun loadRemote(p: RemotePlaylist, forceDownload: Boolean) {
        val sig = p.signature
        prefs.useRemote = true
        if (!forceDownload && sig == prefs.loadedSignature) {
            if (PlaylistRepository.isLoaded) { goHome(); return }
            if (PlaylistRepository.hasCache(this)) { loadCache(); return }
        }
        val ua = prefs.effectiveUserAgent
        runLoad(p.label, onSuccess = { prefs.loadedSignature = sig }) { pr ->
            if (p.isXtream) {
                PlaylistRepository.loadXtream(this, XtreamCreds.of(p.host, p.user, p.pass), ua, pr)
            } else {
                PlaylistRepository.loadFromUrl(this, p.url.trim(), ua, pr) // detecta Xtream get.php solo
            }
        }
    }

    /** Muestra en la pantalla las listas asignadas a este dispositivo desde el panel. */
    private fun showRemoteList(remote: RemoteDevice?) {
        b.layoutRemote.removeAllViews()
        when {
            remote == null -> b.textRemoteHint.text = getString(R.string.remote_offline)
            remote.playlists.isEmpty() -> b.textRemoteHint.text = getString(R.string.remote_empty)
            else -> {
                b.textRemoteHint.text = getString(R.string.remote_choose)
                remote.playlists.forEachIndexed { i, p ->
                    val btn = MaterialButton(
                        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                    )
                    btn.text = if (i == remote.active) "★  ${p.label}" else p.label
                    btn.isAllCaps = false
                    btn.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
                    btn.setOnClickListener { selectRemote(i, p) }
                    b.layoutRemote.addView(btn)
                }
            }
        }
    }

    private fun selectRemote(index: Int, p: RemotePlaylist) {
        if (loadJob?.isActive == true) return
        lifecycleScope.launch {
            RemoteRepository.setActive(prefs, index) // también queda marcada en el panel
            loadRemote(p, forceDownload = false)
        }
    }

    private fun restoreFields() {
        b.editUrl.setText(prefs.m3uUrl)
        b.editHost.setText(prefs.xtHost)
        b.editUser.setText(prefs.xtUser)
        b.editPass.setText(prefs.xtPass)
        b.editUserAgent.setText(prefs.customUserAgent)
        if (prefs.fileUri.isNotBlank()) {
            selectedFileUri = Uri.parse(prefs.fileUri)
            b.textFileName.text = prefs.fileName.ifBlank { getString(R.string.file_selected) }
        }
    }

    private fun showPanel(checkedId: Int) {
        b.panelUrl.isVisible = checkedId == R.id.btnSourceUrl
        b.panelFile.isVisible = checkedId == R.id.btnSourceFile
        b.panelXtream.isVisible = checkedId == R.id.btnSourceXtream
        b.textError.isVisible = false
    }

    // ---------------------------------------------------------------- Carga

    private fun startLoad() {
        if (loadJob?.isActive == true) return
        prefs.customUserAgent = b.editUserAgent.text?.toString().orEmpty()
        val ua = prefs.effectiveUserAgent

        when (b.toggleSource.checkedButtonId) {
            R.id.btnSourceUrl -> {
                val url = b.editUrl.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    showError(getString(R.string.err_url)); b.editUrl.requestFocus(); return
                }
                prefs.m3uUrl = url
                prefs.sourceType = SourceType.URL
                runLoad(labelForUrl(url), onSuccess = ::markManual) { p -> PlaylistRepository.loadFromUrl(this, url, ua, p) }
            }
            R.id.btnSourceFile -> {
                val uri = selectedFileUri
                if (uri == null) {
                    showError(getString(R.string.err_no_file)); b.btnPickFile.requestFocus(); return
                }
                prefs.sourceType = SourceType.FILE
                runLoad(prefs.fileName.ifBlank { getString(R.string.source_file) }, onSuccess = ::markManual) { p ->
                    PlaylistRepository.loadFromUri(this, uri, p)
                }
            }
            R.id.btnSourceXtream -> {
                val host = b.editHost.text?.toString()?.trim().orEmpty()
                val user = b.editUser.text?.toString()?.trim().orEmpty()
                val pass = b.editPass.text?.toString()?.trim().orEmpty()
                if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    showError(getString(R.string.err_xtream)); return
                }
                prefs.xtHost = host; prefs.xtUser = user; prefs.xtPass = pass
                prefs.sourceType = SourceType.XTREAM
                val creds = XtreamCreds.of(host, user, pass)
                runLoad(
                    "Xtream · $user@${Uri.parse(creds.host).host ?: host}",
                    onSuccess = ::markManual
                ) { p ->
                    PlaylistRepository.loadXtream(this, creds, ua, p)
                }
            }
        }
    }

    private fun loadCache() {
        if (loadJob?.isActive == true) return
        runLoad(prefs.sourceLabel.ifBlank { getString(R.string.source_cache) }) { p ->
            PlaylistRepository.loadFromCache(this, p)
        }
    }

    /** Lista cargada a mano en la app: el panel no la sustituye hasta que se cambie algo allí. */
    private fun markManual() {
        prefs.useRemote = false
        prefs.loadedSignature = "manual"
    }

    private fun runLoad(
        label: String,
        onSuccess: () -> Unit = {},
        block: suspend ((Progress) -> Unit) -> Unit,
    ) {
        setLoading(true)
        loadJob = lifecycleScope.launch {
            try {
                block { progress -> runOnUiThread { showProgress(progress) } }
                onSuccess()
                if (PlaylistRepository.loadedLiveOnly) {
                    Toast.makeText(this@MainActivity, R.string.warn_live_only, Toast.LENGTH_LONG).show()
                }
                prefs.sourceLabel = label
                goHome()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlaylistException) {
                showError(e.message ?: getString(R.string.err_generic))
            } catch (e: IOException) {
                showError(getString(R.string.err_network, e.message ?: e.javaClass.simpleName))
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.err_memory))
            } catch (e: Exception) {
                showError(getString(R.string.err_unexpected, e.message ?: e.javaClass.simpleName))
            } finally {
                setLoading(false)
            }
        }
    }

    /** Texto de progreso: en el formulario y en la pantalla de carga. */
    private fun setStatus(text: String) {
        b.textStatus.text = text
        b.textLoadingStatus.text = text
    }

    private fun showProgress(p: Progress) {
        when (p) {
            is Progress.Downloading -> {
                val mb = p.bytes / 1_048_576.0
                if (p.total > 0) {
                    val pct = (p.bytes * 100 / p.total).toInt().coerceIn(0, 100)
                    setStatus(getString(R.string.status_downloading_pct, mb, pct))
                } else {
                    setStatus(getString(R.string.status_downloading, mb))
                }
            }
            is Progress.Parsing -> {
                val n = String.format(Locale("es", "ES"), "%,d", p.count)
                setStatus(if (PlaylistRepository.isXtream || xtreamLoading) {
                    getString(R.string.status_xt_live_count, n)
                } else {
                    getString(R.string.status_parsing, n)
                })
            }
            is Progress.Step -> {
                xtreamLoading = true
                setStatus(getString(
                    when (p.step) {
                        PlaylistRepository.STEP_LOGIN -> R.string.status_xt_login
                        PlaylistRepository.STEP_CATEGORIES -> R.string.status_xt_categories
                        else -> R.string.status_xt_live
                    }
                ))
            }
        }
    }

    /** true mientras se carga una cuenta Xtream (para los textos de progreso). */
    private var xtreamLoading = false

    private fun setLoading(loading: Boolean, keepOverlay: Boolean = false) {
        if (loading) xtreamLoading = false
        b.progressBar.isVisible = loading
        b.textStatus.isVisible = loading
        if (!keepOverlay) b.panelLoading.isVisible = loading
        if (loading) {
            b.textError.isVisible = false
            setStatus(getString(R.string.status_connecting))
            b.btnCancelLoad.requestFocus()
        }
        b.btnLoad.isEnabled = !loading
        b.btnOpenCache.isEnabled = !loading
        b.toggleSource.isEnabled = !loading
        b.btnOpenCache.isVisible = PlaylistRepository.hasCache(this)
    }

    private fun showError(msg: String) {
        b.textError.text = msg
        b.textError.isVisible = true
        b.btnLoad.requestFocus()
    }

    private fun goHome() {
        // Limpia la pila para no dejar menús antiguos detrás
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun labelForUrl(url: String): String = Uri.parse(url).host ?: url

    // ---------------------------------------------------------------- Archivo

    private fun openFilePicker() {
        try {
            // "*/*" porque muchos sistemas no reconocen el tipo MIME de .m3u
            pickFile.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            // Algunos TV Box no traen selector de archivos
            Toast.makeText(this, R.string.err_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun onFilePicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // No todos los proveedores lo permiten; no pasa nada, la lista se cachea igualmente.
        }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.file_selected)
        selectedFileUri = uri
        prefs.fileUri = uri.toString()
        prefs.fileName = name
        b.textFileName.text = name
        b.textError.isVisible = false
        if (!name.endsWith(".m3u", true) && !name.endsWith(".m3u8", true) && !name.endsWith(".txt", true)) {
            Toast.makeText(this, R.string.warn_extension, Toast.LENGTH_LONG).show()
        }
        b.btnLoad.requestFocus()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val EXTRA_EDIT = "extra_edit"
        const val EXTRA_REFRESH = "extra_refresh"

        fun open(ctx: Context, edit: Boolean = false, refresh: Boolean = false) {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_EDIT, edit)
                    .putExtra(EXTRA_REFRESH, refresh)
            )
        }
    }
}
