package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.Locale

/**
 * Orca (the GNOME screen reader) in the Linux runtime: putting it there (droiddeck-orca-setup,
 * the same offline-pacman path as Flatpak) and the Accessibility tab's state.
 *
 * Installing runs in a proot of its own under a fake root, like FlatpakManager.setup: pacman
 * refuses any uid but 0. A session starting meanwhile leaves it running (OrphanReaper). Once it
 * is installed the app keeps Orca enabled, and every session that enters the runtime starts it:
 * SessionService hands BL_ORCA=1 to the session script, and droiddeck-orca does the rest (Steam,
 * a program, or the Plasma desktop).
 */
object OrcaManager {
    private const val TAG = "OrcaManager"
    private const val SETUP = "/usr/local/bin/droiddeck-orca-setup"
    private const val ORCA = "usr/bin/orca"
    /** The running session's droiddeck-orca watches this file and opens Orca's preferences. */
    private const val SETTINGS_REQUEST = "root/.config/droiddeck/orca-settings"

    /** Orca is in the runtime. */
    fun isInstalled(context: Context): Boolean =
        File(LinuxRuntime.rootDir(context), ORCA).isFile

    /** Whether sessions start Orca; the Accessibility tab's switch. */
    fun enabled(context: Context): Boolean = SessionPrefs.orcaEnabled(context)

    fun setEnabled(context: Context, on: Boolean) = SessionPrefs.setOrcaEnabled(context, on)

    /**
     * Installs Orca, then turns it on so the next session reads the screen. [onProgress] gets the
     * script's "== STEP" milestones (percent unknown, -1). Null on success, else what went wrong.
     */
    fun install(context: Context, onProgress: (String, Int) -> Unit): String? {
        if (!LinuxRuntime.isInstalled(context)) return context.getString(R.string.accessibility_requires_runtime)
        if (isInstalled(context)) {
            setEnabled(context, true)
            return null
        }
        var failure: String? = null
        val status = GuestCommand.run(context, listOf("/bin/bash", SETUP), fakeRoot = true, logName = "orca-setup") { line ->
            Log.i(TAG, "setup: $line")
            when {
                line.startsWith("== STEP ") -> onProgress(line.removePrefix("== STEP "), -1)
                line.startsWith("== FAIL ") -> failure = line.removePrefix("== FAIL ")
            }
        }
        return when {
            failure != null -> failure
            status != 0 -> context.getString(R.string.accessibility_install_failed, status)
            !isInstalled(context) -> context.getString(R.string.accessibility_install_unfinished)
            else -> {
                setEnabled(context, true)
                null
            }
        }
    }

    /**
     * The Accessibility tab's "Open Orca settings": the running session's droiddeck-orca watcher
     * answers the request on the session's own display; without a session the request waits for
     * the next one.
     */
    fun requestSettings(context: Context) {
        runCatching {
            val file = File(LinuxRuntime.rootDir(context), SETTINGS_REQUEST)
            file.parentFile?.mkdirs()
            file.writeText("")
        }
    }

    /**
     * The locale droiddeck-orca points Orca and speech-dispatcher at, from the app's interface
     * language: the preferences dialog is localized and the default voice matches. droiddeck-orca-
     * setup generated exactly these locales during install. Anything the runtime has no locale
     * for falls back to English.
     */
    fun languageTag(): String = languageTag(Locale.getDefault())

    fun languageTag(locale: Locale): String = when (locale.language) {
        "zh" -> "zh_CN"
        "es" -> "es_ES"
        "fr" -> "fr_FR"
        "ja" -> "ja_JP"
        "ko" -> "ko_KR"
        "ru" -> "ru_RU"
        else -> "en_US"
    }
}
