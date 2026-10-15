package com.ale.cineiptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.ale.cineiptv.data.Favorites
import com.ale.cineiptv.ui.adapters.DisplayItem
import com.ale.cineiptv.ui.adapters.ItemAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Channel
import com.ale.cineiptv.data.PlaylistRepository
import com.ale.cineiptv.data.Prefs
import com.ale.cineiptv.data.ShowRef
import com.ale.cineiptv.data.WatchHistory
import com.ale.cineiptv.data.PlayLog
import com.ale.cineiptv.data.ParentalControl
import androidx.media3.common.Format
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.ui.DefaultTimeBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ale.cineiptv.databinding.ActivityPlayerBinding

/**
 * Reproductor a pantalla completa (Media3/ExoPlayer).
 *  - TV en vivo: zapping con CH+/CH- o flechas arriba/abajo (con los controles ocultos),
 *    reintento automático si el stream se corta.
 *  - Películas/episodios: recuerda por dónde ibas ("continuar viendo").
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private var player: ExoPlayer? = null
    private var queue: List<Channel> = emptyList()
    private var index = 0
    private var isLive = false
    private var autoRetries = 0
    private lateinit var subtitles: SubtitleController
    /** Serie de los episodios (para "Seguir viendo"). */
    private var show: ShowRef? = null
    /** Abierto desde "Seguir viendo": continuar sin preguntar. */
    private var resumeDirectly = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { b.panelInfo.isVisible = false }
    private val retryRunnable = Runnable { play(index) }
    /** Canal en directo que no llega a arrancar: reconectar (2 veces) y si no, avisar. */
    private val startWatchdog = Runnable {
        if (usingVlc) return@Runnable
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_READY || b.panelError.isVisible) return@Runnable
        lastErrorDetail = getString(R.string.player_err_no_answer) +
            (variants.getOrNull(variant)?.format?.takeIf { it.isNotEmpty() }?.let { " · .$it" } ?: "")
        PlayLog.event("start_timeout", queue.getOrNull(index), mapOf(
            "s" to StreamTuning.LIVE_START_WATCHDOG_MS / 1000, "fmt" to variants.getOrNull(variant)?.let { describe(it) },
        ))
        if (!startedOk && tryNextVariant()) return@Runnable
        if (!startedOk && prefs.engineMode != "exo") { switchToVlc(auto = true); return@Runnable }
        if (startAttempts < 2) {
            startAttempts++
            play(index)
        } else {
            failOrUseVlc(getString(R.string.player_err_start), offerExternal = true)
        }
    }
    private var startAttempts = 0

    // ---------------------------------------------------------------- Motor VLC

    private var vlc: VlcEngine? = null
    /** El canal actual se está viendo con VLC. */
    private var usingVlc = false
    /** Canal que hay que ver con VLC (porque el principal falló con él en esta sesión). */
    private var forceVlcFor: String? = null
    private var vlcStarted = false

    private fun shouldUseVlc(ch: Channel): Boolean = when (prefs.engineMode) {
        "vlc" -> true
        "exo" -> false
        else -> forceVlcFor == ch.url || prefs.isVlcChannel(ch.url)
    }

    /** Si el motor principal no puede, se prueba con VLC antes de mostrar el error. */
    private fun failOrUseVlc(msg: String, offerExternal: Boolean) {
        if (!usingVlc && prefs.engineMode != "exo") switchToVlc(auto = true)
        else showError(msg, offerExternal && !usingVlc)
    }

    private fun switchToVlc(auto: Boolean) {
        val ch = queue.getOrNull(index) ?: return
        PlayLog.event("engine_vlc", ch, mapOf("auto" to auto, "detail" to lastErrorDetail))
        forceVlcFor = ch.url
        vlcResumeAt = if (isLive) 0L else currentPositionMs().takeIf { it > 0 } ?: savedPositionOf(ch)
        Toast.makeText(this, R.string.engine_switching, Toast.LENGTH_SHORT).show()
        autoRetries = 0
        play(index)
    }

    /** Punto en el que debe empezar VLC (al cambiar de motor a mitad de película). */
    private var vlcResumeAt = -1L

    private fun playWithVlc(ch: Channel, userAgent: String) {
        player?.stop()
        handler.removeCallbacks(frozenCheck)
        handler.removeCallbacks(startWatchdog)
        usingVlc = true
        vlcStarted = false
        b.playerView.isVisible = false
        b.vlcLayout.isVisible = true
        val engine = vlc ?: VlcEngine(this, b.vlcLayout).also { vlc = it; it.listener = vlcListener }
        val start = when {
            isLive -> 0L
            vlcResumeAt >= 0 -> vlcResumeAt
            else -> savedPositionOf(ch).also { if (it > 0) resumeAnswered.add(ch.url) }
        }
        vlcResumeAt = -1L
        playStartedAt = SystemClock.elapsedRealtime()
        engine.play(ch.url, userAgent, ch.referrer, isLive, start)
        handler.removeCallbacks(vlcWatchdog)
        handler.postDelayed(vlcWatchdog, VLC_START_WATCHDOG_MS)
        if (!isLive) { handler.removeCallbacks(periodicSave); handler.postDelayed(periodicSave, SAVE_EVERY_MS) }
    }

    /** Vuelve al motor principal (al cambiar a un canal que no necesita VLC). */
    private fun leaveVlc() {
        if (!usingVlc) return
        usingVlc = false
        handler.removeCallbacks(vlcWatchdog)
        vlc?.stop()
        b.vlcLayout.isVisible = false
        b.playerView.isVisible = true
    }

    private val vlcWatchdog = Runnable {
        if (!usingVlc || vlcStarted) return@Runnable
        PlayLog.event("start_timeout", queue.getOrNull(index), mapOf("s" to VLC_START_WATCHDOG_MS / 1000, "fmt" to "VLC"))
        if (isLive && autoRetries < 2) { autoRetries++; play(index) }
        else showError(getString(R.string.player_err_start))
    }

    private val vlcListener = object : VlcEngine.Listener {
        override fun onVlcPlaying() {
            if (!usingVlc) return
            handler.removeCallbacks(vlcWatchdog)
            b.panelError.isVisible = false
            if (!vlcStarted) {
                vlcStarted = true
                PlayLog.event("start_ok", queue.getOrNull(index), mapOf(
                    "ms" to SystemClock.elapsedRealtime() - playStartedAt, "fmt" to "VLC",
                ))
                // Funciona con VLC: la próxima vez este canal empieza directamente con VLC
                queue.getOrNull(index)?.let { if (prefs.engineMode == "auto") prefs.markVlcChannel(it.url) }
                autoRetries = 0
            }
        }

        override fun onVlcBuffering(percent: Float) = Unit

        override fun onVlcError() {
            if (!usingVlc) return
            lastErrorDetail = "VLC"
            PlayLog.event("error", queue.getOrNull(index), mapOf("detail" to "VLC", "live" to isLive, "started" to vlcStarted))
            if (isLive && autoRetries < MAX_AUTO_RETRIES) {
                autoRetries++
                handler.postDelayed(retryRunnable, minOf(1_000L * autoRetries, 4_000L))
            } else {
                showError(getString(R.string.player_err_generic, "VLC"))
            }
        }

        override fun onVlcEnded() {
            if (!usingVlc) return
            if (isLive) { play(index); return }       // el directo se cortó → reconectar
            queue.getOrNull(index)?.let { prefs.clearPosition(it.url); WatchHistory.remove(it.url) }
            offerNextEpisode()
        }
    }

    // ---- Posición y avance, sea cual sea el motor
    private fun currentPositionMs(): Long =
        if (usingVlc) vlc?.positionMs ?: 0L else player?.currentPosition ?: 0L

    private fun durationMs(): Long =
        if (usingVlc) vlc?.durationMs ?: 0L
        else player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun seekToMs(ms: Long) { if (usingVlc) vlc?.seekTo(ms) else player?.seekTo(ms) }

    private fun isPlayingNow(): Boolean = if (usingVlc) vlc?.isPlaying == true else player?.isPlaying == true

    /** En VLC no hay barra de controles: OK pausa/continúa y muestra el tiempo. */
    private fun showVlcStatus() {
        val pos = currentPositionMs(); val dur = durationMs()
        b.textSeekDelta.setText(if (vlc?.isPlaying == true) R.string.vlc_playing else R.string.vlc_paused)
        b.textSeekTime.text = formatTime(pos) + if (dur > 0) " / " + formatTime(dur) else ""
        b.progressSeek.isVisible = dur > 0
        if (dur > 0) b.progressSeek.progress = (pos * 1000 / dur).toInt()
        b.panelSeek.isVisible = true
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 2_500)
    }

    // ---- Variantes del canal actual (formato y User-Agent): se prueban por orden si falla
    private var variants: List<StreamVariants.Variant> = emptyList()
    private var variant = 0
    private var variantsFor: String? = null
    /** El canal actual llegó a reproducirse (entonces un fallo es un corte, no un formato malo). */
    private var startedOk = false

    private fun describe(v: StreamVariants.Variant) =
        "." + v.format + (if (v.userAgent == StreamVariants.ALT_UA) " · UA alt" else "")

    // ---- Tiempos para el informe
    private var playStartedAt = 0L
    private var bufferingSince = 0L

    /** Prueba la siguiente forma de pedir el canal. false si ya no quedan. */
    private fun tryNextVariant(): Boolean {
        if (variant >= variants.lastIndex) return false
        val from = variants[variant]
        variant++
        val to = variants[variant]
        PlayLog.event("variant", queue.getOrNull(index), mapOf(
            "from" to describe(from), "to" to describe(to), "detail" to lastErrorDetail,
        ))
        handler.removeCallbacks(retryRunnable)
        play(index)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = PlaylistRepository.playQueue
        if (queue.isEmpty()) { finish(); return }

        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        isLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        index = (savedInstanceState?.getInt(STATE_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, queue.lastIndex)

        b.btnRetry.setOnClickListener { autoRetries = 0; startAttempts = 0; variantsFor = null; play(index) }
        b.btnExternal.setOnClickListener { switchToVlc(auto = false) }
        b.btnNextNow.setOnClickListener { playNextEpisode() }
        b.btnNextCancel.setOnClickListener { cancelNextEpisode() }
        show = PlaylistRepository.playShow
        resumeDirectly = intent.getBooleanExtra(EXTRA_RESUME, false)
        Favorites.init(this)
        WatchHistory.init(this)
        PlayLog.init(this)
        subtitles = SubtitleController(this, b.playerView, prefs, { player }, { isLive })
        subtitles.applyStyle()
        fitControlsToTv()
        // Mientras se reconecta se queda la última imagen en vez de pantalla negra
        b.playerView.setKeepContentOnPlayerReset(true)
        b.btnSubtitles.setOnClickListener { b.playerView.hideController(); subtitles.openMenu() }
        setupChannelPanel()
    }

    /**
     * Los controles del reproductor ocupan toda la pantalla. En directo se ocultan el tiempo
     * y la barra de progreso (00:00); en películas la barra avanza de 10 en 10 segundos.
     */
    private fun fitControlsToTv() {
        fun view(name: String): View? {
            val id = resources.getIdentifier(name, "id", packageName)
            return if (id != 0) b.playerView.findViewById(id) else null
        }
        val h = resources.getDimensionPixelSize(R.dimen.overscan_h)
        val v = resources.getDimensionPixelSize(R.dimen.overscan_v)
        (view("exo_progress") as? DefaultTimeBar)?.setKeyTimeIncrement(SEEK_SMALL_MS)
        if (isLive) {
            listOf("exo_time", "exo_position", "exo_duration", "exo_progress").forEach { name ->
                view(name)?.visibility = View.GONE
            }
        }
        // La lista de canales tampoco debe pegarse al borde izquierdo
        if (h > 0) b.panelChannels.setPadding(h, v, b.panelChannels.paddingRight, 0)
    }

    // ---------------------------------------------------------------- Lista de canales superpuesta

    private lateinit var channelAdapter: ItemAdapter
    private val closePanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideChannels()
    }

    private fun setupChannelPanel() {
        channelAdapter = ItemAdapter(
            onClick = { pos ->
                hideChannels()
                if (pos != index) { autoRetries = 0; play(pos) }
            },
            onLongClick = { pos ->
                val nowFav = Favorites.toggle(queue[pos])
                Toast.makeText(this, if (nowFav) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
                channelAdapter.itemAt(pos)?.let { channelAdapter.updateItem(pos, it.copy(favorite = nowFav)) }
            },
        )
        b.recyclerChannels.layoutManager = LinearLayoutManager(this)
        b.recyclerChannels.adapter = channelAdapter
        onBackPressedDispatcher.addCallback(this, closePanelOnBack)

        // En móvil/tablet: botón para abrir la lista cuando se muestran los controles
        b.btnChannels.setOnClickListener { showChannels() }
        b.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            val shown = visibility == View.VISIBLE && !b.panelChannels.isVisible
            b.btnChannels.isVisible = shown && queue.size > 1
            b.btnSubtitles.isVisible = shown
        })
    }

    private fun showChannels() {
        if (queue.size < 2) return
        val items = queue.mapIndexed { i, ch ->
            DisplayItem(
                title = if (i == index) "▶  ${ch.name}" else ch.name,
                subtitle = ch.group,
                logo = ch.logo,
                number = if (isLive) PlaylistRepository.numberOf(ch) ?: (i + 1) else null,
                favorite = Favorites.isFavorite(ch),
            )
        }
        channelAdapter.submit(items, poster = false)
        b.playerView.hideController()
        b.btnChannels.isVisible = false
        b.btnSubtitles.isVisible = false
        b.panelChannels.isVisible = true
        closePanelOnBack.isEnabled = true
        val lm = b.recyclerChannels.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(index, (120 * resources.displayMetrics.density).toInt())
        b.recyclerChannels.post {
            b.recyclerChannels.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                ?: b.recyclerChannels.requestFocus()
        }
    }

    private fun hideChannels() {
        b.panelChannels.isVisible = false
        closePanelOnBack.isEnabled = false
        b.playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (queue.isEmpty()) return
        initPlayer()
        play(index)
    }

    override fun onStop() {
        super.onStop()
        if (!::b.isInitialized) return
        savePosition()
        releasePlayer()
        restoreDisplayMode()
        PlayLog.flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------------------------------------------------------------- Player

    private fun initPlayer() {
        if (player != null) return
        // Incluye FFmpeg para audio y el retraso ajustable de subtítulos
        val renderers = subtitles.renderersFactory(this)
            // Si el decodificador por hardware falla (p. ej. 4K), probar con otro del aparato
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(SEEK_SMALL_MS)
            .setSeekForwardIncrementMs(SEEK_SMALL_MS)
            .setLoadControl(StreamTuning.loadControl(isLive))   // colchón anti-cortes
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { p ->
                p.trackSelectionParameters = subtitles.initialParameters(p.trackSelectionParameters)
                p.addListener(listener)
                b.playerView.player = p
            }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(periodicSave)
        handler.removeCallbacks(startWatchdog)
        handler.removeCallbacks(frozenCheck)
        cancelSeek()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        b.playerView.player = null
        handler.removeCallbacks(vlcWatchdog)
        vlc?.release()
        vlc = null
        usingVlc = false
        if (::b.isInitialized) { b.vlcLayout.isVisible = false; b.playerView.isVisible = true }
    }

    private fun play(i: Int) {
        val p = player ?: return
        handler.removeCallbacks(retryRunnable)
        val newIndex = i.coerceIn(0, queue.lastIndex)
        if (newIndex != index) {
            subtitles.resetDelay()   // la sincronización es de cada vídeo
            startAttempts = 0
            if (isLive) previousChannel = queue.getOrNull(index)
        }
        cancelNextEpisode()
        index = newIndex
        val ch = queue[index]
        PlaylistRepository.lastPlayed = ch
        // Subtítulos según las preferencias (idioma elegido la última vez, o desactivados)
        p.trackSelectionParameters = subtitles.initialParameters(p.trackSelectionParameters)
        b.panelError.isVisible = false
        b.btnExternal.isVisible = false
        cancelSeek()
        showInfo(ch)

        // Formas de pedir este canal (se recuerda la que funcionó la última vez)
        if (variantsFor != ch.url) {
            variantsFor = ch.url
            startedOk = false
            lastErrorDetail = ""
            variants = StreamVariants.of(ch.url, ch.userAgent ?: prefs.effectiveUserAgent)
            variant = StreamVariants.preferred(ch.url).coerceIn(0, variants.lastIndex)
        }
        val v = variants[variant]
        if (shouldUseVlc(ch)) {
            playWithVlc(ch, variants[0].userAgent)
            return
        }
        leaveVlc()
        val dataSource = StreamTuning.dataSource(isLive, v.userAgent, ch.referrer)
        playStartedAt = SystemClock.elapsedRealtime()
        bufferingSince = 0L

        val itemBuilder = MediaItem.Builder().setUri(v.url)
        if (isLive) itemBuilder.setLiveConfiguration(StreamTuning.liveConfiguration())
        val lower = v.url.lowercase()
        // HLS sin extensión .m3u8 al final (p. ej. con parámetros) → forzar tipo
        if (lower.contains(".m3u8") || lower.contains("output=m3u8") || lower.contains("type=m3u8")) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val source = DefaultMediaSourceFactory(dataSource, StreamTuning.extractors())
            .setLoadErrorHandlingPolicy(StreamTuning.errorPolicy(isLive)) // reintentos silenciosos
            .createMediaSource(itemBuilder.build())
        p.setMediaSource(source)
        val saved = if (isLive) 0L else savedPositionOf(ch)
        when {
            saved <= 0 -> p.playWhenReady = true
            resumeDirectly || resumeAnswered.contains(ch.url) -> {
                resumeAnswered.add(ch.url)
                p.seekTo(saved); p.playWhenReady = true
            }
            else -> { p.playWhenReady = false; askResume(ch, saved) }   // se pregunta mientras carga
        }
        resumeDirectly = false
        p.prepare()
        if (!isLive) { handler.removeCallbacks(periodicSave); handler.postDelayed(periodicSave, SAVE_EVERY_MS) }
        frozenRecoveries = 0
        lastRenderedFrames = -1
        handler.removeCallbacks(frozenCheck)
        handler.postDelayed(frozenCheck, FROZEN_CHECK_MS)
        handler.removeCallbacks(startWatchdog)
        if (isLive) handler.postDelayed(startWatchdog, StreamTuning.LIVE_START_WATCHDOG_MS)
    }

    /** Vídeos ya decididos en esta sesión (al volver de otra app no se vuelve a preguntar). */
    private val resumeAnswered = HashSet<String>()

    private fun savedPositionOf(ch: Channel): Long =
        WatchHistory.positionOf(ch.url).takeIf { it > 0 } ?: prefs.getPosition(ch.url)

    /** "¿Continuar desde 45:20?" — Continuar está seleccionado por defecto. */
    private fun askResume(ch: Channel, pos: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.resume_title)
            .setItems(arrayOf(getString(R.string.resume_from, formatTime(pos)), getString(R.string.resume_start))) { _, which ->
                resumeAnswered.add(ch.url)
                val p = player ?: return@setItems
                if (which == 0) p.seekTo(pos) else p.seekTo(0)
                p.playWhenReady = true
            }
            .setOnCancelListener {
                resumeAnswered.add(ch.url)
                player?.let { it.seekTo(pos); it.playWhenReady = true }
            }
            .show()
    }

    // ---------------------------------------------------------------- Imagen congelada

    private var lastRenderedFrames = -1
    private var lastCheckPosition = 0L
    private var frozenRecoveries = 0

    /**
     * Vigila que la imagen avance: si el audio sigue pero no se pinta ningún fotograma nuevo
     * (decodificador de vídeo atascado), se reconecta. Si vuelve a pasar, se avisa y se
     * ofrece abrirlo en VLC.
     */
    private val frozenCheck: Runnable = object : Runnable {
        override fun run() {
            if (usingVlc) return
            val p = player ?: return
            handler.postDelayed(this, FROZEN_CHECK_MS)
            if (!p.isPlaying || b.panelError.isVisible) { lastRenderedFrames = -1; return }
            val counters = p.videoDecoderCounters ?: return   // sin vídeo (radio): nada que vigilar
            counters.ensureUpdated()
            val frames = counters.renderedOutputBufferCount
            val pos = p.currentPosition
            val advanced = pos - lastCheckPosition
            val stuck = lastRenderedFrames >= 0 && frames == lastRenderedFrames && advanced > FROZEN_CHECK_MS / 2
            lastRenderedFrames = frames
            lastCheckPosition = pos
            if (!stuck) return
            PlayLog.event("frozen", queue.getOrNull(index), mapOf(
                "fmt" to variants.getOrNull(variant)?.let { describe(it) }, "attempt" to frozenRecoveries + 1,
            ))
            if (frozenRecoveries < 2) {
                frozenRecoveries++
                val keep = if (isLive) C.TIME_UNSET else pos
                val recoveries = frozenRecoveries
                play(index)
                frozenRecoveries = recoveries
                if (keep != C.TIME_UNSET) player?.seekTo(keep)
            } else {
                // Otra forma de pedir el canal (p. ej. .m3u8 en vez de .ts) puede decodificarse bien
                if (tryNextVariant()) return
                handler.removeCallbacks(this)
                failOrUseVlc(getString(R.string.player_err_frozen), offerExternal = true)
            }
        }
    }

    private val periodicSave: Runnable = object : Runnable {
        override fun run() {
            if (isPlayingNow()) savePosition()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            player?.videoFormat?.let { applyFrameRate(it.frameRate) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    val now = SystemClock.elapsedRealtime()
                    val ch = queue.getOrNull(index)
                    if (!startedOk && playStartedAt > 0) {
                        PlayLog.event("start_ok", ch, mapOf(
                            "ms" to now - playStartedAt,
                            "fmt" to variants.getOrNull(variant)?.let { describe(it) },
                        ))
                    } else if (bufferingSince > 0) {
                        // Corte durante la reproducción (se quedó cargando)
                        PlayLog.event("stall", ch, mapOf("ms" to now - bufferingSince))
                    }
                    playStartedAt = 0L
                    bufferingSince = 0L
                    variantsFor?.let { StreamVariants.remember(it, variant) }
                    startedOk = true
                    autoRetries = 0
                    startAttempts = 0
                    handler.removeCallbacks(startWatchdog)
                }
                Player.STATE_BUFFERING -> if (startedOk && bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()
                Player.STATE_ENDED -> {
                    if (isLive) play(index)               // stream cortado → reconectar
                    else {
                        prefs.clearPosition(queue[index].url)
                        WatchHistory.remove(queue[index].url)
                        offerNextEpisode()
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            lastErrorDetail = errorDetail(error)
            PlayLog.event("error", queue.getOrNull(index), mapOf(
                "detail" to lastErrorDetail, "live" to isLive, "started" to startedOk,
            ))
            // Primero, otra forma de pedir el canal (otro formato u otro User-Agent)
            if (!startedOk && tryNextVariant()) return
            // No llegó a arrancar con ninguna variante, o el aparato no puede decodificarlo: VLC
            if ((!startedOk || isDecodingError(error)) && !usingVlc && prefs.engineMode != "exo") {
                switchToVlc(auto = true); return
            }
            if (isLive && autoRetries < MAX_AUTO_RETRIES) {
                autoRetries++
                handler.postDelayed(retryRunnable, minOf(1_000L * autoRetries, 4_000L))
                return
            }
            failOrUseVlc(errorMessage(error), offerExternal = true)
        }
    }

    // ---------------------------------------------------------------- Ajuste de fotogramas (AFR)

    /** fps al que se ha ajustado la tele (para no cambiar de modo en cada canal igual). */
    private var appliedFps = -1f

    /**
     * Cambia la frecuencia de la tele a un múltiplo exacto del vídeo (25 fps → 50 Hz,
     * 24 → 24 Hz, 30 → 60 Hz) para que el movimiento sea fluido. Si la tele no tiene
     * ese modo, no se toca nada.
     */
    private fun applyFrameRate(fps: Float) {
        if (!prefs.afrEnabled || fps <= 1f || kotlin.math.abs(fps - appliedFps) < 0.01f) return
        val display = window.decorView.display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { m ->
                val ratio = m.refreshRate / fps
                val n = kotlin.math.round(ratio)
                n >= 1f && kotlin.math.abs(ratio - n) < 0.01f
            }
            .maxByOrNull { it.refreshRate } ?: return
        appliedFps = fps
        if (best.modeId == current.modeId) return
        val lp = window.attributes
        lp.preferredDisplayModeId = best.modeId
        window.attributes = lp
    }

    /** Al salir, la tele vuelve a su modo normal. */
    private fun restoreDisplayMode() {
        if (appliedFps < 0) return
        appliedFps = -1f
        val lp = window.attributes
        lp.preferredDisplayModeId = 0
        window.attributes = lp
    }

    // ---------------------------------------------------------------- Siguiente episodio

    private var nextCountdown = 0
    private val nextTick: Runnable = object : Runnable {
        override fun run() {
            if (nextCountdown <= 0) { playNextEpisode(); return }
            b.textNextCountdown.text = getString(R.string.next_title_fmt, nextCountdown)
            nextCountdown--
            handler.postDelayed(this, 1_000)
        }
    }

    /** Al terminar un episodio: "Siguiente episodio en 10 s". */
    private fun offerNextEpisode() {
        if (show == null || index >= queue.lastIndex) return
        b.textNextName.text = queue[index + 1].name
        nextCountdown = NEXT_EPISODE_SECONDS
        b.panelNext.isVisible = true
        b.playerView.hideController()
        b.btnNextNow.requestFocus()
        handler.removeCallbacks(nextTick)
        nextTick.run()
    }

    private fun playNextEpisode() {
        handler.removeCallbacks(nextTick)
        b.panelNext.isVisible = false
        if (index < queue.lastIndex) play(index + 1)
    }

    private fun cancelNextEpisode() {
        handler.removeCallbacks(nextTick)
        if (::b.isInitialized) b.panelNext.isVisible = false
    }

    // ---------------------------------------------------------------- Zapping por número y canal anterior

    private var digits = ""
    private var previousChannel: Channel? = null
    private val commitDigits = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        b.textDigits.isVisible = false
        if (n != null) zapToNumber(n)
    }

    private fun typeDigit(d: Int) {
        if (digits.length >= 5) return
        digits += d
        b.textDigits.text = digits
        b.textDigits.isVisible = true
        handler.removeCallbacks(commitDigits)
        handler.postDelayed(commitDigits, 1_600)
    }

    /** Va al canal con ese número: primero en la lista abierta, si no, en todos los canales. */
    private fun zapToNumber(n: Int) {
        val inQueue = queue.indexOfFirst { PlaylistRepository.numberOf(it) == n }
        if (inQueue >= 0) { autoRetries = 0; play(inQueue); return }
        val all = PlaylistRepository.live
        val inAll = all.indexOfFirst { PlaylistRepository.numberOf(it) == n && !ParentalControl.isLocked(it.group) }
        if (inAll >= 0) {
            previousChannel = queue.getOrNull(index)
            queue = all
            PlaylistRepository.playQueue = all
            index = inAll
            autoRetries = 0
            play(inAll)
            return
        }
        Toast.makeText(this, getString(R.string.zap_not_found, n), Toast.LENGTH_SHORT).show()
    }

    /** Vuelve al último canal que se estaba viendo. */
    private fun zapPrevious() {
        val prev = previousChannel ?: return
        val current = queue.getOrNull(index)
        var i = queue.indexOf(prev)
        if (i < 0) {
            i = PlaylistRepository.live.indexOf(prev)
            if (i < 0) return
            queue = PlaylistRepository.live
            PlaylistRepository.playQueue = queue
            index = -1
        }
        autoRetries = 0
        play(i)
        previousChannel = current      // así se puede ir y volver entre dos canales
        b.textInfoName.text = getString(R.string.zap_previous) + "  ·  " + prev.name
    }

    private fun isDecodingError(e: PlaybackException) = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )

    private fun errorMessage(e: PlaybackException): String = if (isDecodingError(e)) getString(R.string.player_err_decoding) else when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            getString(R.string.player_err_http)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            getString(R.string.player_err_network)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            getString(R.string.player_err_format)
        else -> getString(R.string.player_err_generic, e.errorCodeName)
    }

    /** Datos técnicos del último fallo (para poder diagnosticarlo). */
    private var lastErrorDetail = ""

    private fun errorDetail(e: PlaybackException): String {
        var cause: Throwable? = e
        var http = ""
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) { http = "HTTP ${cause.responseCode} · "; break }
            cause = cause.cause
        }
        val v = variants.getOrNull(variant)
        val fmt = v?.format?.takeIf { it.isNotEmpty() }?.let { ".$it · " }.orEmpty()
        val ua = if (v?.userAgent == StreamVariants.ALT_UA) "UA alt · " else ""
        return "$http$fmt$ua${e.errorCodeName}"
    }

    private fun showError(msg: String, offerExternal: Boolean = false) {
        b.textError.text = if (lastErrorDetail.isEmpty()) msg else "$msg\n\n" + getString(R.string.player_err_detail, lastErrorDetail)
        b.panelError.isVisible = true
        b.btnExternal.isVisible = offerExternal
        if (offerExternal) b.btnExternal.requestFocus() else b.btnRetry.requestFocus()
    }

    // ---------------------------------------------------------------- VLC (reproductor externo)

    /** Al volver de VLC, guarda por dónde se quedó (para "Seguir viendo"). */
    private val vlcLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val pos = data.getLongExtra("extra_position", -1L)
        val dur = data.getLongExtra("extra_duration", -1L)
        if (pos >= 0 && dur > 0 && !isLive) WatchHistory.save(queue[index], pos, dur, show)
        // Se fue a VLC porque aquí no se podía ver: al volver, a la lista
        finish()
    }

    private fun openInVlc() {
        val ch = queue[index]
        val start = if (isLive) 0L else (currentPositionMs().takeIf { it > 0 } ?: savedPositionOf(ch))
        val intent = Intent(Intent.ACTION_VIEW)
            .setPackage(VLC_PACKAGE)
            .setDataAndTypeAndNormalize(Uri.parse(ch.url), "video/*")
            .putExtra("title", ch.name)
            .putExtra("from_start", start <= 0)
            .putExtra("position", start)
        try {
            vlcLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.vlc_missing, Toast.LENGTH_LONG).show()
            // Google Play o Amazon Appstore (Fire TV)
            for (store in listOf("market://details?id=$VLC_PACKAGE", "amzn://apps/android?p=$VLC_PACKAGE")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(store))); return } catch (_: ActivityNotFoundException) {}
            }
        }
    }

    private fun showInfo(ch: Channel) {
        b.textInfoNumber.isVisible = isLive
        b.textInfoNumber.text = (PlaylistRepository.numberOf(ch) ?: (index + 1)).toString()
        b.textInfoName.text = ch.name
        b.panelInfo.isVisible = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3_500)
    }

    private fun savePosition() {
        if (isLive || queue.isEmpty()) return
        if (player == null && vlc == null) return
        val ch = queue[index]
        val pos = if (seekTarget >= 0) seekTarget else currentPositionMs()
        val dur = durationMs()
        if (dur <= 0 || b.panelError.isVisible) return
        prefs.clearPosition(ch.url)          // formato antiguo: ahora todo va en "Seguir viendo"
        WatchHistory.save(ch, pos, dur, show)
    }

    // ---------------------------------------------------------------- Avance / retroceso progresivo

    /** Posición a la que se va a saltar (-1 = ninguna). Se aplica al dejar de pulsar. */
    private var seekTarget = -1L
    private var seekPresses = 0
    private var lastSeekKeyAt = 0L
    private val commitSeek = Runnable {
        if (seekTarget >= 0) seekToMs(seekTarget)
        seekTarget = -1L
        handler.postDelayed(hideSeek, 900)
    }
    private val hideSeek = Runnable { b.panelSeek.isVisible = false; seekPresses = 0 }

    /**
     * Cada pulsación salta un poco; si se pulsa seguido (o se mantiene), el salto crece:
     * 10 s → 30 s → 1 min → 3 min → 5 min. Al soltar, se aplica el salto.
     */
    private fun stepSeek(direction: Int, isRepeat: Boolean) {
        if (player == null && vlc == null) return
        val now = SystemClock.uptimeMillis()
        if (isRepeat && now - lastSeekKeyAt < 120) return       // tecla mantenida: sin pasarse
        if (now - lastSeekKeyAt > 1_600) seekPresses = 0          // pausa larga: vuelve a empezar poco a poco
        lastSeekKeyAt = now
        seekPresses++
        val step = when {
            seekPresses <= 2 -> SEEK_SMALL_MS
            seekPresses <= 4 -> 30_000L
            seekPresses <= 7 -> 60_000L
            seekPresses <= 11 -> 180_000L
            else -> 300_000L
        }
        val dur = durationMs().takeIf { it > 0 }
        val base = if (seekTarget >= 0) seekTarget else currentPositionMs()
        seekTarget = (base + direction * step).coerceIn(0L, dur?.let { it - 2_000L } ?: Long.MAX_VALUE)

        val delta = seekTarget - currentPositionMs()
        b.textSeekDelta.text = (if (delta >= 0) "⏩  +" else "⏪  −") + formatTime(kotlin.math.abs(delta))
        b.textSeekTime.text = formatTime(seekTarget) + (dur?.let { " / " + formatTime(it) } ?: "")
        b.progressSeek.isVisible = dur != null
        dur?.let { b.progressSeek.progress = (seekTarget * 1000 / it).toInt() }
        handler.removeCallbacks(hideSeek)
        b.panelSeek.isVisible = true
        handler.removeCallbacks(commitSeek)
        handler.postDelayed(commitSeek, SEEK_COMMIT_DELAY_MS)
    }

    private fun cancelSeek() {
        handler.removeCallbacks(commitSeek)
        handler.removeCallbacks(hideSeek)
        seekTarget = -1L
        seekPresses = 0
        if (::b.isInitialized) b.panelSeek.isVisible = false
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    // ---------------------------------------------------------------- Mando a distancia

    /**
     * Mando a distancia durante la reproducción:
     *  - OK (en directo) o ← : abre la lista de canales sin parar el vídeo
     *  - ↑ / ↓ y CH+ / CH- (en directo): canal anterior / siguiente
     *  - MENÚ / INFO: muestra los controles (pistas de audio, botón de subtítulos…)
     *  - Botón de subtítulos del mando (CC): menú de subtítulos
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::b.isInitialized && !b.panelChannels.isVisible) {
            val free = !b.playerView.isControllerFullyVisible && !b.panelError.isVisible
            // Películas/series: ← → avanzan o retroceden (con los controles ocultos o sobre la barra de tiempo)
            val onTimeBar = currentFocus?.let { it.id == resources.getIdentifier("exo_progress", "id", packageName) } == true
            // Motor VLC (películas): OK o play/pausa pausan y muestran el tiempo
            if (usingVlc && !isLive && !b.panelError.isVisible && !b.panelNext.isVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        vlc?.togglePause(); b.root.postDelayed({ showVlcStatus() }, 150); return true
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        showVlcStatus(); return true
                    }
                }
            }
            val mediaKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (!isLive && !b.panelError.isVisible && (free || onTimeBar || mediaKey)) {
                val dir = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
                    else -> 0
                }
                if (dir != 0) { stepSeek(dir, event.repeatCount > 0); return true }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (isLive) { zap(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (isLive) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (isLive && free) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (isLive && free) { zap(+1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (isLive && free && queue.size > 1) { showChannels(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (isLive && free && queue.size > 1) { showChannels(); return true }
                // → (con los controles ocultos) o el botón "canal anterior" del mando
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (isLive && free && previousChannel != null) { zapPrevious(); return true }
                KeyEvent.KEYCODE_LAST_CHANNEL -> if (isLive) { zapPrevious(); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (isLive) { typeDigit(event.keyCode - KeyEvent.KEYCODE_0); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { b.playerView.showController(); return true }
                KeyEvent.KEYCODE_CAPTIONS -> { subtitles.openMenu(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun zap(delta: Int) {
        if (queue.size < 2) return
        autoRetries = 0
        val next = (index + delta + queue.size) % queue.size
        play(next)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_INDEX = "extra_index"
        private const val EXTRA_LIVE = "extra_live"
        private const val STATE_INDEX = "state_index"
        /** Reconexiones completas en directo si los reintentos silenciosos no bastan. */
        private const val MAX_AUTO_RETRIES = 8
        private const val EXTRA_RESUME = "extra_resume"
        private const val VLC_PACKAGE = "org.videolan.vlc"
        /** Salto mínimo al avanzar/retroceder. */
        private const val SEEK_SMALL_MS = 10_000L
        /** Espera tras la última pulsación antes de saltar (permite encadenar pulsaciones). */
        private const val SEEK_COMMIT_DELAY_MS = 700L
        /** Cada cuánto se guarda el punto de una película (por si se apaga la tele). */
        private const val SAVE_EVERY_MS = 20_000L
        private const val NEXT_EPISODE_SECONDS = 10
        /** Tiempo para que VLC empiece antes de dar el canal por caído. */
        private const val VLC_START_WATCHDOG_MS = 15_000L
        /** Cada cuánto se comprueba que la imagen no esté congelada. */
        private const val FROZEN_CHECK_MS = 4_000L

        fun start(ctx: Context, index: Int, isLive: Boolean, resume: Boolean = false) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_LIVE, isLive)
                    .putExtra(EXTRA_RESUME, resume)
            )
        }
    }
}
