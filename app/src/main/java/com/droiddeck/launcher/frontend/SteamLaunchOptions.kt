package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import java.io.File

/**
 * Launch options set in DroidDeck for a Steam game. They replace the ones in Steam's Properties: the running client is told at
 * once, and every account's `localconfig.vdf` gets them before each session starts, so a change made in Steam lasts only
 * until then. Start without Steam reads the same file.
 */
object SteamLaunchOptions {
    private const val TAG = "SteamLaunchOptions"
    private val STEAM = listOf("UserLocalConfigStore", "Software", "Valve", "Steam")

    /** The options the game starts with: the ones set here, else Steam's own. */
    fun current(context: Context, appId: Int): String =
        SessionPrefs.steamGameLaunch(context, appId) ?: steam(SessionPrefs.activeSteamRoot(context), appId).orEmpty()

    fun set(context: Context, appId: Int, options: String) {
        SessionPrefs.setSteamGameLaunch(context, appId, options)
        val value = options.trim()
        if (SteamLiveShortcuts.setLaunchOptions(context, appId.toLong(), value)) return
        // A running client writes its own copy back when it exits; the next session start puts these over it again.
        if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM) return
        for (arch in listOf("arm64", "x86_64")) write(SessionPrefs.steamRoot(context, arch), mapOf(appId to value))
    }

    /** Before a session starts: every game's options set here go into each account's local config under [steamHome]. */
    fun stage(context: Context, steamHome: File) {
        val wanted = SessionPrefs.steamGameLaunches(context)
        if (wanted.isNotEmpty()) write(steamHome, wanted)
    }

    /** Steam's own launch options for [appId] in the signed-in account under [steamHome]; null when it has none. */
    internal fun steam(steamHome: File, appId: Int): String? {
        val config = AddedGames.accountConfig(steamHome) ?: return null
        val text = runCatching { File(config, "localconfig.vdf").readText() }.getOrNull() ?: return null
        return launchOptions(text, appId)
    }

    private fun write(steamHome: File, wanted: Map<Int, String>) {
        for (user in File(steamHome, "userdata").listFiles { f -> f.isDirectory }.orEmpty()) {
            val config = File(user, "config/localconfig.vdf")
            val text = runCatching { config.readText() }.getOrNull() ?: continue
            val updated = wanted.entries.fold(text) { acc, (appId, options) -> withLaunchOptions(acc, appId, options) ?: acc }
            if (updated == text) continue
            val staged = File(config.parentFile, config.name + ".staged")
            try {
                staged.writeText(updated)
                if (!staged.renameTo(config)) {
                    staged.delete()
                    Log.w(TAG, "could not write launch options to ${config.path}")
                }
            } catch (e: Exception) {
                staged.delete()
                Log.w(TAG, "could not write launch options to ${config.path}", e)
            }
        }
    }

    /** The launch options [text], a `localconfig.vdf`, has for [appId]; null when it has none. */
    internal fun launchOptions(text: String, appId: Int): String? {
        val app = section(text, STEAM + "apps" + appId.toString()) ?: return null
        return entries(text, app.valueStart, app.end).firstOrNull { !it.section && it.key.equals("LaunchOptions", true) }
            ?.let { unescape(text.substring(it.valueStart + 1, it.end)) }
    }

    /** [text], a `localconfig.vdf`, with [appId]'s launch options set to [options]; null when it is not a config this can edit. */
    internal fun withLaunchOptions(text: String, appId: Int, options: String): String? {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        var updated = text
        var path = STEAM
        for (key in listOf("apps", appId.toString())) {
            updated = withSection(updated, section(updated, path) ?: return null, key, newline) ?: return null
            path = path + key
        }
        val app = section(updated, path) ?: return null
        val value = "\"" + escape(options) + "\""
        entries(updated, app.valueStart, app.end).firstOrNull { !it.section && it.key.equals("LaunchOptions", true) }?.let {
            return updated.replaceRange(it.valueStart, it.end + 1, value)
        }
        val lineStart = closingLine(updated, app) ?: return null
        val indent = updated.substring(lineStart, app.end) + "\t"
        return updated.substring(0, lineStart) + indent + "\"LaunchOptions\"\t\t" + value + newline + updated.substring(lineStart)
    }

    /** A key directly inside a section. Its value runs from [valueStart] to [end]: a quoted string, or a section's braces. */
    private class Entry(val key: String, val valueStart: Int, val end: Int, val section: Boolean)

    /** The section at [path] from the top of [text], matching keys as Steam does, without regard to case. */
    private fun section(text: String, path: List<String>): Entry? {
        var found: Entry? = null
        for (key in path) {
            found = entries(text, found?.valueStart ?: -1, found?.end ?: text.length)
                .firstOrNull { it.section && it.key.equals(key, true) } ?: return null
        }
        return found
    }

    /** The entries directly between the braces at [open] and [close] (-1 and the end of [text] for the top level). */
    private fun entries(text: String, open: Int, close: Int): List<Entry> {
        val found = ArrayList<Entry>()
        var key: String? = null
        var i = open + 1
        while (i < close) {
            when {
                text[i] == '"' -> {
                    val end = stringEnd(text, i) ?: return found
                    if (key == null) key = unescape(text.substring(i + 1, end)) else { found += Entry(key, i, end, false); key = null }
                    i = end + 1
                }
                text[i] == '{' -> {
                    val end = sectionEnd(text, i) ?: return found
                    key?.let { found += Entry(it, i, end, true) }
                    key = null
                    i = end + 1
                }
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) close else it }
                else -> i++
            }
        }
        return found
    }

    private fun stringEnd(text: String, open: Int): Int? {
        var i = open + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun sectionEnd(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '"' -> i = stringEnd(text, i) ?: return null
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /** [text] with an empty section [key] at the end of [parent] when it has none; null when [parent] is not laid out as Steam writes it. */
    private fun withSection(text: String, parent: Entry, key: String, newline: String): String? {
        if (entries(text, parent.valueStart, parent.end).any { it.section && it.key.equals(key, true) }) return text
        val lineStart = closingLine(text, parent) ?: return null
        val indent = text.substring(lineStart, parent.end) + "\t"
        return text.substring(0, lineStart) + "$indent\"$key\"$newline$indent{$newline$indent}$newline" + text.substring(lineStart)
    }

    /** Where the line holding [section]'s closing brace starts, when nothing but indentation comes before the brace on it. */
    private fun closingLine(text: String, section: Entry): Int? {
        val lineStart = text.lastIndexOf('\n', section.end - 1) + 1
        return lineStart.takeIf { lineStart > section.valueStart && text.substring(lineStart, section.end).all { it == '\t' || it == ' ' } }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String) = value.replace(Regex("\\\\(.)"), "$1")
}
