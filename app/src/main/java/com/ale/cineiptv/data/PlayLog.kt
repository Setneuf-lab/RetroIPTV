package com.ale.cineiptv.data

import android.content.Context
import android.os.Build
import com.ale.cineiptv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Informe de reproducción para el panel web: arranques (y cuánto tardan), cortes,
 * errores, imagen congelada, cambios de formato… Se guarda en el dispositivo y se
 * envía por lotes al panel (nunca se envían usuarios ni contraseñas de las listas).
 */
object PlayLog {

    private const val MAX_PENDING = 400
    private const val FLUSH_AT = 60

    private var file: File? = null
    private var appCtx: Context? = null
    private val pending = ArrayList<JSONObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var flushing = false

    fun init(context: Context) {
        if (file != null) return
        appCtx = context.applicationContext
        val f = File(context.filesDir, "playlog.json")
        file = f
        runCatching {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                synchronized(pending) { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { pending.add(it) } }
            }
        }
    }

    /**
     * Apunta un suceso. [type]: start_ok, start_timeout, error, variant, stall, frozen.
     * [extra]: datos del suceso (ms, http, formato…).
     */
    fun event(type: String, ch: Channel?, extra: Map<String, Any?> = emptyMap()) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("type", type)
        if (ch != null) {
            o.put("ch", ch.name).put("grp", ch.group).put("kind", ch.type.name.lowercase()).put("src", safeUrl(ch.url))
        }
        extra.forEach { (k, v) -> if (v != null) o.put(k, v) }
        val size = synchronized(pending) {
            pending.add(o)
            while (pending.size > MAX_PENDING) pending.removeAt(0)
            pending.size
        }
        save()
        if (size >= FLUSH_AT) flush()
    }

    /** Envía lo pendiente al panel (si no hay conexión, se queda para la próxima vez). */
    fun flush() {
        val ctx = appCtx ?: return
        if (flushing || !RemoteRepository.isConfigured) return
        val batch = synchronized(pending) { if (pending.isEmpty()) return else ArrayList(pending) }
        flushing = true
        scope.launch {
            try {
                val arr = JSONArray().also { a -> batch.forEach { a.put(it) } }
                val ok = RemoteRepository.uploadLog(
                    Prefs(ctx),
                    model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    app = BuildConfig.VERSION_NAME,
                    events = arr.toString(),
                )
                if (ok) {
                    synchronized(pending) { repeat(batch.size.coerceAtMost(pending.size)) { pending.removeAt(0) } }
                    save()
                }
            } finally {
                flushing = false
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val text = synchronized(pending) { JSONArray().also { a -> pending.forEach { a.put(it) } }.toString() }
        scope.launch { runCatching { f.writeText(text) } }
    }

    /**
     * Dirección sin datos de acceso: http://servidor:8080/live/USUARIO/CLAVE/123.ts
     * → http://servidor:8080/live/…/123.ts
     */
    fun safeUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        val scheme = noQuery.substringBefore("://", "")
        val rest = noQuery.substringAfter("://")
        val host = rest.substringBefore('/')
        val segs = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        val last = segs.lastOrNull().orEmpty()
        val kind = segs.firstOrNull()?.takeIf { it.lowercase() in setOf("live", "movie", "series", "timeshift") }
        val path = listOfNotNull(kind, if (segs.size > 1) "…" else null, last).joinToString("/")
        return (if (scheme.isNotEmpty()) "$scheme://" else "") + host + "/" + path
    }
}
