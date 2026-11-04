package com.droiddeck.launcher.session

import android.database.sqlite.SQLiteDatabase
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class WebCookie(val host: String, val header: String)

object SteamWebLogin {
    val HOSTS = listOf("steamcommunity.com", "steam-chat.com")
    private const val COOKIES = "config/htmlcache/Default/Cookies"
    private const val V10 = "v10"
    private const val HOST_HASH_VERSION = 24
    private const val HOST_HASH_BYTES = 32

    private val key: SecretKeySpec by lazy {
        val spec = PBEKeySpec("peanuts".toCharArray(), "saltysalt".toByteArray(), 1, 128)
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES")
    }

    internal fun decrypt(encrypted: ByteArray, hostHashed: Boolean): String? = runCatching {
        if (encrypted.size <= V10.length || String(encrypted, 0, V10.length, Charsets.US_ASCII) != V10) return null
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ByteArray(16) { ' '.code.toByte() }))
        val plain = cipher.doFinal(encrypted, V10.length, encrypted.size - V10.length)
        val value = if (hostHashed && plain.size >= HOST_HASH_BYTES) plain.copyOfRange(HOST_HASH_BYTES, plain.size) else plain
        String(value, Charsets.UTF_8)
    }.getOrNull()

    internal fun header(name: String, value: String, httpOnly: Boolean): String {
        val v = if (name == "steamLoginSecure") value.replace("||", "%7C%7C") else value
        return "$name=$v; Path=/; Secure" + if (httpOnly) "; HttpOnly" else ""
    }

    fun read(runtimeRoot: File, client: String?, scratch: File): List<WebCookie> {
        val homes = client?.let { listOf(if (it == "x86_64") SteamDownloadMonitor.STEAM_HOMES[1] else SteamDownloadMonitor.STEAM_HOMES[0]) }
            ?: SteamDownloadMonitor.STEAM_HOMES
        val store = homes.map { File(runtimeRoot, "$it/$COOKIES") }.filter { it.isFile }.maxByOrNull { it.lastModified() }
            ?: return emptyList()
        scratch.deleteRecursively()
        if (!scratch.mkdirs()) return emptyList()
        return try {
            val copy = File(scratch, "Cookies")
            store.copyTo(copy, overwrite = true)
            File(store.path + "-journal").takeIf { it.length() > 0L }?.copyTo(File(scratch, "Cookies-journal"), overwrite = true)
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val version = db.rawQuery("SELECT value FROM meta WHERE key = 'version'", null).use { if (it.moveToFirst()) it.getString(0).toIntOrNull() else null } ?: 0
                val marks = HOSTS.joinToString { "?" }
                db.rawQuery("SELECT host_key, name, value, encrypted_value, is_httponly FROM cookies WHERE host_key IN ($marks)", HOSTS.toTypedArray()).use { c ->
                    val out = ArrayList<WebCookie>()
                    while (c.moveToNext()) {
                        val name = c.getString(1)
                        val value = c.getString(2).takeUnless { it.isNullOrEmpty() }
                            ?: decrypt(c.getBlob(3) ?: continue, version >= HOST_HASH_VERSION) ?: continue
                        out += WebCookie(c.getString(0), header(name, value, c.getInt(4) != 0))
                    }
                    out
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            scratch.deleteRecursively()
        }
    }
}
