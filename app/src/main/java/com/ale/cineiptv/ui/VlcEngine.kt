package com.ale.cineiptv.ui

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Segundo motor de reproducción: VLC (libVLC), el mismo que llevan IPTV Smarters o IBO.
 * Es más tolerante con emisiones defectuosas, formatos antiguos o cortes del servidor.
 * La app lo usa sola cuando el motor principal no puede con un canal.
 */
class VlcEngine(context: Context, private val layout: VLCVideoLayout) {

    interface Listener {
        fun onVlcPlaying()
        fun onVlcBuffering(percent: Float)
        fun onVlcError()
        fun onVlcEnded()
    }

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--http-reconnect",            // si el servidor corta, vuelve a conectar
            "--no-stats",
            "--audio-time-stretch",        // sin chasquidos al recuperar tras un corte
            "--avcodec-skiploopfilter=1",  // menos carga en aparatos modestos (Fire Stick)
        )
    )
    private val player = MediaPlayer(libVlc)
    private var attached = false
    var listener: Listener? = null

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> listener?.onVlcPlaying()
                MediaPlayer.Event.Buffering -> listener?.onVlcBuffering(e.buffering)
                MediaPlayer.Event.EncounteredError -> listener?.onVlcError()
                MediaPlayer.Event.EndReached -> listener?.onVlcEnded()
                else -> Unit
            }
        }
    }

    /**
     * Reproduce [url]. En directo se guarda un colchón mayor (como el motor principal),
     * en películas se empieza en [startMs].
     */
    fun play(url: String, userAgent: String, referrer: String?, isLive: Boolean, startMs: Long) {
        if (!attached) {
            player.attachViews(layout, null, false, false)
            attached = true
        }
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=" + if (isLive) LIVE_CACHE_MS else VOD_CACHE_MS)
        media.addOption(":http-user-agent=$userAgent")
        referrer?.let { media.addOption(":http-referrer=$it") }
        if (!isLive && startMs > 0) media.addOption(":start-time=" + startMs / 1000.0)
        player.media = media
        media.release()
        player.play()
    }

    val isPlaying: Boolean get() = player.isPlaying
    val positionMs: Long get() = player.time.coerceAtLeast(0)
    val durationMs: Long get() = player.length.coerceAtLeast(0)

    fun seekTo(ms: Long) { player.setTime(ms.coerceAtLeast(0)) }
    fun togglePause() { if (player.isPlaying) player.pause() else player.play() }
    fun stop() { player.stop() }

    fun release() {
        listener = null
        player.setEventListener(null)
        player.stop()
        if (attached) player.detachViews()
        attached = false
        player.release()
        libVlc.release()
    }

    private companion object {
        const val LIVE_CACHE_MS = 4000
        const val VOD_CACHE_MS = 2500
    }
}
