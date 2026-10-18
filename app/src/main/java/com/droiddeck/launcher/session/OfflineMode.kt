package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object OfflineMode {
    private const val TAG = "OfflineMode"
    private const val KEY = "steam_offline"
    private val WANTS = Regex("""^(\s*"WantsOfflineMode"\s*")([01])(".*)$""")
    private val SKIP = Regex("""^(\s*"SkipOfflineModeWarning"\s*")([01])(".*)$""")
    private val REMEMBER = Regex(""""RememberPassword"\s*"1"""")
    private val PERSONA = Regex(""""PersonaName"\s*"([^"]*)"""")

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun loginUsers(root: File) = File(root, "root/.local/share/Steam/config/loginusers.vdf")

    private fun x64LoginUsers(root: File) = File(root, "root/.droiddeck-x64/.local/share/Steam/config/loginusers.vdf")

    fun account(context: Context): String? {
        val root = LinuxRuntime.rootDir(context)
        // Until its first start the x86-64 client has no sign-in; droiddeck-steam-x64 --sync-login copies the arm64 one.
        val file = x64LoginUsers(root).takeIf { SessionPrefs.activeSteamClientArch(context) == "x86_64" && it.isFile }
            ?: loginUsers(root)
        val text = try {
            if (file.isFile) file.readText() else return null
        } catch (e: Exception) {
            Log.w(TAG, "could not read loginusers.vdf", e); return null
        }
        if (!REMEMBER.containsMatchIn(text)) return null
        return PERSONA.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        apply(context, LinuxRuntime.rootDir(context))
    }

    fun apply(context: Context, root: File) {
        applyTo(context, loginUsers(root))
        applyTo(context, x64LoginUsers(root))
    }

    private fun applyTo(context: Context, file: File) {
        if (!file.isFile) return
        val want = if (enabled(context)) "1" else "0"
        try {
            val lines = file.readLines()
            val out = ArrayList<String>(lines.size + 4)
            var sawWants = false
            var sawSkip = false
            var changed = false
            for (line in lines) {
                val wants = WANTS.matchEntire(line)
                val skip = SKIP.matchEntire(line)
                when {
                    wants != null -> {
                        sawWants = true
                        if (wants.groupValues[2] != want) changed = true
                        out.add(wants.groupValues[1] + want + wants.groupValues[3])
                    }
                    skip != null -> {
                        sawSkip = true
                        if (skip.groupValues[2] != want) changed = true
                        out.add(skip.groupValues[1] + want + skip.groupValues[3])
                    }
                    else -> {
                        out.add(line)
                        if (line.contains("\"AccountName\"")) {
                            val indent = line.takeWhile { it == ' ' || it == '\t' }
                            if (!lines.any { WANTS.matchEntire(it) != null }) {
                                out.add("$indent\"WantsOfflineMode\"\t\t\"$want\""); changed = true
                            }
                            if (!lines.any { SKIP.matchEntire(it) != null }) {
                                out.add("$indent\"SkipOfflineModeWarning\"\t\t\"$want\""); changed = true
                            }
                        }
                    }
                }
            }
            if (!changed) return
            val staged = File(file.parentFile, "loginusers.vdf.staged")
            staged.writeText(out.joinToString("\n", postfix = "\n"))
            if (!staged.renameTo(file)) {
                staged.delete()
                Log.w(TAG, "could not replace loginusers.vdf")
                return
            }
            Log.i(TAG, "offline=$want (wants=$sawWants skip=$sawSkip)")
        } catch (e: Exception) {
            Log.w(TAG, "could not write loginusers.vdf", e)
        }
    }
}
