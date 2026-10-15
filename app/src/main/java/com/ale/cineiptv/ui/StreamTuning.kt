package com.ale.cineiptv.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Ajustes de la reproducción para evitar cortes.
 *
 * En directo:
 *  - el canal arranca en cuanto hay 1,5 s de vídeo (antes esperaba 6 s → arranque lento)
 *  - mientras se ve, el colchón sigue creciendo en segundo plano hasta 60 s
 *  - si aun así se corta, se espera a tener 8 s antes de seguir, para que no vuelva a cortarse
 *  - si la conexión falla, se reintenta en segundo plano mientras se reproduce el colchón
 */
@OptIn(UnstableApi::class)
object StreamTuning {

    // ---- Directo
    /** Segundos guardados antes de empezar a reproducir un canal. */
    private const val LIVE_START_MS = 1_500
    /** Segundos guardados antes de continuar tras un corte (colchón mayor). */
    private const val LIVE_AFTER_STALL_MS = 8_000
    private const val LIVE_MIN_BUFFER_MS = 30_000
    private const val LIVE_MAX_BUFFER_MS = 60_000

    // ---- Películas y series (el servidor envía rápido: se guarda mucho por delante)
    private const val VOD_START_MS = 2_500
    private const val VOD_AFTER_STALL_MS = 6_000
    private const val VOD_MIN_BUFFER_MS = 50_000
    private const val VOD_MAX_BUFFER_MS = 120_000

    /** Tope de memoria del búfer (los Fire TV Stick tienen poca RAM). */
    private const val MAX_BUFFER_BYTES = 64 * 1024 * 1024

    /** Tiempo sin recibir datos antes de reconectar (en directo, menor que el colchón). */
    const val LIVE_READ_TIMEOUT_MS = 8_000
    const val VOD_READ_TIMEOUT_MS = 30_000
    const val CONNECT_TIMEOUT_MS = 10_000

    /**
     * Conexiones HTTP con OkHttp (como las apps IPTV más usadas): reutiliza conexiones,
     * sigue redirecciones http↔https de los balanceadores y reintenta si se cae la conexión.
     */
    private val liveClient by lazy {
        com.ale.cineiptv.data.Network.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(LIVE_READ_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    private val vodClient by lazy {
        com.ale.cineiptv.data.Network.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(VOD_READ_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun dataSource(isLive: Boolean, userAgent: String, referrer: String?): OkHttpDataSource.Factory {
        val f = OkHttpDataSource.Factory(if (isLive) liveClient else vodClient).setUserAgent(userAgent)
        referrer?.let { f.setDefaultRequestProperties(mapOf("Referer" to it)) }
        return f
    }

    fun loadControl(isLive: Boolean): LoadControl {
        val builder = DefaultLoadControl.Builder()
        if (isLive) {
            builder.setBufferDurationsMs(LIVE_MIN_BUFFER_MS, LIVE_MAX_BUFFER_MS, LIVE_START_MS, LIVE_AFTER_STALL_MS)
        } else {
            builder.setBufferDurationsMs(VOD_MIN_BUFFER_MS, VOD_MAX_BUFFER_MS, VOD_START_MS, VOD_AFTER_STALL_MS)
        }
        return builder
            .setTargetBufferBytes(MAX_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    /**
     * Canales HLS: se usa el retraso que indica el propio servidor (forzar 20 s hacía que
     * algunos canales con poca ventana no llegaran a arrancar) y no se acelera la
     * reproducción para "alcanzar" el directo (eso vacía el colchón).
     */
    fun liveConfiguration(): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder()
            .setMinPlaybackSpeed(1f)
            .setMaxPlaybackSpeed(1f)
            .build()

    /** Reintenta en silencio los fallos de red; los errores definitivos (cuenta, canal inexistente) no. */
    /**
     * Lectura de streams .ts con la configuración estándar. (Se probó a mostrar imagen
     * antes del primer fotograma clave para arrancar antes, pero en algunas teles el
     * vídeo se quedaba congelado mientras el audio seguía.)
     */
    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()

    /** Si un canal no ha empezado en este tiempo, se vuelve a conectar. */
    const val LIVE_START_WATCHDOG_MS = 12_000L

    fun errorPolicy(isLive: Boolean): LoadErrorHandlingPolicy = RetryPolicy(if (isLive) 60 else 10)

    private class RetryPolicy(retries: Int) : DefaultLoadErrorHandlingPolicy(retries) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val base = super.getRetryDelayMsFor(loadErrorInfo)
            if (base == C.TIME_UNSET) return C.TIME_UNSET
            val e = loadErrorInfo.exception
            if (e is HttpDataSource.InvalidResponseCodeException) {
                when (e.responseCode) {
                    401, 404, 410 -> return C.TIME_UNSET              // cuenta o canal no válidos
                    // 403/429/458/509 suelen ser "demasiadas conexiones": con cuentas de 1 conexión,
                    // al cambiar de canal el servidor tarda unos segundos en soltar la anterior
                    403, 429, 458, 509 -> return if (loadErrorInfo.errorCount > 6) C.TIME_UNSET else 1_500L
                }
            }
            // Reintentos rápidos: 0,5 s, 1 s, 1,5 s… hasta 2 s
            return minOf(loadErrorInfo.errorCount * 500L, 2_000L)
        }
    }
}

/**
 * Distintas formas de pedir el mismo canal. Si una falla, el reproductor prueba la
 * siguiente sin que el usuario tenga que hacer nada, y recuerda la que funcionó:
 *  1. dirección original con el User-Agent normal
 *  2. el otro formato del servidor Xtream (.ts ↔ .m3u8): algunos canales solo van en uno
 *  3. y 4. lo mismo con otro User-Agent (algunos servidores rechazan ciertos reproductores)
 */
object StreamVariants {

    /** User-Agent alternativo (el de FFmpeg, que usan Kodi y otros muchos reproductores). */
    const val ALT_UA = "Lavf/60.3.100"

    class Variant(val url: String, val userAgent: String) {
        val format: String get() = url.substringBefore('?').substringAfterLast('.', "").take(5)
    }

    /** Variante que funcionó la última vez para cada canal (durante esta sesión). */
    private val worked = HashMap<String, Int>()

    fun of(url: String, userAgent: String): List<Variant> {
        val alt = altFormat(url)
        val list = ArrayList<Variant>(4)
        list.add(Variant(url, userAgent))
        if (alt != null) list.add(Variant(alt, userAgent))
        if (userAgent != ALT_UA) {
            list.add(Variant(url, ALT_UA))
            if (alt != null) list.add(Variant(alt, ALT_UA))
        }
        return list
    }

    fun preferred(url: String): Int = synchronized(worked) { worked[url] ?: 0 }

    fun remember(url: String, variant: Int) = synchronized(worked) {
        if (variant == 0) worked.remove(url) else worked[url] = variant
    }

    /** /live/usuario/clave/123.ts ↔ /live/usuario/clave/123.m3u8 */
    private fun altFormat(url: String): String? {
        val path = url.substringBefore('?')
        val query = url.substring(path.length)
        if (!path.contains("/live/", ignoreCase = true)) return null
        return when {
            path.endsWith(".ts", ignoreCase = true) -> path.dropLast(3) + ".m3u8" + query
            path.endsWith(".m3u8", ignoreCase = true) -> path.dropLast(5) + ".ts" + query
            else -> null
        }
    }
}
