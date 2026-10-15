package com.ale.cineiptv.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Carga, valida, clasifica y guarda en memoria la lista activa.
 *
 * Dos modos:
 *  - M3U: se descarga la lista entera y se clasifica en vivo / películas / series.
 *  - Xtream (API): se cargan solo los canales en vivo y las categorías; las películas
 *    y series de cada categoría se descargan al abrirla. Funciona igual de fluido con
 *    catálogos de millones de títulos.
 * La última lista se guarda en disco para abrir la app al instante.
 */
object PlaylistRepository {

    sealed class Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress()
        data class Parsing(val count: Int) : Progress()
        /** Pasos de la carga Xtream: [STEP_LOGIN], [STEP_CATEGORIES], [STEP_LIVE]. */
        data class Step(val step: Int) : Progress()
    }

    const val STEP_LOGIN = 1
    const val STEP_CATEGORIES = 2
    const val STEP_LIVE = 3

    @Volatile var live: List<Channel> = emptyList(); private set
    @Volatile var movies: List<Channel> = emptyList(); private set
    @Volatile var shows: List<SeriesShow> = emptyList(); private set

    /** Número fijo de cada canal de TV (su posición en la lista del proveedor, desde 1). */
    @Volatile private var liveNumbers: java.util.IdentityHashMap<Channel, Int> = java.util.IdentityHashMap()

    /** Orden de los grupos tal como aparecen en la lista del proveedor. */
    @Volatile var groupOrder: Map<String, Int> = emptyMap(); private set

    fun numberOf(ch: Channel): Int? = if (ch.number > 0) ch.number else liveNumbers[ch]

    // ---------------------------------------------------------------- Estado Xtream

    /** Cuenta Xtream activa; null = modo M3U. */
    @Volatile var xtream: XtreamCreds? = null; private set
    @Volatile private var xtreamUa: String = Network.DEFAULT_UA
    @Volatile var account: XtreamAccount? = null; private set
    @Volatile var vodCategories: List<Category> = emptyList(); private set
    @Volatile var seriesCategories: List<Category> = emptyList(); private set

    val isXtream: Boolean get() = xtream != null

    /** Últimas categorías abiertas, en memoria (para volver a ellas sin esperar). */
    private val vodCache = lruCache<List<Channel>>(8)
    private val seriesCache = lruCache<List<SeriesShow>>(8)

    private fun <V> lruCache(max: Int) = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    /** Lista que el reproductor recorre (para zapping canal arriba/abajo). */
    @Volatile var playQueue: List<Channel> = emptyList()
    /** Serie de los episodios de [playQueue] (null si son películas o canales). */
    @Volatile var playShow: ShowRef? = null
    /** Último canal/vídeo reproducido (al volver a la lista, el cursor se coloca en él). */
    @Volatile var lastPlayed: Channel? = null
    /** Películas de la ficha abierta (para pasar a la siguiente desde el reproductor). */
    @Volatile var detailQueue: List<Channel> = emptyList()

    val isLoaded: Boolean
        get() = live.isNotEmpty() || movies.isNotEmpty() || shows.isNotEmpty() ||
            vodCategories.isNotEmpty() || seriesCategories.isNotEmpty()

    /** true si la lista no cabía en memoria y se cargó solo la TV en vivo. */
    @Volatile var loadedLiveOnly = false; private set

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "playlist_cache.m3u")
    /** Copia ya procesada de la lista: se abre en un instante sin volver a analizar el M3U. */
    private fun binFile(ctx: Context) = File(ctx.filesDir, "playlist_cache.bin")
    private fun tempFile(ctx: Context) = File(ctx.cacheDir, "playlist_download.tmp")
    /** Datos de la cuenta Xtream y sus categorías (si la última lista fue Xtream). */
    private fun xtreamFile(ctx: Context) = File(ctx.filesDir, "xtream_cache.json")

    fun hasCache(ctx: Context): Boolean =
        cacheFile(ctx).let { it.exists() && it.length() > 0 } ||
            (xtreamFile(ctx).exists() && binFile(ctx).exists())

    /**
     * Actualiza los datos de la suscripción (caducidad, conexiones) para mostrarlos en el menú.
     * Funciona también con listas M3U de servidores Xtream: la cuenta se deduce de la
     * dirección de la lista o de la de cualquier canal. Si no se puede, no hace nada.
     */
    suspend fun refreshAccount(m3uUrl: String, ua: String): XtreamAccount? = withContext(Dispatchers.IO) {
        val candidates = LinkedHashSet<XtreamCreds>()
        xtream?.let { candidates.add(it) }
        XtreamCreds.fromGetPhpUrl(m3uUrl)?.let { candidates.add(it) }
        (live.firstOrNull() ?: movies.firstOrNull())?.let { ch ->
            XtreamCreds.fromStreamUrl(ch.url)?.let { candidates.add(it) }
        }
        for (c in candidates) {
            val acc = runCatching { XtreamApi.login(c, if (c == xtream) xtreamUa else ua, strict = false).account }.getOrNull()
            if (acc != null) { account = acc; return@withContext acc }
        }
        account
    }

    fun clear() {
        live = emptyList(); movies = emptyList(); shows = emptyList(); playQueue = emptyList()
        liveNumbers = java.util.IdentityHashMap(); groupOrder = emptyMap()
        xtream = null; account = null; vodCategories = emptyList(); seriesCategories = emptyList()
        synchronized(vodCache) { vodCache.clear() }
        synchronized(seriesCache) { seriesCache.clear() }
        synchronized(indexLock) {
            movieIndexJob?.cancel(); movieIndexJob = null
            seriesIndexJob?.cancel(); seriesIndexJob = null
        }
    }

    /** Descarga desde URL (M3U directa o Xtream get.php). [onProgress] puede llamarse desde otro hilo. */
    suspend fun loadFromUrl(ctx: Context, url: String, userAgent: String, onProgress: (Progress) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            // ¿Es en realidad un Xtream (…/get.php?username=…&password=…)? → API, mucho más rápida
            XtreamCreds.fromGetPhpUrl(url)?.let { creds ->
                try {
                    loadXtream(ctx, creds, userAgent, onProgress)
                    return@withContext
                } catch (e: NotXtreamException) {
                    // El servidor no tiene API: seguimos como lista M3U normal
                }
            }
            val request = try {
                Request.Builder().url(url).header("User-Agent", userAgent).build()
            } catch (e: IllegalArgumentException) {
                throw PlaylistException("La URL no es válida. Debe empezar por http:// o https://")
            }
            val tmp = tempFile(ctx)
            Network.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw PlaylistException(Network.httpErrorMessage(resp.code))
                val body = resp.body ?: throw PlaylistException("El servidor devolvió una respuesta vacía.")
                val total = body.contentLength()
                body.byteStream().use { input -> copyWithProgress(input, tmp, total, onProgress) }
            }
            parseAndCommit(ctx, tmp, onProgress)
        }

    /** Carga desde un archivo elegido con el selector del sistema. */
    suspend fun loadFromUri(ctx: Context, uri: Uri, onProgress: (Progress) -> Unit) =
        withContext(Dispatchers.IO) {
            val tmp = tempFile(ctx)
            val opened: InputStream? = try {
                ctx.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            val input = opened ?: throw PlaylistException("No se pudo abrir el archivo. Vuelve a seleccionarlo.")
            input.use { copyWithProgress(it, tmp, -1, onProgress) }
            parseAndCommit(ctx, tmp, onProgress)
        }

    /** Abre la última lista guardada sin conexión a Internet (usa la copia procesada si existe). */
    suspend fun loadFromCache(ctx: Context, onProgress: (Progress) -> Unit) =
        withContext(Dispatchers.IO) {
            if (xtreamFile(ctx).exists()) {
                loadXtreamCache(ctx)
                return@withContext
            }
            val file = cacheFile(ctx)
            if (!file.exists()) throw PlaylistException("No hay ninguna lista guardada.")
            val bin = binFile(ctx)
            if (bin.exists() && bin.lastModified() >= file.lastModified()) {
                clear()
                val fast = readBin(bin)
                if (fast != null && fast.isNotEmpty()) {
                    commit(fast)
                    return@withContext
                }
            }
            val channels = parseFile(file, onProgress)
            if (channels.isEmpty()) throw PlaylistException("La lista guardada está vacía.")
            writeBinSafe(bin, channels)
            commit(channels)
        }

    /**
     * Analiza el M3U. Si no cabe en memoria (listas Xtream con decenas de miles de
     * películas y episodios), lo reintenta quedándose solo con la TV en vivo.
     */
    private fun parseFile(file: File, onProgress: (Progress) -> Unit): List<Channel> {
        clear() // libera la lista anterior antes de cargar la nueva
        loadedLiveOnly = false
        return try {
            file.inputStream().use { M3uParser.parse(it, liveOnly = false) { n -> onProgress(Progress.Parsing(n)) } }
        } catch (e: OutOfMemoryError) {
            System.gc()
            loadedLiveOnly = true
            try {
                file.inputStream().use { M3uParser.parse(it, liveOnly = true) { n -> onProgress(Progress.Parsing(n)) } }
            } catch (e2: OutOfMemoryError) {
                throw PlaylistException("La lista es demasiado grande para la memoria de este dispositivo, incluso cargando solo la TV en vivo.")
            }
        }
    }

    // ---------------------------------------------------------------- Caché binaria

    private const val BIN_MAGIC = 0x52545633 // "RTV3"

    private fun writeBinSafe(file: File, list: List<Channel>) {
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file), 256 * 1024)).use { o ->
                o.writeInt(BIN_MAGIC)
                o.writeBoolean(loadedLiveOnly)
                o.writeInt(list.size)
                for (c in list) {
                    o.writeStr(c.name); o.writeStr(c.url); o.writeStr(c.group)
                    o.writeStr(c.logo); o.writeStr(c.userAgent); o.writeStr(c.referrer)
                    o.writeByte(c.type.ordinal)
                    o.writeInt(c.number)
                }
            }
        } catch (t: Throwable) {
            file.delete() // no es imprescindible: la próxima vez se analizará el M3U
        }
    }

    private fun readBin(file: File): List<Channel>? = try {
        DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { i ->
            if (i.readInt() != BIN_MAGIC) return null
            val liveOnly = i.readBoolean()
            val n = i.readInt()
            if (n < 0 || n > 5_000_000) return null
            val types = ContentType.values()
            val pool = HashMap<String, String>()
            val list = ArrayList<Channel>(n)
            repeat(n) {
                val name = i.readStr()!!
                val url = i.readStr()!!
                val group = i.readStr()!!.let { g -> pool.getOrPut(g) { g } }
                val logo = i.readStr()
                val ua = i.readStr()?.let { u -> pool.getOrPut(u) { u } }
                val ref = i.readStr()?.let { r -> pool.getOrPut(r) { r } }
                val type = types[i.readByte().toInt().coerceIn(0, types.lastIndex)]
                val number = i.readInt()
                list.add(Channel(name, url, group, logo, ua, ref, type, number))
            }
            loadedLiveOnly = liveOnly
            list
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun DataOutputStream.writeStr(s: String?) {
        if (s == null) { writeInt(-1); return }
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b)
    }

    private fun DataInputStream.readStr(): String? {
        val len = readInt()
        if (len < 0) return null
        val b = ByteArray(len)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private suspend fun copyWithProgress(input: InputStream, out: File, total: Long, onProgress: (Progress) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastReport = 0L
        out.outputStream().use { os ->
            while (true) {
                coroutineContext.ensureActive() // permite cancelar la descarga
                val read = input.read(buffer)
                if (read < 0) break
                os.write(buffer, 0, read)
                copied += read
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 300) {
                    lastReport = now
                    onProgress(Progress.Downloading(copied, total))
                }
            }
        }
        onProgress(Progress.Downloading(copied, total))
    }

    private fun parseAndCommit(ctx: Context, tmp: File, onProgress: (Progress) -> Unit) {
        validateHeader(tmp)
        val channels = parseFile(tmp, onProgress)
        if (channels.isEmpty()) {
            throw PlaylistException("La lista no contiene canales. Puede que la cuenta esté caducada o la URL sea incorrecta.")
        }
        // Guardamos como caché solo si es válida
        val cache = cacheFile(ctx)
        if (!tmp.renameTo(cache)) {
            tmp.copyTo(cache, overwrite = true)
            tmp.delete()
        }
        writeBinSafe(binFile(ctx), channels)
        xtreamFile(ctx).delete() // la lista activa ya no es Xtream
        commit(channels)
    }

    /** Detecta respuestas que no son M3U (páginas HTML de error, JSON, vacío…). */
    private fun validateHeader(file: File) {
        if (file.length() == 0L) throw PlaylistException("El servidor devolvió un archivo vacío (¿cuenta caducada?).")
        val head = file.inputStream().use { s ->
            val buf = ByteArray(8192)
            val n = s.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }.lowercase()
        if (head.contains("<html") || head.contains("<!doctype")) {
            throw PlaylistException("El servidor devolvió una página web en lugar de una lista. Revisa URL, usuario y contraseña.")
        }
        if (!head.contains("#extm3u") && !head.contains("#extinf") && !head.contains("://")) {
            throw PlaylistException("El contenido no parece una lista M3U válida.")
        }
    }

    private fun commit(all: List<Channel>) {
        val liveList = ArrayList<Channel>()
        val movieList = ArrayList<Channel>()
        // Agrupa episodios por (grupo, nombre de serie) manteniendo el orden original
        val seriesMap = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in all) {
            when (ch.type) {
                ContentType.LIVE -> liveList.add(ch)
                ContentType.MOVIE -> movieList.add(ch)
                ContentType.SERIES -> {
                    val key = ch.group + "\u0000" + ContentClassifier.seriesNameOf(ch)
                    seriesMap.getOrPut(key) { ArrayList() }.add(ch)
                }
            }
        }
        val showList = seriesMap.map { (key, eps) ->
            SeriesShow(
                name = key.substringAfter('\u0000'),
                group = eps.first().group,
                logo = eps.firstNotNullOfOrNull { it.logo },
                episodes = eps,
            )
        }
        val numbers = java.util.IdentityHashMap<Channel, Int>(liveList.size)
        liveList.forEachIndexed { i, c -> numbers[c] = i + 1 }
        val order = HashMap<String, Int>()
        all.forEach { order.putIfAbsent(it.group, order.size) }

        liveNumbers = numbers
        groupOrder = order
        live = liveList
        movies = movieList
        shows = showList
    }

    // ================================================================ Xtream (API)

    /**
     * Carga una cuenta Xtream: datos de la cuenta, categorías y canales en vivo.
     * Las películas y series se piden después, categoría a categoría.
     */
    suspend fun loadXtream(ctx: Context, creds: XtreamCreds, userAgent: String, onProgress: (Progress) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            onProgress(Progress.Step(STEP_LOGIN))
            val login = XtreamApi.login(creds, userAgent)

            onProgress(Progress.Step(STEP_CATEGORIES))
            val liveCats = XtreamApi.liveCategories(creds, userAgent)
            // Si el proveedor no ofrece películas o series, no es un error
            val vodCats = runCatching { XtreamApi.vodCategories(creds, userAgent) }.getOrDefault(emptyList())
            val seriesCats = runCatching { XtreamApi.seriesCategories(creds, userAgent) }.getOrDefault(emptyList())

            onProgress(Progress.Step(STEP_LIVE))
            clear() // libera la lista anterior antes de descargar los canales
            val liveList = XtreamApi.liveStreams(creds, userAgent, login.liveExt, liveCats.associate { it.id to it.name }) { n ->
                onProgress(Progress.Parsing(n))
            }
            if (liveList.isEmpty() && vodCats.isEmpty() && seriesCats.isEmpty()) {
                throw PlaylistException("La cuenta no tiene contenido disponible.")
            }

            commitXtream(creds, userAgent, login.account, liveCats, vodCats, seriesCats, liveList)
            deleteSearchIndex(ctx) // el catálogo puede haber cambiado: se rehará al buscar

            // Guardar para abrir al instante la próxima vez
            loadedLiveOnly = false
            writeBinSafe(binFile(ctx), liveList)
            writeXtreamJson(ctx, creds, userAgent, login.account, liveCats, vodCats, seriesCats)
            cacheFile(ctx).delete() // la lista activa ya no es M3U
            Prefs(ctx).lastRefreshAt = System.currentTimeMillis()
        }

    /**
     * Actualiza la cuenta Xtream en segundo plano (canales nuevos, categorías…) sin
     * interrumpir lo que se está viendo: la lista actual sigue disponible hasta que la
     * nueva está completa. Si algo falla, se queda la que había.
     */
    suspend fun refreshXtreamQuietly(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val creds = xtream ?: return@withContext false
        val ua = xtreamUa
        try {
            val login = XtreamApi.login(creds, ua)
            val liveCats = XtreamApi.liveCategories(creds, ua)
            val vodCats = runCatching { XtreamApi.vodCategories(creds, ua) }.getOrDefault(emptyList())
            val seriesCats = runCatching { XtreamApi.seriesCategories(creds, ua) }.getOrDefault(emptyList())
            val liveList = XtreamApi.liveStreams(creds, ua, login.liveExt, liveCats.associate { it.id to it.name }) { }
            if (liveList.isEmpty() && vodCats.isEmpty() && seriesCats.isEmpty()) return@withContext false
            if (xtream != creds) return@withContext false   // se cambió de lista mientras tanto
            synchronized(vodCache) { vodCache.clear() }
            synchronized(seriesCache) { seriesCache.clear() }
            commitXtream(creds, ua, login.account, liveCats, vodCats, seriesCats, liveList)
            deleteSearchIndex(ctx)
            writeBinSafe(binFile(ctx), liveList)
            writeXtreamJson(ctx, creds, ua, login.account, liveCats, vodCats, seriesCats)
            Prefs(ctx).lastRefreshAt = System.currentTimeMillis()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    /** Lanza la actualización diaria si toca (no depende de la pantalla abierta). */
    fun refreshDailyIfNeeded(ctx: Context, onDone: () -> Unit) {
        val app = ctx.applicationContext
        if (!isXtream || refreshJob?.isActive == true) return
        if (System.currentTimeMillis() - Prefs(app).lastRefreshAt < DAILY_MS) return
        refreshJob = indexScope.launch {
            if (refreshXtreamQuietly(app)) kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
        }
    }

    @Volatile private var refreshJob: kotlinx.coroutines.Job? = null
    private const val DAILY_MS = 24 * 60 * 60 * 1000L

    private fun commitXtream(
        creds: XtreamCreds, ua: String, acc: XtreamAccount?,
        liveCats: List<Category>, vodCats: List<Category>, seriesCats: List<Category>, liveList: List<Channel>,
    ) {
        val order = HashMap<String, Int>()
        (liveCats + vodCats + seriesCats).forEach { order.putIfAbsent(it.name, order.size) }
        liveList.forEach { order.putIfAbsent(it.group, order.size) }
        groupOrder = order
        liveNumbers = java.util.IdentityHashMap()
        movies = emptyList()
        shows = emptyList()
        xtreamUa = ua
        account = acc
        vodCategories = vodCats
        seriesCategories = seriesCats
        live = liveList
        xtream = creds
    }

    private fun writeXtreamJson(
        ctx: Context, c: XtreamCreds, ua: String, acc: XtreamAccount,
        liveCats: List<Category>, vodCats: List<Category>, seriesCats: List<Category>,
    ) {
        fun cats(list: List<Category>) = JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        }
        try {
            val o = JSONObject()
                .put("host", c.host).put("user", c.user).put("pass", c.pass).put("ua", ua)
                .put("status", acc.status).put("exp", acc.expiresAt).put("cons", acc.maxConnections)
                .put("live", cats(liveCats)).put("vod", cats(vodCats)).put("series", cats(seriesCats))
            xtreamFile(ctx).writeText(o.toString())
        } catch (e: Exception) {
            xtreamFile(ctx).delete()
        }
    }

    private fun loadXtreamCache(ctx: Context) {
        val o = try {
            JSONObject(xtreamFile(ctx).readText())
        } catch (e: Exception) {
            xtreamFile(ctx).delete()
            throw PlaylistException("La lista guardada está dañada. Vuelve a cargarla.")
        }
        fun cats(key: String): List<Category> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Category(it.optString("id"), it.optString("name")) }
            }
        }
        clear()
        val liveList = readBin(binFile(ctx)) ?: emptyList()
        val creds = XtreamCreds(o.optString("host"), o.optString("user"), o.optString("pass"))
        val acc = XtreamAccount(o.optString("status"), o.optLong("exp"), o.optInt("cons"))
        val vod = cats("vod")
        val series = cats("series")
        if (liveList.isEmpty() && vod.isEmpty() && series.isEmpty()) {
            throw PlaylistException("La lista guardada está vacía. Vuelve a cargarla.")
        }
        commitXtream(creds, o.optString("ua", Network.DEFAULT_UA), acc, cats("live"), vod, series, liveList)
    }

    /** Películas de una categoría Xtream (de memoria si se abrió hace poco). */
    suspend fun moviesOf(category: Category): List<Channel> {
        synchronized(vodCache) { vodCache[category.id] }?.let { return it }
        val creds = xtream ?: return emptyList()
        val list = withContext(Dispatchers.IO) { XtreamApi.vodStreams(creds, xtreamUa, category) }
        synchronized(vodCache) { vodCache[category.id] = list }
        return list
    }

    /** Series de una categoría Xtream (de memoria si se abrió hace poco). */
    suspend fun showsOf(category: Category): List<SeriesShow> {
        synchronized(seriesCache) { seriesCache[category.id] }?.let { return it }
        val creds = xtream ?: return emptyList()
        val list = withContext(Dispatchers.IO) { XtreamApi.seriesList(creds, xtreamUa, category) }
        synchronized(seriesCache) { seriesCache[category.id] = list }
        return list
    }

    /**
     * Episodios de una serie. En Xtream se descargan al abrirla; en M3U ya están cargados
     * (para series guardadas en favoritos se buscan por nombre en la lista actual).
     */
    /** Ficha de una película (solo cuentas Xtream). null si no hay datos. */
    suspend fun movieInfo(ch: Channel): MovieInfo? {
        val creds = xtream ?: return null
        val id = XtreamApi.vodIdOf(ch.url) ?: return null
        return withContext(Dispatchers.IO) { runCatching { XtreamApi.vodInfo(creds, xtreamUa, id) }.getOrNull() }
    }

    suspend fun episodesOf(show: SeriesShow): List<Channel> {
        if (show.episodes.isNotEmpty()) return show.episodes
        val creds = xtream
        val eps = when {
            show.seriesId != null && creds != null ->
                withContext(Dispatchers.IO) { XtreamApi.seriesEpisodes(creds, xtreamUa, show) }
            else -> shows.firstOrNull { it.group == show.group && it.name == show.name }?.episodes.orEmpty()
        }
        show.episodes = eps
        return eps
    }

    // ================================================================ Buscador global (Xtream)
    //
    // Para buscar una película o serie por su título en todo el catálogo se descarga
    // UNA vez la lista completa de títulos (sin episodios) y se guarda en el dispositivo.
    // Las búsquedas siguientes son instantáneas. Se rehace al pulsar «Actualizar lista».

    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexLock = Any()
    @Volatile private var movieIndexJob: Deferred<List<Channel>>? = null
    @Volatile private var seriesIndexJob: Deferred<List<SeriesShow>>? = null

    /** Avisa del número de títulos descargados mientras se prepara el índice. */
    @Volatile var indexProgress: ((Int) -> Unit)? = null

    private fun movieIndexFile(ctx: Context) = File(ctx.filesDir, "search_movies.bin")
    private fun seriesIndexFile(ctx: Context) = File(ctx.filesDir, "search_series.bin")

    private fun deleteSearchIndex(ctx: Context) {
        movieIndexFile(ctx).delete()
        seriesIndexFile(ctx).delete()
    }

    /** true si el índice ya está en memoria (la búsqueda será instantánea). */
    fun isMovieIndexReady(): Boolean = movieIndexJob?.isCompleted == true
    fun isSeriesIndexReady(): Boolean = seriesIndexJob?.isCompleted == true

    /** Todas las películas (para buscar). Se descarga una vez y se reutiliza. */
    suspend fun movieIndex(ctx: Context): List<Channel> {
        val app = ctx.applicationContext
        val job = synchronized(indexLock) {
            movieIndexJob ?: indexScope.async { buildMovieIndex(app) }.also { movieIndexJob = it }
        }
        return awaitIndex(job) { synchronized(indexLock) { if (movieIndexJob === job) movieIndexJob = null } }
    }

    /** Todas las series (para buscar). Se descarga una vez y se reutiliza. */
    suspend fun seriesIndex(ctx: Context): List<SeriesShow> {
        val app = ctx.applicationContext
        val job = synchronized(indexLock) {
            seriesIndexJob ?: indexScope.async { buildSeriesIndex(app) }.also { seriesIndexJob = it }
        }
        return awaitIndex(job) { synchronized(indexLock) { if (seriesIndexJob === job) seriesIndexJob = null } }
    }

    /** Espera el índice; si falló, lo descarta para poder reintentarlo en la próxima búsqueda. */
    private suspend fun <T> awaitIndex(job: Deferred<T>, onFail: () -> Unit): T = try {
        job.await()
    } catch (e: kotlinx.coroutines.CancellationException) {
        if (job.isCancelled) onFail()
        throw e
    } catch (e: Exception) {
        onFail()
        throw e
    }

    private fun buildMovieIndex(ctx: Context): List<Channel> {
        val creds = xtream ?: return emptyList()
        val file = movieIndexFile(ctx)
        readBin(file)?.let { if (it.isNotEmpty()) return it }
        val names = vodCategories.associate { it.id to it.name }
        val list = try {
            XtreamApi.allVodStreams(creds, xtreamUa, names) { n -> indexProgress?.invoke(n) }
        } catch (e: OutOfMemoryError) {
            throw PlaylistException("El catálogo de películas es demasiado grande para buscar en este dispositivo. Entra por categorías.")
        }
        writeBinSafe(file, list)
        return list
    }

    private fun buildSeriesIndex(ctx: Context): List<SeriesShow> {
        val creds = xtream ?: return emptyList()
        val file = seriesIndexFile(ctx)
        readShows(file)?.let { if (it.isNotEmpty()) return it }
        val names = seriesCategories.associate { it.id to it.name }
        val list = try {
            XtreamApi.allSeries(creds, xtreamUa, names) { n -> indexProgress?.invoke(n) }
        } catch (e: OutOfMemoryError) {
            throw PlaylistException("El catálogo de series es demasiado grande para buscar en este dispositivo. Entra por categorías.")
        }
        writeShows(file, list)
        return list
    }

    private const val SHOWS_MAGIC = 0x52545653 // "RTVS"

    private fun writeShows(file: File, list: List<SeriesShow>) {
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file), 256 * 1024)).use { o ->
                o.writeInt(SHOWS_MAGIC)
                o.writeInt(list.size)
                for (sh in list) {
                    o.writeStr(sh.name); o.writeStr(sh.group); o.writeStr(sh.logo); o.writeStr(sh.seriesId)
                }
            }
        } catch (t: Throwable) {
            file.delete()
        }
    }

    private fun readShows(file: File): List<SeriesShow>? = try {
        if (!file.exists()) null
        else DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { i ->
            if (i.readInt() != SHOWS_MAGIC) return null
            val n = i.readInt()
            if (n < 0 || n > 2_000_000) return null
            val pool = HashMap<String, String>()
            val list = ArrayList<SeriesShow>(n)
            repeat(n) {
                val name = i.readStr()!!
                val group = i.readStr()!!.let { g -> pool.getOrPut(g) { g } }
                val logo = i.readStr()
                val sid = i.readStr()
                list.add(SeriesShow(name, group, logo, emptyList(), sid))
            }
            list
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
