package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A Steam game's launch options, kept only where Steam keeps them: in the running client, else in the signed-in account's
 * `localconfig.vdf` of the active client. The Edit screen and Steam's Properties read and change the same value.
 */
object SteamLaunchOptions {
    private const val TAG = "SteamLaunchOptions"
    private val STEAM = listOf("UserLocalConfigStore", "Software", "Valve", "Steam")

    fun current(context: Context, appId: Int): String =
        SteamLiveShortcuts.launchOptions(context, appId.toLong()) ?: steam(SessionPrefs.activeSteamRoot(context), appId).orEmpty()

    enum class Saved { YES, NO, DESKTOP }

    /** Saves [options] only while Steam still has [shown], the value the editor showed. */
    fun set(context: Context, appId: Int, options: String, shown: String): Saved {
        val value = options.trim()
        // A session's client writes its own copy over the file when it exits; the live calls reach only a Steam session's client.
        if (SessionState.running && SessionState.mode == SessionService.MODE_DESKTOP) return Saved.DESKTOP
        if (SessionState.running && SessionState.mode == SessionService.MODE_STEAM) {
            val live = SteamLiveShortcuts.launchOptions(context, appId.toLong()) ?: return Saved.NO
            if (live.trim() != shown.trim()) return Saved.NO
            return if (SteamLiveShortcuts.setLaunchOptions(context, appId.toLong(), value)) Saved.YES else Saved.NO
        }
        val home = SessionPrefs.activeSteamRoot(context)
        if (steam(home, appId).orEmpty().trim() != shown.trim()) return Saved.NO
        return if (write(home, appId, value)) Saved.YES else Saved.NO
    }

    internal fun steam(steamHome: File, appId: Int): String? {
        val config = AddedGames.accountConfig(steamHome) ?: return null
        val text = runCatching { File(config, "localconfig.vdf").readText() }.getOrNull() ?: return null
        return launchOptions(text, appId)
    }

    internal fun write(steamHome: File, appId: Int, options: String): Boolean {
        val config = AddedGames.accountConfig(steamHome)?.let { File(it, "localconfig.vdf") } ?: return false
        val text = runCatching { config.readText() }.getOrNull() ?: return false
        val updated = withLaunchOptions(text, appId, options) ?: return false
        if (updated == text) return true
        val staged = File(config.parentFile, config.name + ".staged")
        return try {
            staged.writeText(updated)
            if (config.readText() != text) {
                staged.delete()
                return false
            }
            Files.move(staged.toPath(), config.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "could not write launch options to ${config.path}", e)
            false
        }
    }

    internal fun launchOptions(text: String, appId: Int): String? {
        val app = section(text, STEAM + "apps" + appId.toString()) ?: return null
        return entries(text, app.valueStart, app.end).firstOrNull { !it.section && it.key.equals("LaunchOptions", true) }
            ?.let { unescape(text.substring(it.valueStart + 1, it.end)) }
    }

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

    /** Its value runs from [valueStart] to [end]: a quoted string, or a section's braces. */
    private class Entry(val key: String, val valueStart: Int, val end: Int, val section: Boolean)

    /** Steam matches keys without regard to case. */
    private fun section(text: String, path: List<String>): Entry? {
        var found: Entry? = null
        for (key in path) {
            found = entries(text, found?.valueStart ?: -1, found?.end ?: text.length)
                .firstOrNull { it.section && it.key.equals(key, true) } ?: return null
        }
        return found
    }

    /** [open] -1 and [close] at the end of [text] mean the top level. */
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

    private fun withSection(text: String, parent: Entry, key: String, newline: String): String? {
        if (entries(text, parent.valueStart, parent.end).any { it.section && it.key.equals(key, true) }) return text
        val lineStart = closingLine(text, parent) ?: return null
        val indent = text.substring(lineStart, parent.end) + "\t"
        return text.substring(0, lineStart) + "$indent\"$key\"$newline$indent{$newline$indent}$newline" + text.substring(lineStart)
    }

    private fun closingLine(text: String, section: Entry): Int? {
        val lineStart = text.lastIndexOf('\n', section.end - 1) + 1
        return lineStart.takeIf { lineStart > section.valueStart && text.substring(lineStart, section.end).all { it == '\t' || it == ' ' } }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String) = value.replace(Regex("\\\\(.)"), "$1")
}
