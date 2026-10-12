package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoreLog
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.InflaterInputStream

/** gameplay.gog.com, with a token scoped to the game's own client. */
object GogAchievements : StoreAchievements(Store.GOG.id, Store.GOG.shortLabel) {
    private const val TAG = "GogAchievements"

    override fun request(context: Context, game: Game): Result {
        val galaxy = GogAuth.validToken(context) ?: return Result.Failed(Reason.SIGNED_OUT)
        val (clientId, clientSecret) = client(context, game.id, galaxy) ?: return Result.Failed(Reason.NO_SERVICE)
        val scoped = GogAuth.gameToken(context, clientId, clientSecret) ?: return Result.Failed(Reason.UNREACHABLE)
        val body = StoreNet.get(
            "https://gameplay.gog.com/clients/$clientId/users/${scoped.userId}/achievements",
            bearer = scoped.accessToken, userAgent = GogAuth.GALAXY_UA, headers = mapOf("X-Gog-Lc" to locale()),
        ) ?: return Result.Failed(Reason.UNREACHABLE)
        val items = body.takeIf { it.isNotBlank() }?.let(::JSONObject)?.optJSONArray("items") ?: return Result.Found(emptyList())
        val out = ArrayList<Achievement>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("achievement_key").ifEmpty { item.optString("achievement_id") }
            if (id.isEmpty()) continue
            val date = item.optString("date_unlocked").takeIf { it.isNotBlank() && it != "null" }
            out += Achievement(
                id = id,
                name = item.optString("name").ifEmpty { id },
                description = item.optString("description"),
                hidden = !item.optBoolean("visible", true),
                unlocked = date != null,
                unlockedAt = date?.let(::epochSeconds) ?: 0,
                icon = item.optString("image_url_unlocked").ifEmpty { item.optString("image_url_locked") }.ifEmpty { null },
                iconLocked = item.optString("image_url_locked").ifEmpty { null },
            )
        }
        return Result.Found(out)
    }

    /** The game's client id and secret: saved at install, else from the newest build's manifest. */
    private fun client(context: Context, productId: String, token: String): Pair<String, String>? {
        val prefs = GogPrefs.get(context)
        val savedId = prefs.getString("client_id_$productId", null)?.ifEmpty { null }
        val savedSecret = prefs.getString("client_secret_$productId", null)?.ifEmpty { null }
        if (savedId != null && savedSecret != null) return savedId to savedSecret
        val builds = StoreNet.get("https://content-system.gog.com/products/$productId/os/windows/builds?generation=2", bearer = token)
            ?.let(::JSONObject) ?: return null
        val link = builds.optJSONArray("items")?.optJSONObject(0)?.optString("link")?.ifEmpty { null } ?: return null
        val manifest = bytes(link, token)?.let { JSONObject(inflate(it)) } ?: return null
        val id = manifest.optString("clientId").ifEmpty { return null }
        val secret = manifest.optString("clientSecret").ifEmpty { return null }
        prefs.edit().putString("client_id_$productId", id).putString("client_secret_$productId", secret).apply()
        return id to secret
    }

    private fun bytes(url: String, bearer: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "GET failed ${StoreLog.redactUrl(url)}: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Gen2 manifests are zlib-compressed; plain JSON passes through. */
    private fun inflate(data: ByteArray): String =
        if (data.isNotEmpty() && data[0] == '{'.code.toByte()) String(data, Charsets.UTF_8)
        else InflaterInputStream(data.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
}
