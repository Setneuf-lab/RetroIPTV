package com.ale.cineiptv.data

import android.content.Context
import java.security.SecureRandom

/** Preferencias persistentes: última fuente usada, Device ID, posiciones de reproducción. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("cineiptv_prefs", Context.MODE_PRIVATE)

    var sourceType: SourceType
        get() = runCatching { SourceType.valueOf(sp.getString(KEY_SOURCE, null) ?: "URL") }
            .getOrDefault(SourceType.URL)
        set(v) = sp.edit().putString(KEY_SOURCE, v.name).apply()

    var m3uUrl: String
        get() = sp.getString("m3u_url", "").orEmpty()
        set(v) = sp.edit().putString("m3u_url", v).apply()

    var fileUri: String
        get() = sp.getString("file_uri", "").orEmpty()
        set(v) = sp.edit().putString("file_uri", v).apply()

    var fileName: String
        get() = sp.getString("file_name", "").orEmpty()
        set(v) = sp.edit().putString("file_name", v).apply()

    var xtHost: String
        get() = sp.getString("xt_host", "").orEmpty()
        set(v) = sp.edit().putString("xt_host", v).apply()

    var xtUser: String
        get() = sp.getString("xt_user", "").orEmpty()
        set(v) = sp.edit().putString("xt_user", v).apply()

    var xtPass: String
        get() = sp.getString("xt_pass", "").orEmpty()
        set(v) = sp.edit().putString("xt_pass", v).apply()

    /** User-Agent personalizado; vacío = el predeterminado (VLC), que casi todos los servidores aceptan. */
    var customUserAgent: String
        get() = sp.getString("user_agent", "").orEmpty()
        set(v) = sp.edit().putString("user_agent", v.trim()).apply()

    val effectiveUserAgent: String
        get() = customUserAgent.ifBlank { Network.DEFAULT_UA }

    /** Texto descriptivo de la lista cargada (para la cabecera). */
    var sourceLabel: String
        get() = sp.getString("source_label", "").orEmpty()
        set(v) = sp.edit().putString("source_label", v).apply()

    /** Ajustar la frecuencia de la tele a la del vídeo (evita tirones). Desactivado de serie: algunas teles se congelan al cambiar de modo. */
    var afrEnabled: Boolean
        get() = sp.getBoolean("afr_enabled_v2", false)
        set(v) = sp.edit().putBoolean("afr_enabled_v2", v).apply()

    /** Última vez que se actualizó la lista (para refrescarla sola una vez al día). */
    var lastRefreshAt: Long
        get() = sp.getLong("last_refresh_at", 0L)
        set(v) = sp.edit().putLong("last_refresh_at", v).apply()

    /** Filtro de idioma/país ("ES", "UK"…); vacío = todos. */
    var langFilter: String
        get() = sp.getString("lang_filter", "").orEmpty()
        set(v) = sp.edit().putString("lang_filter", v).apply()

    /** Orden de las listas: "number" (del proveedor), "name" (A-Z) o "group" (por grupos). */
    var sortMode: String
        get() = sp.getString("sort_mode", "number") ?: "number"
        set(v) = sp.edit().putString("sort_mode", v).apply()

    // --- Subtítulos ---
    /** Mostrar subtítulos automáticamente (si el contenido los tiene). */
    var subEnabled: Boolean
        get() = sp.getBoolean("sub_enabled", false)
        set(v) = sp.edit().putBoolean("sub_enabled", v).apply()

    /** Idioma preferido (código ISO, p. ej. "es"); vacío = cualquiera. */
    var subLang: String
        get() = sp.getString("sub_lang", "").orEmpty()
        set(v) = sp.edit().putString("sub_lang", v).apply()

    /** 0 pequeño · 1 normal · 2 grande · 3 muy grande */
    var subSize: Int
        get() = sp.getInt("sub_size", 1)
        set(v) = sp.edit().putInt("sub_size", v).apply()

    /** 0 normal · 1 negrita · 2 con serifa · 3 monoespaciada */
    var subFont: Int
        get() = sp.getInt("sub_font", 0)
        set(v) = sp.edit().putInt("sub_font", v).apply()

    /** 0 sin efecto · 1 sombra · 2 contorno · 3 fondo oscuro */
    var subEdge: Int
        get() = sp.getInt("sub_edge", 1)
        set(v) = sp.edit().putInt("sub_edge", v).apply()

    /** 0 blanco · 1 amarillo */
    var subColor: Int
        get() = sp.getInt("sub_color", 0)
        set(v) = sp.edit().putInt("sub_color", v).apply()

    // --- Panel web ---
    /** true si la lista actual viene del panel web (false si se cargó a mano en la app). */
    var useRemote: Boolean
        get() = sp.getBoolean("use_remote", false)
        set(v) = sp.edit().putBoolean("use_remote", v).apply()

    /** Última marca de tiempo del panel que ya se aplicó; si cambia, el panel manda. */
    var remoteSeenUpdatedAt: Long
        get() = sp.getLong("remote_seen", -1L)
        set(v) = sp.edit().putLong("remote_seen", v).apply()

    /** Firma de la lista que hay en caché, para no volver a descargar si no ha cambiado. */
    var loadedSignature: String
        get() = sp.getString("loaded_sig", "").orEmpty()
        set(v) = sp.edit().putString("loaded_sig", v).apply()

    /** ID único estilo MAC (AA:BB:CC:DD:EE:FF), generado una vez por instalación. */
    val deviceId: String
        get() {
            sp.getString(KEY_DEVICE_ID, null)?.let { return it }
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            bytes[0] = (bytes[0].toInt() and 0xFE or 0x02).toByte() // formato MAC local válido
            val id = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    /** Clave numérica de 6 dígitos asociada al dispositivo. */
    val deviceKey: String
        get() {
            sp.getString(KEY_DEVICE_KEY, null)?.let { return it }
            val key = (100000 + SecureRandom().nextInt(900000)).toString()
            sp.edit().putString(KEY_DEVICE_KEY, key).apply()
            return key
        }

    // --- "Continuar viendo" para películas y episodios ---
    /** Motor de reproducción: "auto" (VLC si el principal falla), "vlc" o "exo". */
    /** Idioma de audio elegido la última vez ("spa", "eng"…); vacío = el que venga. */
    var audioLang: String
        get() = sp.getString("audio_lang", "").orEmpty()
        set(v) = sp.edit().putString("audio_lang", v).apply()

    /** Último retraso usado para la radio con imagen (segundos). */
    var radioDelaySec: Int
        get() = sp.getInt("radio_delay", 0)
        set(v) = sp.edit().putInt("radio_delay", v).apply()

    /** DNS de la app: auto, system, cloudflare, google, quad9 (ver [SmartDns]). */
    var dnsMode: String
        get() = sp.getString("dns_mode", "auto") ?: "auto"
        set(v) { sp.edit().putString("dns_mode", v).apply(); SmartDns.mode = v }

    var engineMode: String
        get() = sp.getString("engine_mode", "auto") ?: "auto"
        set(v) = sp.edit().putString("engine_mode", v).apply()

    /** Canales que solo funcionaron con VLC (la próxima vez empiezan directamente con VLC). */
    fun isVlcChannel(url: String): Boolean =
        sp.getStringSet("vlc_channels", emptySet())!!.contains(url.hashCode().toString())

    fun markVlcChannel(url: String) {
        val set = HashSet(sp.getStringSet("vlc_channels", emptySet())!!)
        if (set.size > 500) set.clear()
        set.add(url.hashCode().toString())
        sp.edit().putStringSet("vlc_channels", set).apply()
    }

    /** Juntar en una fila las distintas calidades del mismo canal. */
    var groupDuplicates: Boolean
        get() = sp.getBoolean("group_duplicates", true)
        set(v) = sp.edit().putBoolean("group_duplicates", v).apply()

    /** Calidad preferida de un canal agrupado ("" = la que no indica calidad). */
    fun preferredQuality(key: String): String? = sp.getString("qpref_" + key.hashCode(), null)
    fun setPreferredQuality(key: String, quality: String) = sp.edit().putString("qpref_" + key.hashCode(), quality).apply()

    /** Últimas búsquedas de una sección (la más reciente primero). */
    fun recentSearches(section: String): List<String> =
        sp.getString("recent_$section", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun addRecentSearch(section: String, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val list = listOf(q) + recentSearches(section).filter { !it.equals(q, ignoreCase = true) }
        sp.edit().putString("recent_$section", list.take(MAX_RECENT).joinToString("\n")).apply()
    }

    fun savePosition(url: String, positionMs: Long) =
        sp.edit().putLong(posKey(url), positionMs).apply()

    fun getPosition(url: String): Long = sp.getLong(posKey(url), 0L)

    fun clearPosition(url: String) = sp.edit().remove(posKey(url)).apply()

    private fun posKey(url: String) = "pos_" + url.hashCode()

    private companion object {
        const val KEY_SOURCE = "source_type"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_KEY = "device_key"
        const val MAX_RECENT = 4
    }
}
