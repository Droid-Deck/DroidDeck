package com.droiddeck.launcher.session

import com.droiddeck.launcher.frontend.AddedGames
import java.io.File

object SteamLocalConfig {
    class AppStats(val playtimeMinutes: Long, val lastPlayed: Long)

    fun appStats(steamRoot: File, appId: Int): AppStats? {
        val config = AddedGames.accountConfig(steamRoot) ?: return null
        val text = runCatching { File(config, "localconfig.vdf").readText() }.getOrNull() ?: return null
        return appStats(text, appId)
    }

    internal fun appStats(text: String, appId: Int): AppStats? {
        val apps = blocks(text, "apps").firstNotNullOfOrNull { blocks(it, appId.toString(), topLevel = true).firstOrNull() } ?: return null
        val minutes = PLAYTIME.find(apps)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val last = LAST_PLAYED.find(apps)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return AppStats(minutes, last)
    }

    private fun blocks(text: String, key: String, topLevel: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val quoted = "\"$key\""
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val end = text.indexOf('"', i + 1).takeIf { it > 0 } ?: return out
                    if ((!topLevel || depth == 0) && text.regionMatches(i, quoted, 0, quoted.length) && end == i + quoted.length - 1) {
                        val open = text.indexOfFirst(end + 1) { !it.isWhitespace() }
                        if (open >= 0 && text[open] == '{') {
                            val close = matching(text, open)
                            if (close > open) out += text.substring(open + 1, close)
                        }
                    }
                    i = end
                }
                else -> if (c == '/' && text.startsWith("//", i)) i = text.indexOf('\n', i).takeIf { it > 0 } ?: text.length
            }
            i++
        }
        return out
    }

    private fun matching(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
                '"' -> i = text.indexOf('"', i + 1).takeIf { it > 0 } ?: return -1
            }
            i++
        }
        return -1
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }

    private val PLAYTIME = Regex(""""Playtime"\s*"(\d+)"""")
    private val LAST_PLAYED = Regex(""""LastPlayed"\s*"(\d+)"""")
}
