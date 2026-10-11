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
        val args = sidecar.args.joinToString(" ") { quoteArg(it) }
        val run = "\"%~dp0$exe\"" + (if (args.isNotEmpty()) " $args" else "")
        if (sidecar.store == Store.EPIC) {
            // The one-shot code reaches the game on its command line only. cmd expands %DD_X% when it
            // parses the last line, so the variable is cleared on that same line before the game
            // starts: nothing of it is left in the environment the game inherits (and dumps).
            lines += "set \"$CODE_VAR=\""
            lines += "if exist \"%~dp0$EPIC_CODE\" set /p $CODE_VAR=<\"%~dp0$EPIC_CODE\""
            lines += "if exist \"%~dp0$EPIC_CODE\" del \"%~dp0$EPIC_CODE\""
            lines += "if defined $CODE_VAR goto signed"
            lines += run
            lines += "goto :eof"
            lines += ":signed"
            lines += "set \"$CODE_VAR=\" & $run -AUTH_LOGIN=unused -AUTH_PASSWORD=%$CODE_VAR% -AUTH_TYPE=exchangecode"
        } else {
            lines += run
        }
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
    /** The launcher's variable for the one-shot code; a plain name, cleared before the game starts. */
    private const val CODE_VAR = "DD_X"

    /**
     * Rewrites a game's launcher when its text is not what this build writes - an install from an
     * earlier build keeps its old .bat otherwise. Called before every Epic launch.
     */
    fun refreshLauncher(folder: File, sidecar: StoreGameSidecar) {
        if (sidecar.launcher != LAUNCHER) return
        val file = File(folder, LAUNCHER)
        val text = launcherText(sidecar)
        if (runCatching { file.readText() }.getOrNull() != text) {
            file.writeText(text)
            Log.i(TAG, "launcher rewritten for ${sidecar.id}")
        }
    }

    fun prepare(context: Context, store: Store, id: String, onReady: () -> Unit) {
        if (store != Store.EPIC) { onReady(); return }
        val app = context.applicationContext
        Thread({
            epicCode(app, id)
            StoresState.post(onReady)
        }, "stores-launch-prep").start()
    }

    /** What [epicCode] did, for the guest's request and the log: whether a code was written, and why not. */
    class CodeResult(val written: Boolean, val reason: String)

    /**
     * Mints an Epic exchange code for the installed game [id] and writes it as [EPIC_CODE] into the
     * very folder its sidecar and launcher are in, where the launcher .bat reads and deletes it.
     * Blocking (network). Logs `epic launch id=<id> code=yes|no reason=...` - never the code. Every
     * launch path comes here: the Games tab and Stores before they launch, and the compat tool for
     * any launch, the Steam client's own Play button included ([StoreLaunchRequests]).
     */
    fun epicCode(context: Context, id: String): CodeResult {
        val app = context.applicationContext
        val result = try {
            val folder = StoreInstallRoot.gameFolders(app).firstOrNull { f -> StoreGameSidecar.read(f)?.let { it.store == Store.EPIC && it.id == id } == true }
            val sidecar = folder?.let { StoreGameSidecar.read(it) }
            sidecar?.let { refreshLauncher(folder, it) }
            val options = sidecar?.epic
            if (folder == null) CodeResult(false, "not-installed")
            else if (options != null && !options.wantsCode) {
                File(folder, EPIC_CODE).delete()
                CodeResult(false, if (options.offline) "offline" else "eos-off")
            } else {
                val file = File(folder, EPIC_CODE)
                file.delete()
                val support = StoresState.backend(Store.EPIC) as? EpicLaunchSupport
                val code = support?.exchangeCode(app)
                when {
                    support == null -> CodeResult(false, "no-backend")
                    code != null -> { file.writeText(code); CodeResult(true, "ok") }
                    StoreAccounts.signedInAs(app, Store.EPIC) == null -> CodeResult(false, "signed-out")
                    support.signInExpired(app) -> { StoresState.markSignInExpired(Store.EPIC); CodeResult(false, "sign-in-expired") }
                    else -> CodeResult(false, "exchange-failed")
                }
            }
        } catch (e: Exception) {
            CodeResult(false, "error-" + e.javaClass.simpleName)
        }
        Log.i(TAG, "epic launch id=$id code=${if (result.written) "yes" else "no"} reason=${result.reason}")
        return result
    }

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
    /** True when the stored sign-in can no longer be refreshed: the user has to sign in again. */
    fun signInExpired(context: Context): Boolean
}
