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

object SteamLaunchOptions {
    private const val TAG = "SteamLaunchOptions"
    private val STEAM = listOf("UserLocalConfigStore", "Software", "Valve", "Steam")

    fun current(context: Context, appId: Int): String? =
        SteamLiveShortcuts.launchOptions(context, appId.toLong()) ?: steam(SessionPrefs.activeSteamRoot(context), appId)

    enum class Saved { YES, NO, DESKTOP }

    fun set(context: Context, appId: Int, options: String, shown: String): Saved {
        val value = options.trim()
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
        if (section(text, STEAM) == null) return null
        return appValue(text, appId, "LaunchOptions").orEmpty()
    }

    internal fun write(steamHome: File, appId: Int, options: String): Boolean {
        val config = AddedGames.accountConfig(steamHome)?.let { File(it, "localconfig.vdf") } ?: return false
        val text = runCatching { config.readText() }.getOrNull() ?: return false
        val updated = withLaunchOptions(text, appId, options) ?: return false
        return writeConfig(config, text, updated)
    }

    internal fun steamAppSetting(steamHome: File, appId: Int, key: String, default: String): String? {
        val config = AddedGames.accountConfig(steamHome) ?: return null
        val text = runCatching { File(config, "localconfig.vdf").readText() }.getOrNull() ?: return null
        if (section(text, STEAM) == null) return null
        return appValue(text, appId, key) ?: default
    }

    internal fun writeSteamAppSetting(
        steamHome: File, appId: Int, key: String, expected: String, value: String?, default: String,
    ): Boolean {
        val config = AddedGames.accountConfig(steamHome)?.let { File(it, "localconfig.vdf") } ?: return false
        val text = runCatching { config.readText() }.getOrNull() ?: return false
        if (section(text, STEAM) == null || (appValue(text, appId, key) ?: default) != expected) return false
        val updated = (if (value == null) withoutAppValue(text, appId, key) else withAppValue(text, appId, key, value))
            ?: return false
        return writeConfig(config, text, updated)
    }

    private fun writeConfig(config: File, text: String, updated: String): Boolean {
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
            Log.w(TAG, "could not write Steam app settings to ${config.path}", e)
            false
        }
    }

    internal fun launchOptions(text: String, appId: Int): String? {
        return appValue(text, appId, "LaunchOptions")
    }

    private fun appValue(text: String, appId: Int, key: String): String? {
        val app = section(text, STEAM + "apps" + appId.toString()) ?: return null
        return entries(text, app.valueStart, app.end).firstOrNull { !it.section && it.key.equals(key, true) }
            ?.let { unescape(text.substring(it.valueStart + 1, it.end)) }
    }

    internal fun withLaunchOptions(text: String, appId: Int, options: String): String? =
        withAppValue(text, appId, "LaunchOptions", options)

    private fun withAppValue(text: String, appId: Int, key: String, rawValue: String): String? {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        var updated = text
        var path = STEAM
        for (pathPart in listOf("apps", appId.toString())) {
            updated = withSection(updated, section(updated, path) ?: return null, pathPart, newline) ?: return null
            path = path + pathPart
        }
        val app = section(updated, path) ?: return null
        val value = "\"" + escape(rawValue) + "\""
        entries(updated, app.valueStart, app.end).firstOrNull { !it.section && it.key.equals(key, true) }?.let {
            return updated.replaceRange(it.valueStart, it.end + 1, value)
        }
        val lineStart = closingLine(updated, app) ?: return null
        val indent = updated.substring(lineStart, app.end) + "\t"
        return updated.substring(0, lineStart) + indent + "\"${escape(key)}\"\t\t" + value + newline + updated.substring(lineStart)
    }

    private fun withoutAppValue(text: String, appId: Int, key: String): String {
        val app = section(text, STEAM + "apps" + appId.toString()) ?: return text
        val entry = entries(text, app.valueStart, app.end).firstOrNull { !it.section && it.key.equals(key, true) } ?: return text
        val lineStart = text.lastIndexOf('\n', entry.start - 1) + 1
        val lineEnd = text.indexOf('\n', entry.end + 1).let { if (it < 0) text.length else it }
        val onlyEntryOnLine = text.substring(lineStart, entry.start).all(Char::isWhitespace) &&
            text.substring(entry.end + 1, lineEnd).all(Char::isWhitespace)
        return if (onlyEntryOnLine) {
            text.removeRange(lineStart, if (lineEnd < text.length) lineEnd + 1 else lineEnd)
        } else {
            text.removeRange(entry.start, entry.end + 1)
        }
    }

    private class Entry(val key: String, val start: Int, val valueStart: Int, val end: Int, val section: Boolean)

    private fun section(text: String, path: List<String>): Entry? {
        var found: Entry? = null
        for (key in path) {
            found = entries(text, found?.valueStart ?: -1, found?.end ?: text.length)
                .firstOrNull { it.section && it.key.equals(key, true) } ?: return null
        }
        return found
    }

    private fun entries(text: String, open: Int, close: Int): List<Entry> {
        val found = ArrayList<Entry>()
        var key: String? = null
        var keyStart = -1
        var i = open + 1
        while (i < close) {
            when {
                text[i] == '"' -> {
                    val end = stringEnd(text, i) ?: return found
                    if (key == null) {
                        key = unescape(text.substring(i + 1, end))
                        keyStart = i
                    } else {
                        found += Entry(key, keyStart, i, end, false)
                        key = null
                        keyStart = -1
                    }
                    i = end + 1
                }
                text[i] == '{' -> {
                    val end = sectionEnd(text, i) ?: return found
                    key?.let { found += Entry(it, keyStart, i, end, true) }
                    key = null
                    keyStart = -1
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
