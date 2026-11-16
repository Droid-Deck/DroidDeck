package com.droiddeck.launcher.session

import java.io.File

object SteamGameProcess {
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
    private fun steamAppIdForGameId(gameId: String): String? {
        if (gameId.isEmpty() || !gameId.all(Char::isDigit)) return null

        val numericId = gameId.toULongOrNull() ?: return null
        val appId = if (numericId > UInt.MAX_VALUE.toULong()) {
            numericId shr 32
        } else {
            numericId
        }

        return appId.takeIf { it != 0uL }?.toString()
    }

    fun matches(cmdline: ByteArray, gameId: String): Boolean {
        val expectedAppId = steamAppIdForGameId(gameId) ?: return false
        val args = String(cmdline).split('\u0000')

        for (i in args.indices) {
            if (args[i] == "SteamLaunch") {
                val appId = args.getOrNull(i + 1)
                    ?.takeIf { it.startsWith("AppId=") }
                    ?.removePrefix("AppId=")

                if (appId == expectedAppId) return true
            }

            if (args[i] == "-applaunch" &&
                args.getOrNull(i + 1) == expectedAppId
            ) {
                return true
            }
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
