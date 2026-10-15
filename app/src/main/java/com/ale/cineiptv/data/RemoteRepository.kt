package com.ale.cineiptv.data

import android.net.Uri
import android.os.Build
import com.ale.cineiptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Una lista asignada desde el panel web. */
data class RemotePlaylist(
    val name: String,
    val type: String,      // "m3u" o "xtream"
    val url: String,
    val host: String,
    val user: String,
    val pass: String,
) {
    val isXtream: Boolean get() = type == "xtream"
    val isValid: Boolean get() = if (isXtream) host.isNotBlank() && user.isNotBlank() else url.isNotBlank()

    fun m3uUrl(): String = if (isXtream) Xtream.buildM3uUrl(host, user, pass) else url.trim()

    /** Cambia si cambia cualquier dato de conexión → hay que volver a descargar. */
    val signature: String get() = "remote|$type|${url.trim()}|${host.trim()}|${user.trim()}|${pass.trim()}"

    val label: String
        get() = name.ifBlank {
            if (isXtream) "Xtream · $user" else (Uri.parse(url).host ?: url)
        }
}

/** Configuración de este dispositivo guardada en el panel. */
data class RemoteDevice(
    val playlists: List<RemotePlaylist>,
    val active: Int,
    val updatedAt: Long,
) {
    val activePlaylist: RemotePlaylist? get() = playlists.getOrNull(active) ?: playlists.firstOrNull()
}

/**
 * Comunicación con el panel web a través de la API REST de Firestore (sin SDK de Firebase).
 *
 * Cada dispositivo es un documento `devices/{IDSINDOSPUNTOS}-{CLAVE}`.
 * Como las reglas de Firestore prohíben listar documentos, solo quien conoce
 * el ID + la clave de 6 dígitos puede leer o cambiar las listas de ese dispositivo.
 */
object RemoteRepository {

    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()

    private val client by lazy {
        Network.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    }
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun docId(prefs: Prefs): String =
        prefs.deviceId.replace(":", "").uppercase() + "-" + prefs.deviceKey

    private fun docUrl(docId: String, extraQuery: String = ""): String =
        "https://firestore.googleapis.com/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}" +
            "/databases/(default)/documents/devices/$docId?key=${BuildConfig.FIREBASE_API_KEY}$extraQuery"

    /**
     * Descarga la configuración de este dispositivo.
     * Si aún no existe, registra el dispositivo (vacío) para que el panel lo reconozca.
     * Devuelve null si no está configurado o no hay conexión: la app sigue funcionando en local.
     */
    suspend fun fetch(prefs: Prefs): RemoteDevice? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val id = docId(prefs)
        try {
            val request = Request.Builder().url(docUrl(id)).get().build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> {
                        register(id)
                        RemoteDevice(emptyList(), 0, 0L)
                    }
                    !resp.isSuccessful -> null
                    else -> parse(resp.body?.string().orEmpty())
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Marca como activa otra lista del panel (cuando se elige desde la propia app). */
    suspend fun setActive(prefs: Prefs, index: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val body = JSONObject().put(
            "fields", JSONObject().put("active", JSONObject().put("integerValue", index.toString()))
        ).toString()
        try {
            val request = Request.Builder()
                .url(docUrl(docId(prefs), "&updateMask.fieldPaths=active&currentDocument.exists=true"))
                .patch(body.toRequestBody(JSON_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Sube un lote del informe de reproducción a devices/{id}/logs (solo lo puede leer el admin). */
    suspend fun uploadLog(prefs: Prefs, model: String, app: String, events: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val fields = JSONObject()
            .put("createdAt", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
            .put("model", JSONObject().put("stringValue", model))
            .put("app", JSONObject().put("stringValue", app))
            .put("events", JSONObject().put("stringValue", events))
        val url = "https://firestore.googleapis.com/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}" +
            "/databases/(default)/documents/devices/${docId(prefs)}/logs?key=${BuildConfig.FIREBASE_API_KEY}"
        try {
            val request = Request.Builder().url(url)
                .post(JSONObject().put("fields", fields).toString().toRequestBody(JSON_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Crea el documento vacío del dispositivo, con el modelo como etiqueta. */
    private fun register(id: String) {
        val fields = JSONObject()
            .put("playlists", JSONObject().put("arrayValue", JSONObject().put("values", JSONArray())))
            .put("active", JSONObject().put("integerValue", "0"))
            .put("updatedAt", JSONObject().put("integerValue", "0"))
            .put("label", JSONObject().put("stringValue", "${Build.MANUFACTURER} ${Build.MODEL}".trim()))
        val request = Request.Builder()
            .url(docUrl(id, "&currentDocument.exists=false"))
            .patch(JSONObject().put("fields", fields).toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // Sin conexión: se intentará en el próximo arranque
        }
    }

    /** Convierte el JSON tipado de Firestore a nuestros objetos. */
    internal fun parse(json: String): RemoteDevice {
        val fields = JSONObject(json).optJSONObject("fields") ?: return RemoteDevice(emptyList(), 0, 0L)
        val list = ArrayList<RemotePlaylist>()
        val values = fields.optJSONObject("playlists")
            ?.optJSONObject("arrayValue")
            ?.optJSONArray("values")
        if (values != null) {
            for (i in 0 until values.length()) {
                val f = values.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                val p = RemotePlaylist(
                    name = str(f, "name"),
                    type = str(f, "type").ifBlank { "m3u" },
                    url = str(f, "url"),
                    host = str(f, "host"),
                    user = str(f, "user"),
                    pass = str(f, "pass"),
                )
                if (p.isValid) list.add(p)
            }
        }
        val active = num(fields, "active").toInt().coerceIn(0, (list.size - 1).coerceAtLeast(0))
        return RemoteDevice(list, active, num(fields, "updatedAt"))
    }

    private fun str(o: JSONObject, key: String): String =
        o.optJSONObject(key)?.optString("stringValue", "").orEmpty()

    private fun num(o: JSONObject, key: String): Long {
        val v = o.optJSONObject(key) ?: return 0L
        return v.optString("integerValue", "").toLongOrNull()
            ?: v.optDouble("doubleValue", 0.0).toLong()
    }
}
