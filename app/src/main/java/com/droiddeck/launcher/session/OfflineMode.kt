package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Starting the Steam client signed out of Valve's servers.
 *
 * The client can run without a connection - installed games launch, Proton and the frame-gen are
 * all local - but only if it has credentials from an earlier successful sign-in. Two keys in
 * loginusers.vdf decide what it does when it cannot reach a CM: WantsOfflineMode makes it go
 * offline on its own, SkipOfflineModeWarning stops it asking first. Both are read once, while the
 * client starts, which is why this belongs to the main screen and not to the in-session drawer:
 * changing it during a session would write the file and change nothing until the next launch.
 *
 * The client rewrites this file when it exits, so the request is kept as our own preference and
 * written into the file again at every session start.
 */
object OfflineMode {
    private const val TAG = "OfflineMode"
    private const val KEY = "steam_offline"
    private val WANTS = Regex("""^(\s*"WantsOfflineMode"\s*")([01])(".*)$""")
    private val SKIP = Regex("""^(\s*"SkipOfflineModeWarning"\s*")([01])(".*)$""")

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun loginUsers(root: File) = File(root, "root/.local/share/Steam/config/loginusers.vdf")

    /** The account the client could sign in as offline: its name, "" when it recorded none, null when there is none. */
    fun account(context: Context): String? {
        val file = loginUsers(LinuxRuntime.rootDir(context))
        val text = try {
            if (file.isFile) file.readText() else return null
        } catch (e: Exception) {
            Log.w(TAG, "could not read loginusers.vdf", e); return null
        }
        return SteamAccounts.activeLabel(text)
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        apply(context, LinuxRuntime.rootDir(context))
    }

    /**
     * Write the request into loginusers.vdf. Every account block gets the same answer: the client
     * signs in as one of them and we have no say in which. Keys the file does not carry are added
     * beside the account's name; a file without an account block is left alone, since there is
     * nothing to be offline as.
     */
    fun apply(context: Context, root: File) {
        val file = loginUsers(root)
        if (!file.isFile) return
        val want = if (enabled(context)) "1" else "0"
        try {
            val out = rewrite(file.readLines(), want) ?: return
            val staged = File(file.parentFile, "loginusers.vdf.staged")
            staged.writeText(out.joinToString("\n", postfix = "\n"))
            if (!staged.renameTo(file)) {
                staged.delete()
                Log.w(TAG, "could not replace loginusers.vdf")
                return
            }
            Log.i(TAG, "offline=$want")
        } catch (e: Exception) {
            Log.w(TAG, "could not write loginusers.vdf", e)
        }
    }

    /** loginusers.vdf's lines with every account block asking for [want], or null when nothing changes. */
    internal fun rewrite(lines: List<String>, want: String): List<String>? {
        // Which lines belong to which account block (the braces one level inside "users"), so a
        // block that lacks a key gets it even when another account's block has it - with more
        // than one remembered account, a whole-file check left a newer account out.
        val blockOf = IntArray(lines.size) { -1 }
        val blockHasWants = ArrayList<Boolean>()
        val blockHasSkip = ArrayList<Boolean>()
        var depth = 0
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            if (t == "{") {
                depth++
                if (depth == 2) { blockHasWants.add(false); blockHasSkip.add(false) }
                continue
            }
            if (t == "}") { depth--; continue }
            if (depth >= 2 && blockHasWants.isNotEmpty()) {
                val b = blockHasWants.size - 1
                blockOf[i] = b
                if (WANTS.matchEntire(line) != null) blockHasWants[b] = true
                if (SKIP.matchEntire(line) != null) blockHasSkip[b] = true
            }
        }
        val out = ArrayList<String>(lines.size + 4)
        var changed = false
        for ((i, line) in lines.withIndex()) {
            val wants = WANTS.matchEntire(line)
            val skip = SKIP.matchEntire(line)
            when {
                wants != null -> {
                    if (wants.groupValues[2] != want) changed = true
                    out.add(wants.groupValues[1] + want + wants.groupValues[3])
                }
                skip != null -> {
                    if (skip.groupValues[2] != want) changed = true
                    out.add(skip.groupValues[1] + want + skip.groupValues[3])
                }
                else -> {
                    out.add(line)
                    if (line.contains("\"AccountName\"")) {
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        val b = blockOf[i]
                        val hasWants = if (b >= 0) blockHasWants[b] else lines.any { WANTS.matchEntire(it) != null }
                        val hasSkip = if (b >= 0) blockHasSkip[b] else lines.any { SKIP.matchEntire(it) != null }
                        if (!hasWants) {
                            out.add("$indent\"WantsOfflineMode\"\t\t\"$want\""); changed = true
                        }
                        if (!hasSkip) {
                            out.add("$indent\"SkipOfflineModeWarning\"\t\t\"$want\""); changed = true
                        }
                    }
                }
            }
        }
        return if (changed) out else null
    }
}
