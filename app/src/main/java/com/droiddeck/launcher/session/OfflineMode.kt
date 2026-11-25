package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object OfflineMode {
    private const val TAG = "OfflineMode"
    private const val KEY = "steam_offline"
    private const val AUTO_KEY = "steam_offline_auto"
    private val WANTS = Regex("""^(\s*"WantsOfflineMode"\s*")([01])(".*)$""")
    private val SKIP = Regex("""^(\s*"SkipOfflineModeWarning"\s*")([01])(".*)$""")
    private val REMEMBER = Regex(""""RememberPassword"\s*"1"""")
    private val PERSONA = Regex(""""PersonaName"\s*"([^"]*)"""")
    private const val EVAL_TIMEOUT_MS = 10_000L

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun loginUsers(root: File) = File(root, "root/.local/share/Steam/config/loginusers.vdf")

    private fun x64LoginUsers(root: File) = File(root, "root/.droiddeck-x64/.local/share/Steam/config/loginusers.vdf")

    private fun activeLoginUsers(context: Context, root: File) =
        x64LoginUsers(root).takeIf { SessionPrefs.activeSteamClientArch(context) == "x86_64" && it.isFile }
            ?: loginUsers(root)

    fun account(context: Context): String? {
        val file = activeLoginUsers(context, LinuxRuntime.rootDir(context))
        val text = try {
            if (file.isFile) file.readText() else return null
        } catch (e: Exception) {
            Log.w(TAG, "could not read loginusers.vdf", e); return null
        }
        if (!REMEMBER.containsMatchIn(text)) return null
        return PERSONA.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun steamWants(context: Context, root: File): Boolean? {
        val file = activeLoginUsers(context, root)
        val lines = try {
            if (file.isFile) file.readLines() else return null
        } catch (e: Exception) {
            Log.w(TAG, "could not read loginusers.vdf", e); return null
        }
        val values = lines.mapNotNull { WANTS.matchEntire(it)?.groupValues?.get(2) }
        return if (values.isEmpty()) null else "1" in values
    }

    internal fun manual(steamWants: Boolean?, automatic: Boolean, saved: Boolean): Boolean =
        if (steamWants == null) saved else steamWants && !automatic

    internal fun offline(manual: Boolean, online: Boolean): Boolean = manual || !online

    fun enabled(context: Context): Boolean {
        val prefs = prefs(context)
        return manual(steamWants(context, LinuxRuntime.rootDir(context)), prefs.getBoolean(AUTO_KEY, false), prefs.getBoolean(KEY, false))
    }

    fun active(context: Context): Boolean =
        offline(enabled(context), com.droiddeck.launcher.frontend.AutoComponents.isOnline(context))

    fun setEnabled(context: Context, on: Boolean) {
        val offline = write(context, LinuxRuntime.rootDir(context), on)
        if (SessionState.running && (SessionState.mode == SessionService.MODE_STEAM || SessionState.mode == SessionService.MODE_DESKTOP)) {
            val app = context.applicationContext
            Thread({ switchLive(app, offline) }, "steam-offline").start()
        }
    }

    fun apply(context: Context, root: File): Boolean = write(context, root, saved(context, root))

    fun follow(context: Context): Boolean? = switchLive(context, remember(context, saved(context, LinuxRuntime.rootDir(context))))

    private fun saved(context: Context, root: File): Boolean {
        val prefs = prefs(context)
        return manual(steamWants(context, root), prefs.getBoolean(AUTO_KEY, false), prefs.getBoolean(KEY, false))
    }

    private fun remember(context: Context, manual: Boolean): Boolean {
        val online = com.droiddeck.launcher.frontend.AutoComponents.isOnline(context)
        val offline = offline(manual, online)
        prefs(context).edit().putBoolean(KEY, manual).putBoolean(AUTO_KEY, offline && !manual).apply()
        Log.i(TAG, "manual=$manual online=$online offline=$offline")
        return offline
    }

    private fun switchLive(context: Context, offline: Boolean): Boolean? {
        val answer = try {
            AgentGuest.steamEval(context, "$SWITCH($offline)", EVAL_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "steam not reachable: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val result = if (answer.optBoolean("ok")) answer.optString("value") else answer.optJSONObject("error")?.optString("message")
        Log.i(TAG, "live offline=$offline: $result")
        return when (result) {
            "switched" -> true
            "same" -> false
            else -> null
        }
    }

    private fun write(context: Context, root: File, manual: Boolean): Boolean {
        val offline = remember(context, manual)
        applyTo(loginUsers(root), offline)
        applyTo(x64LoginUsers(root), offline)
        return offline
    }

    private fun applyTo(file: File, offline: Boolean) {
        if (!file.isFile) return
        val want = if (offline) "1" else "0"
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

    private val SWITCH = """(async want => {
          const user = globalThis.SteamClient?.User;
          if (typeof user?.GoOffline !== 'function' || typeof user?.GoOnline !== 'function') return "missing";
          const current = await new Promise(done => {
            let handle = null, value;
            const finish = v => { clearTimeout(timer); try { handle?.unregister(); } catch (e) {} done(v); };
            const timer = setTimeout(() => finish(null), 4000);
            handle = user.RegisterForCurrentUserChanges(u => { value = !!u?.bIsOfflineMode; if (handle) finish(value); });
            if (value !== undefined) finish(value);
          });
          if (current === null) return "unknown";
          if (current === want) return "same";
          if (want) user.GoOffline(); else user.GoOnline();
          return "switched";
        })"""
}
