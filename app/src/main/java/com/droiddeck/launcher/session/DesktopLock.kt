package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object DesktopLock {
    const val EXTRA_UNLOCK_MODE = "com.droiddeck.launcher.extra.DESKTOP_UNLOCK_MODE"
    const val EXTRA_UNLOCK_UI = "com.droiddeck.launcher.extra.DESKTOP_UNLOCK_UI"
    const val EXTRA_UNLOCK_URL = "com.droiddeck.launcher.extra.DESKTOP_UNLOCK_URL"
    private const val PREFS = "desktop_lock"
    private const val SALT = "salt"
    private const val HASH = "hash"
    private const val ROUNDS = 20_000
    private val PIN = Regex("[0-9]{4,8}")
    private val UI = Regex("[a-z]{1,32}")

    data class Handoff(val mode: String, val ui: String, val url: String) {
        fun put(intent: Intent): Intent = intent
            .putExtra(EXTRA_UNLOCK_MODE, mode)
            .putExtra(EXTRA_UNLOCK_UI, ui)
            .putExtra(EXTRA_UNLOCK_URL, url)

        fun session(context: Context): Intent {
            val next = Intent(context, com.droiddeck.launcher.SessionActivity::class.java)
                .putExtra(SessionService.EXTRA_MODE, mode)
                .putExtra(SessionService.EXTRA_STEAM_UI, ui)
            if (url.isNotEmpty()) next.putExtra(SessionService.EXTRA_STEAM_URL, url)
            return next
        }
    }

    fun handoff(mode: String?, ui: String?, url: String?): Handoff? {
        if (mode != SessionService.MODE_STEAM && mode != SessionService.MODE_DESKTOP) return null
        if (ui == null || !UI.matches(ui) || !entersDesktop(mode, ui)) return null
        val link = url.orEmpty()
        if (link.isNotEmpty() && !link.startsWith("steam://")) return null
        return Handoff(mode, ui, link)
    }

    fun handoff(intent: Intent): Handoff? {
        if (!intent.hasExtra(EXTRA_UNLOCK_MODE)) return null
        val found = handoff(intent.getStringExtra(EXTRA_UNLOCK_MODE), intent.getStringExtra(EXTRA_UNLOCK_UI), intent.getStringExtra(EXTRA_UNLOCK_URL))
        intent.removeExtra(EXTRA_UNLOCK_MODE)
        intent.removeExtra(EXTRA_UNLOCK_UI)
        intent.removeExtra(EXTRA_UNLOCK_URL)
        return found
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).contains(HASH)

    fun validPin(pin: String): Boolean = PIN.matches(pin)

    fun entersDesktop(mode: String?, steamUi: String?): Boolean =
        mode == SessionService.MODE_DESKTOP || steamUi == "desktop"

    fun entersDesktop(intent: Intent): Boolean =
        entersDesktop(intent.getStringExtra(SessionService.EXTRA_MODE), intent.getStringExtra(SessionService.EXTRA_STEAM_UI))

    fun set(context: Context, pin: String): Boolean {
        if (!validPin(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return prefs(context).edit()
            .putString(SALT, Base64.getEncoder().encodeToString(salt))
            .putString(HASH, Base64.getEncoder().encodeToString(hash(pin, salt)))
            .commit()
    }

    fun matches(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(SALT, null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        val stored = p.getString(HASH, null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        return validPin(pin) && MessageDigest.isEqual(stored, hash(pin, salt))
    }

    fun clear(context: Context, pin: String): Boolean =
        matches(context, pin) && prefs(context).edit().clear().commit()

    internal fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
