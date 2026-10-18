package com.ale.cineiptv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Channel
import com.ale.cineiptv.data.Favorites
import com.ale.cineiptv.data.MovieInfo
import com.ale.cineiptv.data.PlaylistRepository
import com.ale.cineiptv.data.Prefs
import com.ale.cineiptv.data.WatchHistory
import com.ale.cineiptv.databinding.ActivityDetailBinding
import com.ale.cineiptv.util.ChannelName
import com.ale.cineiptv.util.DeviceCaps
import com.ale.cineiptv.util.TitleKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ficha de una película antes de verla: carátula, año, duración, nota, sinopsis y reparto,
 * con "Ver" / "Continuar" y favoritos.
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private lateinit var queue: List<Channel>
    private var index = 0
    private val movie: Channel get() = queue[index]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = PlaylistRepository.detailQueue
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (queue.isEmpty() || index !in queue.indices) { finish(); return }
        Favorites.init(this)
        WatchHistory.init(this)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Lo que ya sabemos sin preguntar al servidor
        val (name, quality) = ChannelName.split(movie.name)
        b.textTitle.text = name
        b.textQuality.isVisible = quality != null
        b.textQuality.text = quality.orEmpty()
        b.textMeta.text = movie.group
        b.imagePoster.load(movie.logo) { crossfade(false); error(R.drawable.ic_placeholder) }

        b.btnPlay.setOnClickListener { playChecked(fromStart = false) }
        b.btnFromStart.setOnClickListener { playChecked(fromStart = true) }
        b.btnFav.setOnClickListener {
            val nowFav = Favorites.toggle(movie)
            Toast.makeText(this, if (nowFav) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
            updateButtons()
        }
        b.btnPlay.requestFocus()

        lifecycleScope.launch {
            val info = PlaylistRepository.movieInfo(movie)
            b.progressInfo.isVisible = false
            if (info != null) showInfo(info) else b.textPlot.setText(R.string.detail_no_info)
        }
        loadVersions()
    }

    private val cantPlay4K: Boolean get() = DeviceCaps.is4K(movie.name) && !DeviceCaps.supports4K

    /**
     * Busca la misma película en otras calidades/idiomas ("Michael [4K]" → "Michael" HD, MULTI-SUB…).
     * Si este aparato no puede con 4K, avisa y deja enfocada la mejor versión HD.
     */
    private fun loadVersions() {
        val warn = cantPlay4K
        b.textWarn4k.isVisible = warn
        lifecycleScope.launch {
            val all = runCatching {
                if (PlaylistRepository.isXtream) PlaylistRepository.movieIndex(this@DetailActivity)
                else PlaylistRepository.movies
            }.getOrDefault(emptyList())
            val versions = withContext(Dispatchers.Default) {
                val key = TitleKey.of(movie.name)
                if (key.title.length < 2) return@withContext emptyList()
                val myLang = com.ale.cineiptv.util.LangFilter.codeOf(movie.name)
                all.asSequence()
                    .filter { it.url != movie.url && TitleKey.sameMovie(key, TitleKey.of(it.name)) }
                    .distinctBy { it.url }
                    .sortedWith(compareBy<Channel>(
                        { if (DeviceCaps.is4K(it.name) && !DeviceCaps.supports4K) 1 else 0 },
                        { if (com.ale.cineiptv.util.LangFilter.codeOf(it.name) == myLang) 0 else 1 },
                        { -qualityScore(it.name) },
                    ))
                    .take(8)
                    .toList()
            }
            if (versions.isEmpty()) return@launch
            b.textVersions.isVisible = true
            b.rowVersions.removeAllViews()
            versions.forEachIndexed { i, ch ->
                val unplayable = DeviceCaps.is4K(ch.name) && !DeviceCaps.supports4K
                val recommended = warn && i == 0 && !unplayable
                val btn = MaterialButton(this@DetailActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                btn.isAllCaps = false
                btn.text = versionLabel(ch, recommended)
                btn.alpha = if (unplayable) 0.6f else 1f
                btn.setOnClickListener { playVersion(ch) }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (i > 0) lp.marginStart = (10 * resources.displayMetrics.density).toInt()
                b.rowVersions.addView(btn, lp)
                if (recommended) btn.requestFocus()
            }
        }
    }

    /** "ES · HD (recomendada)", "EN · 4K", "MULTI-SUB"… */
    private fun versionLabel(ch: Channel, recommended: Boolean): String {
        val lang = com.ale.cineiptv.util.LangFilter.codeOf(ch.name)
        val quality = ChannelName.split(ch.name).second
        val parts = listOfNotNull(lang, quality).ifEmpty { listOf(ChannelName.split(ch.name).first.take(24)) }
        val label = parts.joinToString(" · ")
        return if (recommended) "▶ $label (" + getString(R.string.detail_recommended) + ")" else label
    }

    private fun qualityScore(name: String): Int {
        val q = ChannelName.split(name).second?.uppercase().orEmpty()
        return when {
            DeviceCaps.is4K(name) -> 4
            "FHD" in q || "1080" in q -> 3
            "HD" in q || "720" in q -> 2
            q.isEmpty() -> 1
            else -> 0
        }
    }

    private fun playVersion(ch: Channel) {
        val go = {
            PlaylistRepository.playQueue = listOf(ch)
            PlaylistRepository.playShow = null
            PlayerActivity.start(this, 0, isLive = false, resume = true)
        }
        if (DeviceCaps.is4K(ch.name) && !DeviceCaps.supports4K) confirm4K(go) else go()
    }

    private fun playChecked(fromStart: Boolean) {
        if (cantPlay4K) confirm4K { play(fromStart) } else play(fromStart)
    }

    private fun confirm4K(onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.detail_4k_confirm_title)
            .setMessage(R.string.detail_4k_confirm)
            .setPositiveButton(R.string.detail_4k_try) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) updateButtons()
    }

    private fun showInfo(info: MovieInfo) {
        val meta = ArrayList<String>()
        if (info.year.isNotEmpty()) meta.add(info.year)
        if (info.durationMin > 0) meta.add(
            if (info.durationMin >= 60) getString(R.string.detail_hours, info.durationMin / 60, info.durationMin % 60)
            else getString(R.string.detail_minutes, info.durationMin)
        )
        if (info.rating.isNotEmpty()) meta.add("★ " + info.rating)
        if (info.genre.isNotEmpty()) meta.add(info.genre)
        if (meta.isNotEmpty()) b.textMeta.text = meta.joinToString("  ·  ")
        b.textPlot.text = info.plot.ifEmpty { getString(R.string.detail_no_info) }
        val credits = ArrayList<String>()
        if (info.director.isNotEmpty()) credits.add(getString(R.string.detail_director, info.director))
        if (info.cast.isNotEmpty()) credits.add(getString(R.string.detail_cast, info.cast))
        b.textCredits.text = credits.joinToString("\n")
        b.textCredits.isVisible = credits.isNotEmpty()
        if (movie.logo.isNullOrBlank() && info.poster != null) b.imagePoster.load(info.poster) { crossfade(false) }
        (info.backdrop ?: info.poster)?.let { b.imageBackdrop.load(it) { crossfade(false) } }
    }

    /** "Ver" o "Continuar (45:20)" según lo que ya se haya visto. */
    private fun updateButtons() {
        val pos = WatchHistory.positionOf(movie.url).takeIf { it > 0 } ?: Prefs(this).getPosition(movie.url)
        b.btnPlay.text = if (pos > 0) getString(R.string.detail_continue, formatTime(pos)) else getString(R.string.detail_play)
        b.btnFromStart.isVisible = pos > 0
        b.btnFav.setText(if (Favorites.isFavorite(movie)) R.string.detail_fav_remove else R.string.detail_fav_add)
    }

    private fun play(fromStart: Boolean) {
        if (fromStart) {
            WatchHistory.remove(movie.url)
            Prefs(this).clearPosition(movie.url)
        }
        PlaylistRepository.playQueue = queue
        PlaylistRepository.playShow = null
        PlayerActivity.start(this, index, isLive = false, resume = !fromStart)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    companion object {
        private const val EXTRA_INDEX = "extra_index"

        fun start(ctx: Context, index: Int) {
            ctx.startActivity(Intent(ctx, DetailActivity::class.java).putExtra(EXTRA_INDEX, index))
        }
    }
}
