package com.ale.cineiptv.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Control parental con PIN de 4 dígitos.
 *  - Sin PIN creado no se bloquea nada.
 *  - Con PIN: se bloquean los grupos marcados a mano y, si está activo,
 *    los grupos de adultos detectados automáticamente.
 *  - Al introducir el PIN se desbloquea todo hasta volver al menú principal.
 */
object ParentalControl {

    private var sp: SharedPreferences? = null

    /** Desbloqueado temporalmente con el PIN (se vuelve a bloquear al volver al menú). */
    @Volatile var sessionUnlocked = false

    private val ADULT_WORDS = listOf(
        "adult", "xxx", "+18", "18+", "porn", "erotic", "erótic", "hentai", "for adults", "only fans"
    )

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences("retro_parental", Context.MODE_PRIVATE)
    }

    val hasPin: Boolean get() = !sp?.getString(KEY_PIN, null).isNullOrEmpty()

    fun checkPin(pin: String): Boolean = hasPin && hash(pin) == sp?.getString(KEY_PIN, null)

    fun setPin(pin: String) {
        sp?.edit()?.putString(KEY_PIN, hash(pin))?.apply()
    }

    /** Quita el PIN y todos los bloqueos. */
    fun disable() {
        sp?.edit()?.clear()?.apply()
        sessionUnlocked = false
    }

    var autoAdult: Boolean
        get() = sp?.getBoolean(KEY_AUTO, true) ?: true
        set(v) { sp?.edit()?.putBoolean(KEY_AUTO, v)?.apply() }

    val lockedGroups: Set<String>
        get() = sp?.getStringSet(KEY_GROUPS, emptySet()).orEmpty()

    fun isManuallyLocked(group: String) = group in lockedGroups

    fun setGroupLocked(group: String, locked: Boolean) {
        val set = HashSet(lockedGroups)
        if (locked) set.add(group) else set.remove(group)
        sp?.edit()?.putStringSet(KEY_GROUPS, set)?.apply()
    }

    fun isAdultGroup(group: String): Boolean {
        val g = group.lowercase()
        return ADULT_WORDS.any { g.contains(it) }
    }

    /** ¿Está protegido este grupo (aunque ahora esté desbloqueado)? */
    fun isProtected(group: String): Boolean =
        hasPin && (isManuallyLocked(group) || (autoAdult && isAdultGroup(group)))

    /** ¿Hay que ocultarlo ahora mismo? */
    fun isLocked(group: String): Boolean = !sessionUnlocked && isProtected(group)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("retro-iptv:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val KEY_PIN = "pin"
    private const val KEY_AUTO = "auto_adult"
    private const val KEY_GROUPS = "locked_groups"
}
