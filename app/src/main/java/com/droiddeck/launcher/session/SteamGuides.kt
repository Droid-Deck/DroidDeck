package com.droiddeck.launcher.session

import android.content.Context
import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

object SteamGuides {
    class Guide(val id: String, val title: String, val author: String, val summary: String, val preview: String?, val stars: Int) {
        val url: String get() = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"
    }

    class Place(val url: String, val scroll: Float)

    fun list(appId: Long, cookies: String?): List<Guide>? = runCatching {
        val connection = URL("https://steamcommunity.com/app/$appId/guides/?browsefilter=toprated&browsesort=toprated").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) DroidDeck")
        cookies?.takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Cookie", it) }
        try {
            if (connection.responseCode != 200) return null
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    internal fun parse(html: String): List<Guide> = html.split("class=\"workshopItemCollectionContainer\"").drop(1).mapNotNull { card ->
        val id = ID.find(card)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = TITLE.find(card)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Guide(
            id, title,
            AUTHOR.find(card)?.groupValues?.get(1)?.let(::text).orEmpty(),
            SUMMARY.find(card)?.groupValues?.get(1)?.let(::text).orEmpty(),
            PREVIEW.find(card)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() },
            STARS.find(card)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }.distinctBy { it.id }

    fun place(context: Context, game: String): Place? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("$game.url", null)?.takeIf(::isGuide) ?: return null
        return Place(url, prefs.getFloat("$game.scroll", 0f))
    }

    fun savePlace(context: Context, game: String, url: String, scroll: Float) {
        if (!isGuide(url)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("$game.url", url).putFloat("$game.scroll", scroll.coerceIn(0f, 1f)).apply()
    }

    fun clear(context: Context, game: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("$game.url").remove("$game.scroll").apply()
    }

    internal fun isGuide(url: String): Boolean = GUIDE_URL.matches(url)

    private fun text(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim().replace(Regex("\\s+"), " ")

    private const val PREFS = "steam_guides"
    private val GUIDE_URL = Regex("https://steamcommunity\\.com/sharedfiles/filedetails/\\?id=\\d+(#[\\w-]*)?")
    private val ID = Regex("""filedetails/\?id=(\d+)""")
    private val TITLE = Regex("""class="workshopItemTitle">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val AUTHOR = Regex("""class="workshopItemAuthorName">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val SUMMARY = Regex("""class="workshopItemShortDesc">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val PREVIEW = Regex("""class="workshopItemPreviewImage" src="([^"]+)"""")
    private val STARS = Regex("""/(\d)-star\.png""")
}
