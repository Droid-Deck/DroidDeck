package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.Library
import java.io.File

/**
 * How a store game is started inside the session. The shortcut Steam gets can only name an exe,
 * so when a game needs arguments or environment the install writes a small `.droiddeck-launch.bat`
 * beside it and the shortcut runs that: Proton's steam.exe shim hands a .bat to Wine's cmd, the
 * script sets the variables, steps into the exe's folder and starts it, and waits for it as a
 * batch file does, so Steam sees the game running.
 *
 * Epic's online sign-in is a fresh exchange code per launch (it expires in minutes), which no fixed
 * command line can carry: [prepare] mints one right before a launch and leaves it in
 * `.droiddeck-epic-code`; the script reads the file, deletes it and passes the AUTH arguments. With
 * no file the game starts in its offline identity mode, which most EOS titles accept.
 */
object StoreLaunch {
    private const val TAG = "StoreLaunch"
    const val LAUNCHER = ".droiddeck-launch.bat"
    const val EPIC_CODE = ".droiddeck-epic-code"

    /**
     * Writes the launcher for [sidecar] when it needs one (arguments, environment, or an Epic
     * sign-in), removes a stale one otherwise, and returns the sidecar with [StoreGameSidecar.launcher] set.
     */
    fun writeLauncher(folder: File, sidecar: StoreGameSidecar): StoreGameSidecar {
        val needs = sidecar.args.isNotEmpty() || sidecar.env.isNotEmpty() || sidecar.store == Store.EPIC
        val file = File(folder, LAUNCHER)
        if (!needs) { file.delete(); return sidecar.copy(launcher = null) }
        file.writeText(launcherText(sidecar))
        return sidecar.copy(launcher = LAUNCHER)
    }

    /** The batch file's text; pure, so it can be checked without a folder. CRLF line ends, as cmd expects. */
    fun launcherText(sidecar: StoreGameSidecar): String {
        val exe = sidecar.exe.replace('/', '\\')
        val dir = exe.substringBeforeLast('\\', "")
        val lines = ArrayList<String>()
        lines += "@echo off"
        lines += "setlocal"
        lines += "rem Written by DroidDeck for ${sidecar.store.label}: ${sidecar.title}. Steam's shortcut runs this file."
        lines += "cd /d \"%~dp0$dir\""
        for ((k, v) in sidecar.env) if (ENV_NAME.matches(k)) lines += "set \"$k=${v.replace("\"", "")}\""
        lines += "set \"DD_AUTH=\""
        if (sidecar.store == Store.EPIC) {
            lines += "if not exist \"%~dp0$EPIC_CODE\" goto run"
            lines += "set /p DD_CODE=<\"%~dp0$EPIC_CODE\""
            lines += "del \"%~dp0$EPIC_CODE\""
            lines += "set \"DD_AUTH=-AUTH_LOGIN=unused -AUTH_PASSWORD=%DD_CODE% -AUTH_TYPE=exchangecode\""
            lines += ":run"
        }
        val args = sidecar.args.joinToString(" ") { quoteArg(it) }
        lines += "\"%~dp0$exe\"" + (if (args.isNotEmpty()) " $args" else "") + " %DD_AUTH%"
        return lines.joinToString("\r\n") + "\r\n"
    }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** An argument on cmd's line: quoted when it has a space and is not already quoted; control characters dropped. */
    fun quoteArg(arg: String): String {
        val clean = arg.filter { it >= ' ' && it != '\u007f' }
        return if (' ' in clean && !clean.contains('"')) "\"$clean\"" else clean
    }

    /**
     * Everything a launch needs done first, then [onReady] on the main thread: for an Epic game a
     * fresh exchange code written beside its launcher (a few seconds at most; the launch goes
     * ahead without one when the store cannot be reached). Other stores need nothing.
     */
    fun prepare(context: Context, store: Store, id: String, onReady: () -> Unit) {
        if (store != Store.EPIC) { onReady(); return }
        val app = context.applicationContext
        Thread({
            try {
                val game = StoreInstallRoot.gameFolders(app).firstOrNull { folder -> StoreGameSidecar.read(folder)?.let { it.store == store && it.id == id } == true }
                if (game != null) {
                    val code = StoresState.backend(Store.EPIC)?.let { (it as? EpicLaunchSupport)?.exchangeCode(app) }
                    val file = File(game, EPIC_CODE)
                    if (code != null) { file.writeText(code); Log.i(TAG, "epic: exchange code ready for $id") }
                    else { file.delete(); Log.w(TAG, "epic: no exchange code; launching offline") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "epic prepare: ${e.message}")
            }
            StoresState.post(onReady)
        }, "stores-launch-prep").start()
    }

    /** The same, for a game the Games tab launches: a store's game by its [Library.SteamGame.source]. */
    fun prepare(context: Context, game: Library.SteamGame, onReady: () -> Unit) {
        val store = Store.byId(game.source)
        val id = game.storeId
        if (store == null || id == null) { onReady(); return }
        prepare(context, store, id, onReady)
    }
}

/** What the Epic backend adds for launches: a short-lived exchange code for the signed-in account. */
interface EpicLaunchSupport {
    fun exchangeCode(context: Context): String?
}
