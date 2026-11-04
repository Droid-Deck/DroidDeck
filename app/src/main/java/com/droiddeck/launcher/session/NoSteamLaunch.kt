package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.epic.EpicClient

object NoSteamLaunch {
    const val PROTON_RUN = "/usr/local/bin/droiddeck-proton-run"
    const val EPIC = "/usr/local/bin/droiddeck-epic"
    const val BATTLENET = com.droiddeck.launcher.runtime.BattleNet.PROGRAM

    class Command(val program: String, val args: List<String>, val name: String)

    fun scope(game: Library.SteamGame): String? = when {
        game.library == Library.EPIC_LAUNCHER -> EpicClient.shortcutAppId.toString()
        game.library == Library.BATTLENET_LAUNCHER -> com.droiddeck.launcher.runtime.BattleNet.APP_ID.toString()
        game.library == Library.ADDED || game.appId > 0 -> game.profileKey
        else -> null
    }?.takeIf { it.isNotEmpty() && GameEnvironment.validScope(it) }

    fun enabled(config: GameEnvironment.Config, game: Library.SteamGame): Boolean =
        scope(game)?.let { GameEnvironment.gameNoSteam(config, it) } == true

    fun enabled(context: Context, game: Library.SteamGame): Boolean =
        scope(game) != null && runCatching { enabled(GameEnvironmentStore.read(context), game) }.getOrDefault(false)

    fun enabled(context: Context, appId: Long): Boolean = runCatching {
        GameEnvironment.gameNoSteam(GameEnvironmentStore.read(context), (appId and 0xFFFFFFFFL).toString())
    }.getOrDefault(false)

    fun command(game: Library.SteamGame, added: AddedGames.Game?): Command? = when {
        game.library == Library.EPIC_LAUNCHER -> Command(EPIC, listOf("run"), game.name)
        game.library == Library.ADDED && game.source == com.droiddeck.launcher.stores.Store.BATTLENET.id ->
            game.storeId?.let(com.droiddeck.launcher.runtime.BattleNet::title)?.let { Command(BATTLENET, listOf("launch", it.code), game.name) }
        game.library == Library.ADDED -> added?.let { a -> a.steamAppId?.let { Command(PROTON_RUN, listOf("--app", it.toString()), a.name) } ?: addedCommand(a) }
        game.appId > 0 -> Command(PROTON_RUN, listOf("--app", game.appId.toString()), game.name)
        else -> null
    }

    fun addedCommand(game: AddedGames.Game): Command = Command(
        PROTON_RUN,
        listOf("--app", (game.appId and 0xFFFFFFFFL).toString(), "--dir", game.guestDir) +
            (if (game.launchOptions.isEmpty()) emptyList() else listOf("--options", game.launchOptions)) +
            listOf("--", game.guestExe),
        game.name,
    )

    fun settingsId(game: AddedGames.Game): Long = (game.steamAppId?.toLong() ?: game.appId) and 0xFFFFFFFFL

    fun addedGame(context: Context, game: Library.SteamGame): AddedGames.Game? {
        val id = game.appId.toLong() and 0xFFFFFFFFL
        val one = game.gameFiles?.let { AddedGames.single(context, it.path) }
        return one?.takeIf { settingsId(it) == id } ?: AddedGames.scan(context).firstOrNull { settingsId(it) == id }
    }

    fun nameFor(program: String?, args: List<String>): String? = when (program) {
        EPIC -> Library.EPIC_LAUNCHER_NAME
        BATTLENET -> args.getOrNull(args.indexOf("launch") + 1)?.takeIf { args.contains("launch") }?.let { names[it] } ?: com.droiddeck.launcher.stores.Store.BATTLENET.label
        PROTON_RUN -> args.getOrNull(args.indexOf("--app") + 1)?.takeIf { args.contains("--app") }?.let { names[it] }
        else -> null
    }

    val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(command: Command) {
        if (command.program == PROTON_RUN) command.args.getOrNull(command.args.indexOf("--app") + 1)?.let { names[it] = command.name }
        if (command.program == BATTLENET) command.args.getOrNull(command.args.indexOf("launch") + 1)?.takeIf { command.args.contains("launch") }?.let { names[it] = command.name }
    }
}
