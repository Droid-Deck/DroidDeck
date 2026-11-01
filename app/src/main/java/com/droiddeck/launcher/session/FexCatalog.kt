package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.Downloader
import org.json.JSONObject
import java.io.File

object FexCatalog {
    private const val TAG = "FexCatalog"
    const val CATALOG_URL = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/fex.json"
    private const val RELEASES = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/"
    private const val REFRESH_MS = 10 * 60 * 1000L
    private val TAG_FORMAT = Regex("""fex-\d+-r\d+""")
    private val VERSION_FORMAT = Regex("""\d+-r\d+""")
    private val SHA256 = Regex("[0-9a-f]{64}")

    data class Build(val tag: String, val url: String, val sha256: String)

    @Volatile private var refreshedAt = 0L

    private fun file(context: Context) = File(context.filesDir, "fex-catalog.json")

    internal fun parse(text: String): Build? = runCatching {
        val o = JSONObject(text)
        val release = o.getString("release")
        val url = o.getString("url")
        val sha = o.getString("sha256").lowercase()
        val version = o.optString("version")
        val tag = when {
            TAG_FORMAT.matches(release) -> release
            release == "fex" && VERSION_FORMAT.matches(version) -> "fex-$version"
            else -> return@runCatching null
        }
        Build(tag, url, sha).takeIf { url == "$RELEASES$release/fex.tzst" && SHA256.matches(sha) }
    }.getOrNull()

    fun cached(context: Context): Build? = runCatching { parse(file(context).readText()) }.getOrNull()

    @Synchronized
    fun refresh(context: Context, now: Long = System.currentTimeMillis()): Build? {
        if (now - refreshedAt < REFRESH_MS) return cached(context)
        refreshedAt = now
        val text = Downloader.downloadDirect(CATALOG_URL) ?: return cached(context)
        val build = parse(text) ?: run {
            Log.w(TAG, "fex.json not usable; keeping the last one")
            return cached(context)
        }
        runCatching {
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) tmp.delete()
        }
        return build
    }

    fun environment(build: Build, sources: List<String> = DownloadSource.candidates(build.url)): List<String> = listOf(
        "BL_FEX_TAG=${build.tag}",
        "BL_FEX_SHA256=${build.sha256}",
        "BL_FEX_URLS=" + sources.joinToString(" "),
    )
}
