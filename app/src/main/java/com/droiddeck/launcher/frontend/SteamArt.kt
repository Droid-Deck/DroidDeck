package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SteamArt {
    private const val TAG = "SteamArt"
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val MISS_RETRY_MS = 3L * 24 * 3600 * 1000
    private val LANDSCAPE = listOf("library_header.jpg", "header.jpg")
    private val PORTRAIT = listOf("library_600x900.jpg", "library_capsule.jpg")

    private fun libraryCache(context: Context) =
        File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/appcache/librarycache")

    private fun fetched(context: Context, appId: Int) = File(context.filesDir, "steam-art/$appId")

    private fun find(context: Context, appId: Int, names: List<String>): File? {
        val cache = libraryCache(context)
        val dir = File(cache, appId.toString())
        val dirs = listOf(dir) + (dir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList())
        for (name in names) {
            for (d in dirs) File(d, name).takeIf { it.isFile && it.length() > 0 }?.let { return it }
            File(cache, "${appId}_$name").takeIf { it.isFile && it.length() > 0 }?.let { return it }
        }
        return null
    }

    private fun landscape(context: Context, appId: Int): File? =
        find(context, appId, LANDSCAPE) ?: File(fetched(context, appId), "header.jpg").takeIf { it.isFile }

    fun resolve(context: Context, appId: Int): File? = landscape(context, appId) ?: find(context, appId, PORTRAIT)

    fun fetchMissing(context: Context, appIds: List<Int>): Boolean {
        var changed = false
        for (appId in appIds) {
            if (landscape(context, appId) != null) continue
            val dir = fetched(context, appId)
            val miss = File(dir, "header.none")
            if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS) continue
            dir.mkdirs()
            val bytes = get("$CDN$appId/header.jpg")
            if (bytes == null) { miss.writeText(""); continue }
            val tmp = File(dir, "header.jpg.tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(File(dir, "header.jpg"))) { miss.delete(); changed = true }
        }
        return changed
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "$url: ${e.message}"); null
        } finally {
            c.disconnect()
        }
    }
}
