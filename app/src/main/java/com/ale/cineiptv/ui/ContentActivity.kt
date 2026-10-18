package com.ale.cineiptv.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ale.cineiptv.R
import com.ale.cineiptv.data.Category
import com.ale.cineiptv.data.Channel
import com.ale.cineiptv.data.ContentType
import com.ale.cineiptv.data.Favorites
import com.ale.cineiptv.data.ParentalControl
import com.ale.cineiptv.data.PlaylistException
import com.ale.cineiptv.data.PlaylistRepository
import com.ale.cineiptv.data.Prefs
import com.ale.cineiptv.data.SeriesShow
import com.ale.cineiptv.data.ShowRef
import com.ale.cineiptv.data.WatchHistory
import com.ale.cineiptv.util.Seasons
import com.ale.cineiptv.util.LangFilter
import com.ale.cineiptv.util.LangMatcher
import com.ale.cineiptv.util.ChannelName
import com.ale.cineiptv.databinding.ActivityContentBinding
import com.ale.cineiptv.databinding.ItemRecentChipBinding
import com.ale.cineiptv.ui.adapters.DisplayItem
import com.ale.cineiptv.ui.adapters.GroupAdapter
import com.ale.cineiptv.ui.adapters.ItemAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * Lista de una sección (TV en vivo, películas, series o favoritos) con:
 *  - columna/franja de grupos (mantener OK sobre un grupo = bloquearlo con el PIN)
 *  - buscador en tiempo real (busca en todos los grupos por nombre o grupo)
 *  - mantener OK sobre un elemento = añadir/quitar de favoritos
 *  - para series: lista de series → episodios
 */
class ContentActivity : AppCompatActivity() {

    enum class Section { LIVE, MOVIE, SERIES, FAVORITES }

    private lateinit var b: ActivityContentBinding
    private lateinit var section: Section
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var itemAdapter: ItemAdapter

    private var groups: List<String> = emptyList()   // índice 0 = "Todos"
    private var selectedGroup = 0
    private var query = ""
    private var openShow: SeriesShow? = null
    /** Búsqueda que había al abrir una serie desde los resultados (se recupera al volver). */
    private var queryBeforeShow: String? = null

    // Lo que se está mostrando (mismo orden que el adaptador): Channel o SeriesShow
    private var visibleEntries: List<Any> = emptyList()
    // Solo los canales visibles, en orden: es la cola del reproductor
    private var visibleChannels: List<Channel> = emptyList()

    private var filterJob: Job? = null
    private lateinit var prefs: Prefs

    /**
     * Modo Xtream en Películas/Series: el contenido se descarga por categorías.
     * El índice 0 muestra la lista de categorías y el resto, su contenido.
     */
    private var isLazy = false
    private var categories: List<Category> = emptyList()   // categories[i] = groups[i + 1]

    // ---- Temporadas de la serie abierta (sustituyen a los grupos en la columna)
    private var seasons: List<Int> = emptyList()
    private var selectedSeason = 0
    private var seasonsForShow: SeriesShow? = null
    private var seasonAdapter: GroupAdapter? = null

    // ---------------------------------------------------------------- Canales repetidos

    /** Varias calidades del mismo canal (La 1 SD / HD / FHD) mostradas en una sola fila. */
    private class ChannelGroup(val key: String, val name: String, val variants: List<Channel>, var chosen: Channel)

    /** Nombre sin calidad y calidad de cada canal (se calcula una vez). */
    private val nameParts = java.util.concurrent.ConcurrentHashMap<Channel, Pair<String, String>>()

    private fun partsOf(c: Channel): Pair<String, String> = nameParts.getOrPut(c) {
        val (n, q) = ChannelName.split(c.name)
        n to q.orEmpty()
    }

    /** Preferencia por defecto: FHD > 4K > HD > sin indicar > SD (el 4K va justo en un Fire Stick). */
    private fun qualityRank(q: String): Int {
        if (q.isEmpty()) return 15
        val t = q.split(' ')
        var r = 0
        for (x in t) r = maxOf(r, when (x) {
            "FHD", "FULLHD", "1080P", "1080I", "FHD+", "1080" -> 30
            "4K", "UHD", "2160P", "UHD+", "4K+", "8K", "2160" -> 25
            "HD", "HD+", "720P", "720", "HQ" -> 20
            "SD", "576P", "480P", "LQ" -> 10
            else -> 0
        })
        if ("HEVC" in t || "H265" in t || "H.265" in t || "X265" in t) r -= 1
        return if (r == 0) 15 else r
    }

    private fun qualityLabel(q: String) = q.ifEmpty { getString(R.string.quality_normal) }

    /** Junta los canales con el mismo nombre (sin calidad) dentro del mismo grupo. */
    private fun groupDuplicates(list: List<Channel>): FilterResult {
        val buckets = LinkedHashMap<String, MutableList<Channel>>()
        for (c in list) {
            val base = partsOf(c).first.uppercase().replace(Regex("\\s+"), " ").trim()
            buckets.getOrPut(c.group + "\u0000" + base) { ArrayList(2) }.add(c)
        }
        val entries = ArrayList<Any>(buckets.size)
        val channels = ArrayList<Channel>(buckets.size)
        val items = ArrayList<DisplayItem>(buckets.size)
        for ((key, vs) in buckets) {
            if (vs.size == 1) {
                val c = vs[0]
                entries.add(c); channels.add(c)
                items.add(DisplayItem(c.name, c.group, c.logo, PlaylistRepository.numberOf(c), Favorites.isFavorite(c)))
                continue
            }
            val sortedVs = vs.sortedByDescending { qualityRank(partsOf(it).second) }
            val pref = prefs.preferredQuality(key)
            val chosen = sortedVs.firstOrNull { pref != null && partsOf(it).second == pref } ?: sortedVs[0]
            val g = ChannelGroup(key, partsOf(chosen).first, sortedVs, chosen)
            entries.add(g); channels.add(chosen)
            val badge = sortedVs.joinToString(" · ") { qualityLabel(partsOf(it).second) }
            items.add(DisplayItem(g.name, chosen.group, chosen.logo, PlaylistRepository.numberOf(chosen),
                sortedVs.any { Favorites.isFavorite(it) }, badge))
        }
        return FilterResult(entries, channels, items)
    }

    /** Elegir calidad de un canal agrupado (la elegida se recuerda). */
    private fun chooseQuality(g: ChannelGroup) {
        val labels = g.variants.map { v ->
            val q = qualityLabel(partsOf(v).second)
            if (v === g.chosen) "$q  ✓" else q
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.quality_title, g.name))
            .setItems(labels) { _, which ->
                val v = g.variants[which]
                prefs.setPreferredQuality(g.key, partsOf(v).second)
                g.chosen = v
                val slot = visibleEntries.indexOf(g)
                val queue = visibleChannels.toMutableList()
                val qi = queue.indexOfFirst { it in g.variants }
                if (qi >= 0) queue[qi] = v
                visibleChannels = queue
                PlaylistRepository.playQueue = queue
                PlaylistRepository.playShow = null
                PlayerActivity.start(this, if (qi >= 0) qi else 0, isLive = true)
                if (slot >= 0) itemAdapter.itemAt(slot)?.let { itemAdapter.updateItem(slot, it.copy(logo = v.logo, number = PlaylistRepository.numberOf(v))) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Fila de la lista de categorías (modo Xtream). */
    private class CatEntry(val groupIndex: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PlaylistRepository.isLoaded) {
            MainActivity.open(this); finish(); return
        }
        Favorites.init(this)
        ParentalControl.init(this)
        WatchHistory.init(this)
        b = ActivityContentBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        section = runCatching { Section.valueOf(intent.getStringExtra(EXTRA_SECTION).orEmpty()) }
            .getOrDefault(Section.LIVE)
        isLazy = PlaylistRepository.isXtream && (section == Section.MOVIE || section == Section.SERIES)
        if (isLazy) {
            categories = if (section == Section.MOVIE) PlaylistRepository.vodCategories else PlaylistRepository.seriesCategories
        }
        setupLanguage()
        val firstGroup = getString(if (isLazy) R.string.group_categories else R.string.group_all)
        groups = listOf(firstGroup) + buildGroupList()

        selectedGroup = savedInstanceState?.getInt(STATE_GROUP, 0)?.coerceIn(0, groups.lastIndex) ?: 0
        savedInstanceState?.getString(STATE_SHOW)?.let { key ->
            openShow = (PlaylistRepository.shows + Favorites.shows()).firstOrNull { Favorites.keyOf(it) == key }
        }

        setupGroups()
        setupItems()
        setupSearch()
        // Si Android cerró esta pantalla mientras se veía un canal, se recupera la búsqueda
        savedInstanceState?.getString(STATE_QUERY)?.takeIf { it.isNotEmpty() }?.let { q ->
            query = q
            b.editSearch.setText(q)
        }
        setupRecent()
        setupSort()
        setupBack()
        refresh(immediate = true)
    }

    override fun onResume() {
        super.onResume()
        if (!::itemAdapter.isInitialized) return
        // Al volver del reproductor el cursor se coloca AL MOMENTO en el canal que se estaba
        // viendo, sin recargar la lista (antes se recargaba y el cursor "saltaba" al cabo de 1-2 s)
        val target = PlaylistRepository.lastPlayed
        PlaylistRepository.lastPlayed = null
        if (Favorites.version != favVersionSeen) {
            // Solo si se añadió/quitó algún favorito hay que repintar las estrellas
            focusAfterRefresh = target
            refresh(immediate = true, keepScroll = true)
        } else if (target != null) {
            focusAfterRefresh = target
            restoreFocus()
        }
    }

    /** Versión de favoritos con la que se pintó la lista por última vez. */
    private var favVersionSeen = -1

    /** Elemento que debe recibir el foco cuando termine de pintarse la lista. */
    private var focusAfterRefresh: Channel? = null

    /** [fallback]: si el canal ya no está en la lista, al menos dejar el foco en la lista. */
    private fun restoreFocus(fallback: Boolean = false) {
        val target = focusAfterRefresh ?: return
        focusAfterRefresh = null
        val i = visibleEntries.indexOfFirst {
            (it is Channel && it.url == target.url) || (it is ChannelGroup && it.variants.any { v -> v.url == target.url })
        }
        val lm = b.recyclerItems.layoutManager
        if (i < 0 && !fallback) return
        val pos = if (i >= 0) i else (lm as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        if (pos < 0 || pos >= itemAdapter.itemCount) return
        if (i >= 0) {
            if (lm is GridLayoutManager) lm.scrollToPosition(pos)
            else (lm as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, b.recyclerItems.height / 3)
        }
        b.recyclerItems.post {
            b.recyclerItems.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_GROUP, selectedGroup)
        outState.putString(STATE_QUERY, query)
        openShow?.let { outState.putString(STATE_SHOW, Favorites.keyOf(it)) }
    }

    // ---------------------------------------------------------------- Idioma

    /** Idioma elegido que existe en esta sección ("" = todos). */
    private var lang = ""
    /** Idiomas detectados en esta sección, con su número de grupos (mayor primero). */
    private var langCodes: List<Pair<String, Int>> = emptyList()

    private fun setupLanguage() {
        if (section == Section.FAVORITES) return
        val counts = HashMap<String, Int>()
        fun count(code: String?) { if (code != null) counts[code] = (counts[code] ?: 0) + 1 }
        if (isLazy) {
            categories.forEach { count(LangFilter.codeOf(it.name)) }
        } else {
            val seen = HashSet<String>()
            val noCode = HashSet<String>()
            when (section) {
                Section.LIVE -> PlaylistRepository.live.forEach { c -> seenGroup(c.group, c.name, seen, noCode, ::count) }
                Section.MOVIE -> PlaylistRepository.movies.forEach { c -> seenGroup(c.group, c.name, seen, noCode, ::count) }
                Section.SERIES -> PlaylistRepository.shows.forEach { sh -> seenGroup(sh.group, sh.name, seen, noCode, ::count) }
                Section.FAVORITES -> Unit
            }
        }
        langCodes = counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        val saved = prefs.langFilter
        lang = if (langCodes.any { it.first == saved }) saved else ""
        if (isLazy && lang.isNotEmpty()) categories = categories.filter { LangFilter.codeOf(it.name) == lang }

        b.btnLang.isVisible = langCodes.isNotEmpty()
        b.btnLang.text = getString(R.string.lang_button_fmt, if (lang.isEmpty()) getString(R.string.lang_all_short) else lang)
        b.btnLang.setOnClickListener { chooseLanguage() }
    }

    /** Cuenta el idioma de cada grupo (o, si el grupo no lo indica, de cada nombre). */
    private inline fun seenGroup(group: String, name: String, seen: HashSet<String>, noCode: HashSet<String>, count: (String?) -> Unit) {
        if (seen.add(group)) {
            val code = LangFilter.codeOf(group)
            if (code != null) count(code) else noCode.add(group)
        }
        if (group in noCode) {
            val code = LangFilter.codeOf(name) ?: return
            // Se cuenta una vez por grupo y código
            if (seen.add(group + "\u0000" + code)) count(code)
        }
    }

    private fun chooseLanguage() {
        val codes = listOf("") + langCodes.map { it.first }
        val labels = codes.map { c ->
            if (c.isEmpty()) getString(R.string.lang_all)
            else "$c  ·  " + LangFilter.label(c)
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(labels, codes.indexOf(lang).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                if (codes[which] != lang) {
                    prefs.langFilter = codes[which]
                    // Se vuelve a abrir la sección desde el principio con el nuevo filtro
                    start(this, section)
                    finish()
                    overridePendingTransition(0, 0)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildGroupList(): List<String> {
        if (section == Section.FAVORITES) {
            return listOf(
                getString(R.string.section_live),
                getString(R.string.section_movies),
                getString(R.string.section_series),
            )
        }
        if (isLazy) return categories.map { it.name }
        val set = LinkedHashSet<String>()
        val m = LangMatcher(lang)
        when (section) {
            Section.LIVE -> PlaylistRepository.live.forEach { if (m.ok(it.group, it.name)) set.add(it.group) }
            Section.MOVIE -> PlaylistRepository.movies.forEach { if (m.ok(it.group, it.name)) set.add(it.group) }
            Section.SERIES -> PlaylistRepository.shows.forEach { if (m.ok(it.group, it.name)) set.add(it.group) }
            Section.FAVORITES -> Unit
        }
        return set.toList()
    }

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun locked(group: String) = section != Section.FAVORITES && ParentalControl.isLocked(group)

    // ---------------------------------------------------------------- Grupos

    private fun setupGroups() {
        val horizontal = !isLandscape
        groupAdapter = GroupAdapter(
            groups = groups,
            horizontal = horizontal,
            isLocked = { name -> section != Section.FAVORITES && ParentalControl.isProtected(name) },
            onClick = { index -> onGroupClick(index) },
            onLongClick = { index -> onGroupLongClick(index) },
        )
        groupAdapter.setSelected(selectedGroup)
        b.recyclerGroups.layoutManager = LinearLayoutManager(
            this,
            if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL,
            false
        )
        b.recyclerGroups.adapter = groupAdapter
        b.recyclerGroups.scrollToPosition(selectedGroup)
    }

    private fun onGroupClick(index: Int) {
        if (index > 0 && locked(groups[index])) {
            PinDialogs.ask(this) {
                ParentalControl.sessionUnlocked = true
                groupAdapter.refreshAll()
                selectGroup(index)
            }
            return
        }
        selectGroup(index)
    }

    private fun selectGroup(index: Int) {
        if (index == selectedGroup && openShow == null && query.isEmpty()) return
        selectedGroup = index
        groupAdapter.setSelected(index)
        openShow = null
        if (query.isNotEmpty()) b.editSearch.setText("") // dispara refresh
        else refresh(immediate = true)
    }

    /** Mantener OK sobre un grupo: bloquearlo o desbloquearlo (pide el PIN). */
    private fun onGroupLongClick(index: Int) {
        if (section == Section.FAVORITES) return
        if (!ParentalControl.hasPin) {
            Toast.makeText(this, R.string.parental_need_pin, Toast.LENGTH_LONG).show()
            return
        }
        val group = groups[index]
        PinDialogs.ask(this) {
            val lock = !ParentalControl.isManuallyLocked(group)
            ParentalControl.setGroupLocked(group, lock)
            Toast.makeText(
                this,
                if (lock) R.string.group_locked else R.string.group_unlocked,
                Toast.LENGTH_LONG
            ).show()
            groupAdapter.refreshAll()
            refresh(immediate = true, keepScroll = true)
        }
    }

    // ---------------------------------------------------------------- Contenido

    private fun setupItems() {
        itemAdapter = ItemAdapter(
            onClick = { position -> onItemClick(position) },
            onLongClick = { position -> toggleFavorite(position) },
        )
        b.recyclerItems.adapter = itemAdapter
        b.recyclerItems.setHasFixedSize(true)
        b.recyclerItems.setItemViewCacheSize(20)
    }

    /** Cuadrícula de carátulas para películas y series; lista para TV, episodios y favoritos. */
    private fun usePosterMode(): Boolean =
        section == Section.MOVIE || (section == Section.SERIES && openShow == null)

    private fun applyLayoutManager(poster: Boolean) {
        val lm = b.recyclerItems.layoutManager
        if (poster) {
            val panel = if (isLandscape) resources.getDimension(R.dimen.groups_width) / resources.displayMetrics.density else 0f
            val availableDp = resources.configuration.screenWidthDp - panel - 32
            val span = (availableDp / resources.getInteger(R.integer.poster_cell_dp)).toInt().coerceAtLeast(2)
            if (lm !is GridLayoutManager || lm.spanCount != span) {
                b.recyclerItems.layoutManager = GridLayoutManager(this, span)
            }
        } else if (lm == null || lm is GridLayoutManager) {
            b.recyclerItems.layoutManager = LinearLayoutManager(this)
        }
    }

    private class FilterResult(
        val entries: List<Any>,
        val channels: List<Channel>,
        val items: List<DisplayItem>,
    )

    private fun refresh(immediate: Boolean = false, keepScroll: Boolean = false) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            if (openShow == null) restoreGroups()
            if (!immediate) delay(220) // pequeño "debounce" mientras se escribe
            val q = query.trim()
            // En modo Xtream la búsqueda se hace dentro de la categoría abierta
            val groupIndex = if (q.isNotEmpty() && !isLazy) 0 else selectedGroup
            val groupName = if (groupIndex == 0) null else groups[groupIndex]
            val show = openShow
            val sort = prefs.sortMode
            // Xtream: al escribir se busca por título en TODO el catálogo (no solo en la categoría)
            val globalSearch = isLazy && show == null && q.length >= MIN_GLOBAL_QUERY
            val showCategories = isLazy && show == null && groupIndex == 0 && q.isEmpty()
            val poster = usePosterMode() && !showCategories
            updateSearchHint()

            if (isLazy && show == null && q.isNotEmpty() && !globalSearch) {
                showMessage(getString(R.string.search_min_chars, MIN_GLOBAL_QUERY))
                return@launch
            }

            // Contenido que hay que descargar antes (Xtream: categoría o episodios de una serie)
            var lazyMovies: List<Channel>? = null
            var lazyShows: List<SeriesShow>? = null
            var episodes: List<Channel> = emptyList()
            try {
                val indexReady = if (section == Section.MOVIE) PlaylistRepository.isMovieIndexReady() else PlaylistRepository.isSeriesIndexReady()
                val needsNetwork = (show != null && show.episodes.isEmpty()) ||
                    (isLazy && show == null && !globalSearch && groupIndex > 0) ||
                    (globalSearch && !indexReady)
                if (globalSearch && !indexReady) {
                    showLoading(poster, getString(R.string.search_index_building))
                    PlaylistRepository.indexProgress = { n ->
                        runOnUiThread {
                            b.textEmpty.text = getString(
                                R.string.search_index_progress,
                                String.format(Locale("es", "ES"), "%,d", n)
                            )
                        }
                    }
                } else if (needsNetwork) {
                    showLoading(poster)
                }
                if (globalSearch) {
                    if (section == Section.MOVIE) lazyMovies = PlaylistRepository.movieIndex(this@ContentActivity)
                    else lazyShows = PlaylistRepository.seriesIndex(this@ContentActivity)
                } else if (show != null) {
                    episodes = PlaylistRepository.episodesOf(show)
                } else if (isLazy && groupIndex > 0) {
                    val cat = categories[groupIndex - 1]
                    if (section == Section.MOVIE) lazyMovies = PlaylistRepository.moviesOf(cat)
                    else lazyShows = PlaylistRepository.showsOf(cat)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoadError(e)
                return@launch
            }

            // Serie abierta: la columna de grupos pasa a mostrar sus temporadas
            if (show != null && seasonsForShow !== show) setupSeasons(show, episodes)
            val seasonFilter = if (show != null && q.isEmpty() && seasons.size > 1) seasons.getOrNull(selectedSeason) else null

            // Copias inmutables para usarlas dentro del hilo de filtrado
            val loadedMovies = lazyMovies
            val loadedShows = lazyShows
            val loadedEpisodes = episodes

            val langM = LangMatcher(lang)
            val groupDupes = prefs.groupDuplicates
            val result = withContext(Dispatchers.Default) {
                when {
                    showCategories -> {
                        val idx = categories.indices.filter { categories[it].name.contains(q, ignoreCase = true) }
                        val ordered = if (sort == "name") {
                            val collator = Collator.getInstance(Locale("es", "ES")).apply { strength = Collator.PRIMARY }
                            idx.sortedWith { a, b2 -> collator.compare(categories[a].name, categories[b2].name) }
                        } else idx
                        FilterResult(ordered.map { CatEntry(it + 1) }, emptyList(), ordered.map { i ->
                            val name = categories[i].name
                            DisplayItem(if (locked(name)) "🔒  $name" else name, null, null, null)
                        })
                    }

                    show != null -> {
                        val eps = loadedEpisodes.filter {
                            it.matchesQuery(q) && (seasonFilter == null || Seasons.of(it.name) == seasonFilter)
                        }
                        FilterResult(eps, eps, eps.mapIndexed { i, c ->
                            DisplayItem(c.name, null, c.logo ?: show.logo, i + 1, Favorites.isFavorite(c))
                        })
                    }

                    loadedShows != null -> {
                        val list = sorted(loadedShows.filter {
                            !locked(it.group) && it.name.contains(q, ignoreCase = true) && (!globalSearch || langM.ok(it.group, it.name))
                        }, sort, { it.name }, { it.group })
                        FilterResult(list, emptyList(), list.map {
                            DisplayItem(it.name, if (globalSearch) it.group else null, it.logo, null, Favorites.isFavorite(it))
                        })
                    }

                    loadedMovies != null -> {
                        val list = sorted(loadedMovies.filter {
                            !locked(it.group) && it.name.contains(q, ignoreCase = true) && (!globalSearch || langM.ok(it.group, it.name))
                        }, sort, { it.name }, { it.group })
                        FilterResult(list, list, list.map { c ->
                            DisplayItem(c.name, if (globalSearch) c.group else null, c.logo, null, Favorites.isFavorite(c))
                        })
                    }

                    section == Section.SERIES -> {
                        val list = sorted(PlaylistRepository.shows.filter {
                            (groupName == null || it.group == groupName) && !locked(it.group) && it.matchesQuery(q) && langM.ok(it.group, it.name)
                        }, sort, { it.name }, { it.group })
                        FilterResult(list, emptyList(), list.map {
                            DisplayItem(it.name, getString(R.string.episodes_fmt, it.episodes.size), it.logo, null, Favorites.isFavorite(it))
                        })
                    }

                    section == Section.FAVORITES -> buildFavorites(groupIndex, sort, q)

                    else -> {
                        val src = if (section == Section.LIVE) PlaylistRepository.live else PlaylistRepository.movies
                        val list = sorted(src.filter {
                            (groupName == null || it.group == groupName) && !locked(it.group) && it.matchesQuery(q) && langM.ok(it.group, it.name)
                        }, sort, { it.name }, { it.group })
                        val numbered = section == Section.LIVE
                        if (numbered && groupDupes) groupDuplicates(list) else
                        FilterResult(list, list, list.map { c ->
                            // El número es fijo (el del proveedor) aunque se ordene A-Z o por grupo
                            DisplayItem(c.name, c.group, c.logo, if (numbered) PlaylistRepository.numberOf(c) else null, Favorites.isFavorite(c))
                        })
                    }
                }
            }

            b.btnSort.isVisible = show == null
            visibleEntries = result.entries
            visibleChannels = result.channels
            applyLayoutManager(poster)
            itemAdapter.submit(result.items, poster)
            if (!keepScroll) b.recyclerItems.scrollToPosition(0)
            updateHeader(result.items.size)
            b.textEmpty.text = getString(
                if (section == Section.FAVORITES && q.isEmpty()) R.string.empty_favorites else R.string.empty_results
            )
            b.textEmpty.isVisible = result.items.isEmpty()
            favVersionSeen = Favorites.version
            restoreFocus(fallback = true)
        }
    }

    /** Favoritos: 0 = todos, 1 = TV en vivo, 2 = películas, 3 = series. Respeta el control parental. */
    private fun buildFavorites(category: Int, sort: String, q: String): FilterResult {
        val wantLive = category == 0 || category == 1
        val wantMovies = category == 0 || category == 2
        val wantSeries = category == 0 || category == 3
        val okGroup = { g: String -> !ParentalControl.isLocked(g) }

        // Los favoritos se guardan con todos sus datos: no dependen de qué categorías estén cargadas
        val favShows = if (wantSeries) sorted(Favorites.shows().filter {
            okGroup(it.group) && it.matchesQuery(q)
        }, sort, { it.name }, { it.group }) else emptyList()

        val favChannels = sorted(Favorites.channels().filter { c ->
            val wanted = when (c.type) {
                ContentType.LIVE -> wantLive
                ContentType.MOVIE -> wantMovies
                ContentType.SERIES -> wantSeries
            }
            wanted && okGroup(c.group) && c.matchesQuery(q)
        }, sort, { it.name }, { it.group })

        val liveLabel = getString(R.string.section_live)
        val movieLabel = getString(R.string.section_movies)
        val seriesLabel = getString(R.string.section_series)
        val items = ArrayList<DisplayItem>()
        favShows.forEach {
            items.add(DisplayItem(it.name, "$seriesLabel · ${it.group}", it.logo, null, true))
        }
        // Canales en vivo: las distintas calidades del mismo canal, en una sola fila
        if (prefs.groupDuplicates) {
            val live = favChannels.filter { it.type == ContentType.LIVE }
            val others = favChannels.filter { it.type != ContentType.LIVE }
            val grouped = groupDuplicates(live)
            val entries = ArrayList<Any>()
            entries.addAll(favShows)
            entries.addAll(grouped.entries)
            entries.addAll(others)
            grouped.items.forEach { items.add(it.copy(subtitle = "$liveLabel · ${it.subtitle.orEmpty()}")) }
            others.forEach { c ->
                val label = if (c.type == ContentType.MOVIE) movieLabel else seriesLabel
                items.add(DisplayItem(c.name, "$label · ${c.group}", c.logo, null, true))
            }
            return FilterResult(entries, grouped.channels + others, items)
        }
        favChannels.forEach { c ->
            val label = when (c.type) {
                ContentType.LIVE -> liveLabel
                ContentType.MOVIE -> movieLabel
                ContentType.SERIES -> seriesLabel
            }
            items.add(DisplayItem(c.name, "$label · ${c.group}", c.logo, PlaylistRepository.numberOf(c), true))
        }
        val entries = ArrayList<Any>(favShows.size + favChannels.size)
        entries.addAll(favShows)
        entries.addAll(favChannels)
        return FilterResult(entries, favChannels, items)
    }

    // ---------------------------------------------------------------- Ordenar

    /**
     * "number": orden original del proveedor (ya viene así de la lista)
     * "name":   alfabético, ignorando mayúsculas y acentos
     * "group":  por grupos en el orden del proveedor; dentro de cada grupo, orden original
     */
    private fun <T> sorted(list: List<T>, mode: String, name: (T) -> String, group: (T) -> String): List<T> =
        when (mode) {
            "name" -> {
                val collator = Collator.getInstance(Locale("es", "ES")).apply { strength = Collator.PRIMARY }
                list.sortedWith { a, b -> collator.compare(name(a), name(b)) }
            }
            "group" -> {
                val order = PlaylistRepository.groupOrder
                list.sortedBy { order[group(it)] ?: Int.MAX_VALUE } // sortedBy es estable
            }
            else -> list
        }

    private fun sortLabel(mode: String): String = getString(
        when (mode) {
            "name" -> R.string.sort_name
            "group" -> R.string.sort_group
            else -> R.string.sort_number
        }
    )

    private fun setupSort() {
        b.btnSort.text = getString(R.string.sort_button_fmt, sortLabel(prefs.sortMode))
        b.btnSort.setOnClickListener {
            val modes = arrayOf("number", "name", "group")
            val labels = arrayOf(
                getString(R.string.sort_number_long),
                getString(R.string.sort_name_long),
                getString(R.string.sort_group_long),
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort_title)
                .setSingleChoiceItems(labels, modes.indexOf(prefs.sortMode).coerceAtLeast(0)) { dialog, which ->
                    prefs.sortMode = modes[which]
                    b.btnSort.text = getString(R.string.sort_button_fmt, sortLabel(modes[which]))
                    dialog.dismiss()
                    refresh(immediate = true)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private var lastHintRes = 0

    private fun updateSearchHint() {
        val res = when {
            !isLazy -> R.string.search_hint
            section == Section.MOVIE -> R.string.search_hint_movies
            else -> R.string.search_hint_series
        }
        if (res != lastHintRes) {
            lastHintRes = res
            b.editSearch.hint = getString(res)
        }
    }

    private fun showLoading(poster: Boolean, text: String = getString(R.string.loading_content)) {
        visibleEntries = emptyList()
        visibleChannels = emptyList()
        applyLayoutManager(poster)
        itemAdapter.submit(emptyList(), poster)
        b.textCount.text = "…"
        b.textEmpty.text = text
        b.textEmpty.isVisible = true
    }

    private fun showMessage(text: String) {
        visibleEntries = emptyList()
        visibleChannels = emptyList()
        itemAdapter.submit(emptyList(), false)
        b.textCount.text = "0"
        b.textEmpty.text = text
        b.textEmpty.isVisible = true
    }

    private fun showLoadError(e: Exception) {
        visibleEntries = emptyList()
        visibleChannels = emptyList()
        itemAdapter.submit(emptyList(), false)
        b.textCount.text = "0"
        b.textEmpty.text = if (e is PlaylistException) e.message
        else getString(R.string.err_network, e.message ?: e.javaClass.simpleName)
        b.textEmpty.isVisible = true
    }

    private fun updateHeader(count: Int) {
        val title = when (section) {
            Section.LIVE -> getString(R.string.section_live)
            Section.MOVIE -> getString(R.string.section_movies)
            Section.SERIES -> getString(R.string.section_series)
            Section.FAVORITES -> getString(R.string.section_favorites)
        }
        val seasonLabel = if (openShow != null && seasons.size > 1 && query.isEmpty())
            seasons.getOrNull(selectedSeason)?.let { "  ›  " + seasonName(it) }.orEmpty() else ""
        b.textTitle.text = openShow?.let { "$title › ${it.name}$seasonLabel" } ?: title
        b.textCount.text = String.format(Locale("es", "ES"), "%,d", count)
    }

    private fun onItemClick(position: Int) {
        when (val entry = visibleEntries.getOrNull(position)) {
            is CatEntry -> {
                b.recyclerGroups.scrollToPosition(entry.groupIndex)
                onGroupClick(entry.groupIndex)
            }
            is SeriesShow -> {
                if (query.isNotBlank()) { prefs.addRecentSearch(section.name, query); updateRecent() }
                if (query.isNotEmpty()) queryBeforeShow = query
                openShow = entry
                hideKeyboard()
                if (query.isNotEmpty()) b.editSearch.setText("") else refresh(immediate = true)
            }
            is ChannelGroup -> {
                if (query.isNotBlank()) { prefs.addRecentSearch(section.name, query); updateRecent() }
                chooseQuality(entry)
            }
            is Channel -> {
                val index = visibleChannels.indexOf(entry)
                if (index < 0) return
                if (query.isNotBlank()) { prefs.addRecentSearch(section.name, query); updateRecent() }
                // Películas de cuentas Xtream: primero la ficha (sinopsis, duración, nota…)
                if (entry.type == ContentType.MOVIE && PlaylistRepository.isXtream) {
                    PlaylistRepository.detailQueue = visibleChannels
                    DetailActivity.start(this, index)
                    return
                }
                PlaylistRepository.playQueue = visibleChannels
                PlaylistRepository.playShow = openShow?.let { ShowRef(it.name, it.logo) }
                PlayerActivity.start(this, index, isLive = entry.type == ContentType.LIVE)
            }
        }
    }

    // ---------------------------------------------------------------- Temporadas

    private fun seasonName(n: Int) = if (n == 0) getString(R.string.season_other) else getString(R.string.season_fmt, n)

    /** Al abrir una serie con varias temporadas, la columna izquierda las muestra. */
    private fun setupSeasons(show: SeriesShow, episodes: List<Channel>) {
        seasonsForShow = show
        seasons = episodes.map { Seasons.of(it.name) }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
        // Empieza en la temporada del último episodio visto (si lo hay)
        val lastSeen = WatchHistory.entries().firstOrNull { it.show?.name == show.name }?.channel?.name
        selectedSeason = lastSeen?.let { seasons.indexOf(Seasons.of(it)) }?.takeIf { it >= 0 } ?: 0
        if (seasons.size < 2) return
        val adapter = GroupAdapter(
            groups = seasons.map { seasonName(it) },
            horizontal = !isLandscape,
            isLocked = { false },
            onClick = { index -> selectSeason(index) },
            onLongClick = { },
        )
        adapter.setSelected(selectedSeason)
        seasonAdapter = adapter
        b.recyclerGroups.adapter = adapter
        b.recyclerGroups.scrollToPosition(selectedSeason)
    }

    private fun selectSeason(index: Int) {
        if (index == selectedSeason) return
        selectedSeason = index
        seasonAdapter?.setSelected(index)
        refresh(immediate = true)
    }

    /** Al cerrar la serie vuelve la lista de grupos normal. */
    private fun restoreGroups() {
        if (seasonsForShow == null) return
        seasonsForShow = null
        seasons = emptyList()
        seasonAdapter = null
        if (b.recyclerGroups.adapter !== groupAdapter) {
            b.recyclerGroups.adapter = groupAdapter
            b.recyclerGroups.scrollToPosition(selectedGroup)
        }
    }

    /** Mantener OK: añadir o quitar de favoritos. */
    private fun toggleFavorite(position: Int) {
        val nowFav = when (val entry = visibleEntries.getOrNull(position)) {
            is Channel -> Favorites.toggle(entry)
            // Canal agrupado: entran (o salen) de favoritos TODAS sus calidades
            is ChannelGroup -> Favorites.setAll(entry.variants, !entry.variants.any { Favorites.isFavorite(it) })
            is SeriesShow -> Favorites.toggle(entry)
            else -> return
        }
        Toast.makeText(
            this,
            if (nowFav) R.string.fav_added else R.string.fav_removed,
            Toast.LENGTH_SHORT
        ).show()
        if (section == Section.FAVORITES && openShow == null) {
            refresh(immediate = true, keepScroll = true)
        } else {
            itemAdapter.itemAt(position)?.let { itemAdapter.updateItem(position, it.copy(favorite = nowFav)) }
        }
    }

    // ---------------------------------------------------------------- Búsquedas recientes

    /** Muestra las últimas búsquedas debajo del buscador mientras se está en él. */
    private fun setupRecent() {
        b.editSearch.setOnFocusChangeListener { _, _ -> b.root.post { updateRecent() } }
        updateRecent()
    }

    private var recentShown: List<String> = emptyList()

    private fun updateRecent() {
        val recent = prefs.recentSearches(section.name)
        if (recent != recentShown) {
            recentShown = recent
            // Se conserva la etiqueta "Recientes:" (primer hijo) y se rehacen los botones
            while (b.rowRecent.childCount > 1) b.rowRecent.removeViewAt(1)
            recent.forEach { q ->
                val chip = ItemRecentChipBinding.inflate(layoutInflater, b.rowRecent, false).root
                chip.text = q
                chip.setOnClickListener {
                    b.editSearch.setText(q)
                    b.editSearch.setSelection(q.length)
                    hideKeyboard()
                    b.rowRecent.isVisible = false
                    b.recyclerItems.postDelayed({ b.recyclerItems.requestFocus() }, 350)
                }
                chip.setOnFocusChangeListener { _, _ -> b.root.post { updateRecent() } }
                b.rowRecent.addView(chip)
            }
        }
        val inSearch = b.editSearch.hasFocus() || b.rowRecent.hasFocus()
        b.rowRecent.isVisible = recent.isNotEmpty() && query.isEmpty() && inSearch
    }

    // ---------------------------------------------------------------- Búsqueda

    private fun setupSearch() {
        b.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val newQuery = s?.toString().orEmpty()
                if (newQuery == query) return
                query = newQuery
                updateRecent()
                refresh(immediate = newQuery.isEmpty())
            }
        })
        b.editSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                hideKeyboard()
                b.recyclerItems.requestFocus()
                true
            } else false
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.editSearch.windowToken, 0)
    }

    // ---------------------------------------------------------------- Atrás

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    openShow != null -> {          // episodios → volver a la lista de series (o a la búsqueda)
                        openShow = null
                        val previous = queryBeforeShow
                        queryBeforeShow = null
                        when {
                            previous != null -> b.editSearch.setText(previous)
                            query.isNotEmpty() -> b.editSearch.setText("")
                            else -> refresh(immediate = true)
                        }
                    }
                    query.isNotEmpty() -> b.editSearch.setText("")
                    isLazy && selectedGroup > 0 -> {      // categoría → volver a la lista de categorías
                        selectGroup(0)
                        b.recyclerGroups.scrollToPosition(0)
                    }
                    else -> finish()
                }
            }
        })
    }

    override fun onDestroy() {
        PlaylistRepository.indexProgress = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SECTION = "extra_section"
        private const val STATE_GROUP = "state_group"
        private const val STATE_SHOW = "state_show"
        private const val STATE_QUERY = "state_query"
        private const val MIN_GLOBAL_QUERY = 2

        fun start(ctx: Context, section: Section) {
            ctx.startActivity(Intent(ctx, ContentActivity::class.java).putExtra(EXTRA_SECTION, section.name))
        }
    }
}
