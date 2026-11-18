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
    const val EXTRA_UNLOCK = "com.droiddeck.launcher.extra.DESKTOP_UNLOCK"
    private const val PREFS = "desktop_lock"
    private const val SALT = "salt"
    private const val HASH = "hash"
    private const val ROUNDS = 20_000
    private val PIN = Regex("[0-9]{4,8}")

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
