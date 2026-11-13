package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import java.io.File

object StoreLaunch {
    private const val TAG = "StoreLaunch"
    const val LAUNCHER = ".droiddeck-launch.bat"
    const val EPIC_CODE = ".droiddeck-epic-code"
    const val EPIC_ARGS = ".droiddeck-epic-args"
    const val UBISOFT_LAUNCH = ".droiddeck-ubisoft.json"
    private const val UBISOFT_SERVICE = "Z:\\usr\\local\\lib\\droiddeck-ubisoft\\droiddeck-upc.exe"
    const val EA_ENV = ".droiddeck-ea-env"
    /** A sleeping stand-in that the launch hook installs; older EA SDKs require a running Origin process. */
    const val EA_ORIGIN = "C:\\Program Files (x86)\\Origin\\Origin.exe"

    fun writeLauncher(folder: File, sidecar: StoreGameSidecar): StoreGameSidecar {
        val needs = sidecar.args.isNotEmpty() || sidecar.env.isNotEmpty() || sidecar.store == Store.EPIC || sidecar.store == Store.EA || sidecar.store == Store.UBISOFT || sidecar.store == Store.ROCKSTAR
        val file = File(folder, LAUNCHER)
        if (sidecar.store == Store.ROCKSTAR && java.nio.file.Files.isSymbolicLink(file.toPath())) return sidecar.copy(launcher = null)
        if (sidecar.store == Store.ROCKSTAR && !com.droiddeck.launcher.stores.rockstar.RockstarLaunch.supports(sidecar.id)) {
            file.delete()
            return sidecar.copy(launcher = null)
        }
        if (!needs) { file.delete(); return sidecar.copy(launcher = null) }
        file.writeText(launcherText(sidecar))
        return sidecar.copy(launcher = LAUNCHER)
    }

    fun launcherText(sidecar: StoreGameSidecar): String {
        val exe = sidecar.exe.replace('/', '\\')
        val dir = sidecar.workdir?.let { if (it.isEmpty()) "" else it.replace('/', '\\') }
            ?: exe.substringBeforeLast('\\', "")
        val lines = ArrayList<String>()
        lines += "@echo off"
        lines += "setlocal"
        if (sidecar.store != Store.ROCKSTAR) lines += "rem Written by DroidDeck for ${sidecar.store.label}: ${sidecar.title}. Steam's shortcut runs this file."
        lines += "cd /d \"%~dp0$dir\""
        for ((k, v) in sidecar.env) if (ENV_NAME.matches(k)) lines += "set \"$k=${v.replace("\"", "")}\""
        val args = sidecar.args.joinToString(" ") { quoteArg(it) }
        val run = "\"%~dp0$exe\"" + (if (args.isNotEmpty()) " $args" else "")
        if (sidecar.store == Store.EPIC) {
            lines += "if exist \"%~dp0$EPIC_ARGS\" goto client"
            lines += "set \"$CODE_VAR=\""
            lines += "if exist \"%~dp0$EPIC_CODE\" set /p $CODE_VAR=<\"%~dp0$EPIC_CODE\""
            lines += "if exist \"%~dp0$EPIC_CODE\" del \"%~dp0$EPIC_CODE\""
            lines += "if defined $CODE_VAR goto signed"
            lines += run
            lines += "goto :eof"
            lines += ":signed"
            lines += "set \"$CODE_VAR=\" & $run -AUTH_LOGIN=unused -AUTH_PASSWORD=%$CODE_VAR% -AUTH_TYPE=exchangecode"
            lines += "goto :eof"
            lines += ":client"
            lines += "set \"$CLIENT_VAR=\""
            lines += "set /p $CLIENT_VAR=<\"%~dp0$EPIC_ARGS\""
            lines += "del \"%~dp0$EPIC_ARGS\""
            lines += "if exist \"%~dp0$EPIC_CODE\" del \"%~dp0$EPIC_CODE\""
            lines += "set \"$CLIENT_VAR=\" & \"%~dp0$exe\" %$CLIENT_VAR%"
        } else if (sidecar.store == Store.ROCKSTAR) {
            lines += "\"C:\\Program Files\\Rockstar Games\\Launcher\\Launcher.exe\""
            lines += "exit /b %errorlevel%"
        } else if (sidecar.store == Store.UBISOFT) {
            lines += "if not exist \"%~dp0$UBISOFT_LAUNCH\" goto direct"
            lines += "if not exist \"$UBISOFT_SERVICE\" goto direct"
            lines += "\"$UBISOFT_SERVICE\" \"%~dp0$UBISOFT_LAUNCH\""
            lines += "goto :eof"
            lines += ":direct"
            lines += run
        } else if (sidecar.store == Store.EA) {
            lines += "if not exist \"%~dp0$EA_ENV\" goto run"
            lines += "for /f \"usebackq tokens=1,* delims==\" %%a in (\"%~dp0$EA_ENV\") do set \"%%a=%%b\""
            lines += "del \"%~dp0$EA_ENV\""
            lines += ":run"
            lines += "if exist \"$EA_ORIGIN\" start \"\" \"$EA_ORIGIN\""
            lines += run
            lines += "taskkill /f /im Origin.exe >nul 2>&1"
        } else {
            lines += run
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun quoteArg(arg: String): String {
        val clean = arg.filter { it >= ' ' && it != '\u007f' }
        return if (' ' in clean && !clean.contains('"')) "\"$clean\"" else clean
    }

    private const val CODE_VAR = "DD_X"
    private const val CLIENT_VAR = "DD_A"

    fun installedGame(context: Context, store: Store, id: String): Library.SteamGame? {
        val folder = StoreInstallRoot.gameFolders(context).firstOrNull { folder ->
            StoreGameSidecar.read(folder)?.let { it.store == store && it.id == id && it.isInstalled } == true
        } ?: return null
        val game = AddedGames.single(context, folder.path) ?: return null
        return Library.addedGame(context, game)
    }

    fun adopt(folder: File, sidecar: StoreGameSidecar): StoreGameSidecar {
        if (sidecar.store !in listOf(Store.UBISOFT, Store.ROCKSTAR) || !sidecar.isInstalled) return sidecar
        if (sidecar.store == Store.ROCKSTAR && !com.droiddeck.launcher.stores.rockstar.RockstarLaunch.supports(sidecar.id)) return sidecar
        if (sidecar.store == Store.ROCKSTAR && java.nio.file.Files.isSymbolicLink(File(folder, LAUNCHER).toPath())) return sidecar.copy(launcher = null)
        val wrongExe = !sidecar.exeFile(folder).isFile || sidecar.exe.contains("installer", ignoreCase = true) || sidecar.exe.contains("autorun", ignoreCase = true)
        if (sidecar.store == Store.UBISOFT && sidecar.launcher == LAUNCHER && !wrongExe) return sidecar
        val exe = if (wrongExe) StoreExe.pick(folder, null, sidecar.title).ifEmpty { sidecar.exe } else sidecar.exe
        if (exe == sidecar.exe && sidecar.launcher == LAUNCHER &&
            runCatching { File(folder, LAUNCHER).readText() }.getOrNull() == launcherText(sidecar)) return sidecar
        return runCatching { writeLauncher(folder, sidecar.copy(exe = exe)).also { it.write(folder) } }.getOrDefault(sidecar)
    }

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

    class CodeResult(val written: Boolean, val reason: String)

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

interface EpicLaunchSupport {
    fun exchangeCode(context: Context): String?
    fun signInExpired(context: Context): Boolean
}
