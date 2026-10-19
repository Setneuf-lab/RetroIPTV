package com.ale.cineiptv.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Channel
import com.ale.cineiptv.data.PlaylistRepository
import com.ale.cineiptv.data.Prefs
import com.ale.cineiptv.util.ChannelName
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Audio del reproductor:
 *  - elegir pista (idioma, original, 5.1…) en los dos motores; se recuerda el idioma elegido
 *  - "radio con imagen": se silencia el canal y suena una emisora de la lista, con un retraso
 *    ajustable para que vaya a la par de la imagen (la radio suele ir por delante).
 */
@OptIn(UnstableApi::class)
class AudioController(
    private val activity: PlayerActivity,
    private val prefs: Prefs,
    private val player: () -> ExoPlayer?,
    private val vlc: () -> VlcEngine?,
    private val usingVlc: () -> Boolean,
    private val isLive: () -> Boolean,
    /** Silencia (true) o devuelve (false) el sonido del canal. */
    private val muteMain: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- Pistas

    /** Parámetros al empezar un contenido: el idioma de audio preferido, sin elecciones anteriores. */
    fun initialParameters(current: TrackSelectionParameters): TrackSelectionParameters =
        current.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(prefs.audioLang.ifEmpty { null })
            .build()

    private class AudioTrack(val label: String, val language: String?, val selected: Boolean, val select: () -> Unit)

    private fun tracks(): List<AudioTrack> = if (usingVlc()) vlcTracks() else exoTracks()

    private fun exoTracks(): List<AudioTrack> {
        val p = player() ?: return emptyList()
        val out = ArrayList<AudioTrack>()
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val lang = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                val parts = listOfNotNull(
                    langName(lang),
                    f.label?.takeIf { it.isNotBlank() },
                    channelsName(f.channelCount),
                    codecName(f.sampleMimeType),
                ).distinct()
                val name = parts.joinToString(" · ").ifEmpty { activity.getString(R.string.audio_track_fmt, out.size + 1) }
                val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                out.add(AudioTrack(name, lang, group.isTrackSelected(i)) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(override)
                        .build()
                })
            }
        }
        return out
    }

    private fun vlcTracks(): List<AudioTrack> {
        val engine = vlc() ?: return emptyList()
        val current = engine.audioTrack
        return engine.audioTracks().mapIndexed { n, (id, rawName) ->
            val lang = Regex("""\[([a-zA-Z]{2,3})]""").find(rawName)?.groupValues?.get(1)?.lowercase()
            val name = rawName.ifBlank { activity.getString(R.string.audio_track_fmt, n + 1) }
            AudioTrack(name, lang, id == current) { engine.setAudioTrack(id) }
        }
    }

    private fun langName(code: String?): String? =
        code?.let { Locale(it).getDisplayLanguage(Locale("es", "ES")) }
            ?.takeIf { it.isNotBlank() && it != code }
            ?.replaceFirstChar { it.uppercase() }
            ?: code?.uppercase()

    private fun channelsName(n: Int): String? = when (n) {
        1 -> "Mono"
        2 -> "Estéreo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby+"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        else -> null
    }

    // ---------------------------------------------------------------- Menú

    fun openMenu() {
        val tracks = tracks()
        val labels = ArrayList<CharSequence>()
        val actions = ArrayList<() -> Unit>()
        tracks.forEach { t ->
            labels.add((if (t.selected) "✓  " else "     ") + t.label)
            actions.add {
                t.select()
                t.language?.let { prefs.audioLang = it }
            }
        }
        if (isLive()) {
            val radio = radioChannel
            if (radio == null) {
                labels.add(activity.getString(R.string.audio_radio_add)); actions.add { pickRadio() }
            } else {
                labels.add(activity.getString(R.string.audio_radio_on, ChannelName.split(radio.name).first, delaySec))
                actions.add { openDelay() }
                labels.add(activity.getString(R.string.audio_radio_off)); actions.add { stopRadio() }
            }
        }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(R.string.audio_title)
        val onlyMessage = when {
            labels.isEmpty() -> R.string.audio_none
            tracks.size == 1 && labels.size == 1 -> R.string.audio_only_one
            else -> 0
        }
        if (onlyMessage != 0) builder.setMessage(onlyMessage)
        else builder.setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        builder.setNegativeButton(R.string.close, null).show()
    }

    // ---------------------------------------------------------------- Radio con imagen

    private var radioPlayer: ExoPlayer? = null
    var radioChannel: Channel? = null
        private set
    private var delaySec = 0
    private var radioReady = false
    private val resumeRadio = Runnable { resumeAt = 0L; radioPlayer?.playWhenReady = true }
    /** Momento (uptime) en que la radio pausada vuelve a sonar; 0 = no está en pausa. */
    private var resumeAt = 0L

    /** Pausa la radio [ms] más (sumándose a una pausa en curso). */
    private fun holdRadio(ms: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        resumeAt = maxOf(resumeAt, now) + ms
        radioPlayer?.playWhenReady = false
        handler.removeCallbacks(resumeRadio)
        handler.postAtTime(resumeRadio, resumeAt)
    }

    val radioOn: Boolean get() = radioChannel != null

    private fun isRadio(ch: Channel): Boolean {
        val text = (ch.group + " " + ch.name).uppercase()
        return RADIO_WORDS.any { Regex("""\b$it\b""").containsMatchIn(text) }
    }

    /** Radios de la lista del proveedor que son de España. */
    private fun providerSpanishRadios(): List<Channel> =
        PlaylistRepository.live.filter { ch ->
            isRadio(ch) && (com.ale.cineiptv.util.LangFilter.codeOf(ch.group) == "ES" ||
                com.ale.cineiptv.util.LangFilter.codeOf(ch.name) == "ES")
        }.distinctBy { it.url }

    /** Selector con buscador: radios españolas de internet (COPE y SER primero) y las de la lista. */
    private fun pickRadio() {
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val fromList = providerSpanishRadios()
        var shown: List<Channel> = emptyList()

        val search = android.widget.EditText(activity).apply {
            setHint(R.string.audio_radio_search)
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val adapter = android.widget.ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, ArrayList())
        val list = android.widget.ListView(activity).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (300 * density).toInt())
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(search)
            addView(status)
            addView(list)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.audio_radio_pick)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .show()

        var job: kotlinx.coroutines.Job? = null
        fun load(query: String) {
            job?.cancel()
            status.setText(R.string.audio_radio_loading)
            job = activity.lifecycleScope.launch {
                val internet = runCatching { com.ale.cineiptv.data.RadioDirectory.search(query) }.getOrDefault(emptyList())
                val mine = fromList.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                shown = (internet + mine).distinctBy { it.url }
                adapter.clear()
                adapter.addAll(shown.map { ChannelName.split(it.name).first })
                if (shown.isEmpty()) status.setText(R.string.audio_radio_none) else status.text = ""
                status.isVisible = shown.isEmpty() || status.text.isNotEmpty()
            }
        }
        val debounce = Runnable { load(search.text.toString()) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                handler.removeCallbacks(debounce)
                handler.postDelayed(debounce, 600)
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { startRadio(it, prefs.radioDelaySec) }
            dialog.dismiss()
        }
        dialog.setOnDismissListener { handler.removeCallbacks(debounce); job?.cancel() }
        load("")
        list.requestFocus()
    }

    private fun startRadio(ch: Channel, delay: Int) {
        releaseRadioPlayer()
        radioChannel = ch
        delaySec = delay
        radioReady = false
        val ua = ch.userAgent ?: prefs.effectiveUserAgent
        val rp = ExoPlayer.Builder(activity)
            // Guarda hasta 2,5 min de radio para poder retrasarla
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(MAX_BUFFER_MS, MAX_BUFFER_MS, 1_000, 2_000)
                .build())
            // Sin "foco de audio": si no, el reproductor principal se pausaría
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), false)
            .build()
        radioPlayer = rp
        rp.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !radioReady) {
                    radioReady = true
                    // Se deja de reproducir mientras sigue descargando: así va [delaySec] por detrás
                    if (delaySec > 0) holdRadio(delaySec * 1000L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(activity, R.string.audio_radio_failed, Toast.LENGTH_SHORT).show()
                stopRadio()
            }
        })
        val item = MediaItem.Builder().setUri(ch.url)
        val lower = ch.url.lowercase()
        if (lower.contains(".m3u8") || lower.contains("output=m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        rp.setMediaSource(
            DefaultMediaSourceFactory(StreamTuning.dataSource(true, ua, ch.referrer), StreamTuning.extractors())
                .createMediaSource(item.build())
        )
        rp.playWhenReady = true
        rp.prepare()
        muteMain(true)
        Toast.makeText(activity, activity.getString(R.string.audio_radio_starting, ChannelName.split(ch.name).first), Toast.LENGTH_LONG).show()
    }

    /** Cambia el retraso: si sube, se pausa la radio esos segundos; si baja, se vuelve a empezar. */
    private fun applyDelay(newDelay: Int) {
        val ch = radioChannel ?: return
        val diff = newDelay - delaySec
        if (diff == 0) return
        prefs.radioDelaySec = newDelay
        val rp = radioPlayer
        if (diff > 0 && rp != null && radioReady) {
            delaySec = newDelay
            holdRadio(diff * 1000L)
        } else {
            startRadio(ch, newDelay)
        }
    }

    private fun openDelay() {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        var pending = delaySec
        val value = TextView(activity).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.amber_light))
            text = activity.getString(R.string.audio_delay_value, pending)
        }
        val help = TextView(activity).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_secondary))
            setText(R.string.audio_delay_help)
        }
        // Se aplica cuando dejas de pulsar (si no, la radio se cortaría con cada pulsación)
        val commit = Runnable { applyDelay(pending) }
        fun step(sec: Int, labelRes: Int) = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(labelRes)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt(); marginEnd = (3 * density).toInt()
            }
            setOnClickListener {
                pending = (pending + sec).coerceIn(0, MAX_DELAY_SEC)
                value.text = activity.getString(R.string.audio_delay_value, pending)
                handler.removeCallbacks(commit)
                handler.postDelayed(commit, 900)
            }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(step(-5, R.string.audio_minus_5))
            addView(step(-1, R.string.audio_minus_1))
            addView(step(1, R.string.audio_plus_1))
            addView(step(5, R.string.audio_plus_5))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(value)
            addView(row)
            addView(help)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.audio_delay_title)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { handler.removeCallbacks(commit); applyDelay(pending) }
            .show()
        // Arriba y casi sin sombra, para ver el partido mientras se ajusta
        dialog.window?.let { w ->
            w.setGravity(Gravity.TOP)
            w.setDimAmount(0.15f)
        }
        row.getChildAt(3)?.requestFocus()
    }

    private fun releaseRadioPlayer() {
        handler.removeCallbacks(resumeRadio)
        resumeAt = 0L
        radioPlayer?.release()
        radioPlayer = null
    }

    /** Quita la radio y vuelve el sonido del canal. */
    fun stopRadio() {
        if (radioChannel == null && radioPlayer == null) return
        releaseRadioPlayer()
        radioChannel = null
        muteMain(false)
    }

    private companion object {
        val RADIO_WORDS = listOf("RADIO", "RADIOS", "FM", "EMISORAS")
        const val MAX_DELAY_SEC = 120
        const val MAX_BUFFER_MS = 150_000
    }
}
