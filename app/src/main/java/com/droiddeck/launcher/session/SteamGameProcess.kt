package com.droiddeck.launcher.session

import java.io.File

object SteamGameProcess {
    // Compatibilidade com telas que precisam detectar qualquer jogo Steam.
    fun matches(cmdline: ByteArray): Boolean {
        val args = String(cmdline).split('\u0000')
        return args.indices.any { i ->
            args[i] == "waitforexitandrun" ||
                (args[i] == "SteamLaunch" &&
                    args.getOrNull(i + 1)?.startsWith("AppId=") == true)
        }
    }

    fun running(): Boolean =
        File("/proc").listFiles()?.any { p ->
            p.name.all(Char::isDigit) &&
                runCatching { File(p, "cmdline").readBytes() }
                    .getOrNull()?.let(::matches) == true
        } == true
    fun matches(cmdline: ByteArray, gameId: String): Boolean {
        if (gameId.isEmpty() || !gameId.all(Char::isDigit)) return false
        val args = String(cmdline).split('\u0000')
        for (i in args.indices) {
            if (args[i] == "SteamLaunch") {
                val appId = args.getOrNull(i + 1)
                    ?.takeIf { it.startsWith("AppId=") }
                    ?.removePrefix("AppId=")
                if (appId == gameId) return true
            }
            if (args[i] == "-applaunch" && args.getOrNull(i + 1) == gameId) return true
        }
        return false
    }

    fun running(gameId: String, proc: File = File("/proc")): Boolean =
        proc.listFiles()?.any { p ->
            p.name.all(Char::isDigit) &&
                runCatching { File(p, "cmdline").readBytes() }
                    .getOrNull()?.let { matches(it, gameId) } == true
        } == true
}
