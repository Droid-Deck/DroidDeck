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
    const val EXTRA_UNLOCK_SOURCE = "com.droiddeck.launcher.extra.DESKTOP_UNLOCK_SOURCE"
    private const val PREFS = "desktop_lock"
    private const val SALT = "salt"
    private const val HASH = "hash"
    private const val ROUNDS = 20_000
    private val PIN = Regex("[0-9]{4,8}")
    private val UI = Regex("[a-z]{1,32}")

    data class Handoff(val mode: String, val ui: String, val url: String, val source: String? = null) {
        fun put(intent: Intent): Intent = intent
            .putExtra(EXTRA_UNLOCK_MODE, mode)
            .putExtra(EXTRA_UNLOCK_UI, ui)
            .putExtra(EXTRA_UNLOCK_URL, url)
            .putExtra(EXTRA_UNLOCK_SOURCE, source)

        fun session(context: Context): Intent {
            val next = Intent(context, com.droiddeck.launcher.SessionActivity::class.java)
                .putExtra(SessionService.EXTRA_MODE, mode)
                .putExtra(SessionService.EXTRA_STEAM_UI, ui)
                .putExtra(EXTRA_UNLOCK_SOURCE, source)
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
        val source = intent.getStringExtra(EXTRA_UNLOCK_SOURCE)
        intent.removeExtra(EXTRA_UNLOCK_SOURCE)
        intent.removeExtra(EXTRA_UNLOCK_MODE)
        intent.removeExtra(EXTRA_UNLOCK_UI)
        intent.removeExtra(EXTRA_UNLOCK_URL)
        return found?.copy(source = source)
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class Credentials(val salt: String?, val hash: String?)
    private val saved = java.util.concurrent.ConcurrentHashMap<SharedPreferences, Credentials>()

    private fun credentials(p: SharedPreferences): Credentials = saved.computeIfAbsent(p) {
        Credentials(it.getString(SALT, null), it.getString(HASH, null))
    }

    private fun write(p: SharedPreferences, next: Credentials): Boolean {
        val previous = credentials(p)
        if (!p.edit().putString(SALT, next.salt).putString(HASH, next.hash).commit()) {
            p.edit().putString(SALT, previous.salt).putString(HASH, previous.hash).commit()
            throw java.io.IOException()
        }
        saved[p] = next
        return true
    }

    fun enabled(context: Context): Boolean = credentials(prefs(context)).hash != null

    fun validPin(pin: String): Boolean = PIN.matches(pin)

    fun requiresUnlock(enabled: Boolean, mode: String?, ui: String?): Boolean = enabled && entersDesktop(mode, ui)

    fun currentSession(source: String?, current: String?, running: Boolean): Boolean =
        source != null && source == current && running

    fun entersDesktop(mode: String?, steamUi: String?): Boolean =
        mode == SessionService.MODE_DESKTOP || steamUi == "desktop"

    fun entersDesktop(intent: Intent): Boolean =
        entersDesktop(intent.getStringExtra(SessionService.EXTRA_MODE), intent.getStringExtra(SessionService.EXTRA_STEAM_UI))

    @Synchronized
    fun set(context: Context, pin: String): Boolean {
        if (!validPin(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return write(prefs(context), Credentials(Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(hash(pin, salt))))
    }

    fun matches(context: Context, pin: String): Boolean {
        val storedCredentials = credentials(prefs(context))
        val salt = storedCredentials.salt?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        val stored = storedCredentials.hash?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        return validPin(pin) && MessageDigest.isEqual(stored, hash(pin, salt))
    }

    @Synchronized
    fun clear(context: Context, pin: String): Boolean =
        matches(context, pin) && write(prefs(context), Credentials(null, null))

    internal fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
