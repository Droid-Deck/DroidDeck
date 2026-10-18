package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import java.io.File

object AddedGameEdits {
    fun game(context: Context, folder: String): AddedGames.Game? = AddedGames.single(context, folder)

    private fun publish(context: Context, live: () -> Unit) = AddedGamesPublisher.later(context, live)

    fun setName(context: Context, folder: String, name: String): AddedGames.Game? {
        SessionPrefs.setAddedGameName(context, folder, name)
        return apply(context, folder)
    }

    fun setExe(context: Context, folder: String, exe: File): Boolean {
        if (!exe.isFile || AddedGames.guestPath(context, exe) == null) return false
        SessionPrefs.setAddedGameExe(context, folder, exe.path)
        apply(context, folder)
        return true
    }

    fun setStartIn(context: Context, folder: String, dir: String): Boolean {
        val guest = if (dir.isBlank()) "" else AddedGames.guestPath(context, File(dir.trim())) ?: return false
        SessionPrefs.setAddedGameStartIn(context, folder, guest)
        apply(context, folder)
        return true
    }

    fun setLaunchOptions(context: Context, folder: String, options: String): AddedGames.Game? {
        SessionPrefs.setAddedGameLaunch(context, folder, options)
        return apply(context, folder)
    }

    fun setArt(context: Context, folder: String, slot: AddedGameArt.Slot, option: AddedGameArt.Option): Boolean {
        val game = game(context, folder) ?: return false
        return synchronized(SteamGrid.lock) {
            if (!AddedGameArt.choose(context, game, slot, option)) return@synchronized false
            applyArt(context, game, slot)
            true
        }
    }

    fun resetArt(context: Context, folder: String, slot: AddedGameArt.Slot) {
        val game = game(context, folder) ?: return
        synchronized(SteamGrid.lock) {
            AddedGameArt.reset(context, game, slot)
            applyArt(context, game, slot)
        }
    }

    fun logoPosition(context: Context, folder: String): LogoPosition.Value? =
        game(context, folder)?.let { SteamGrid.position(SteamGrid.target(it, context)) }

    fun setLogoPosition(context: Context, folder: String, value: LogoPosition.Value?): Boolean {
        val game = game(context, folder) ?: return false
        if (!SteamGrid.writePosition(context, SteamGrid.target(game, context), value)) return false
        publish(context) { SteamLiveShortcuts.setLogoPosition(context, game.appId, value) }
        return true
    }

    private fun applyArt(context: Context, game: AddedGames.Game, slot: AddedGameArt.Slot) {
        val file = AddedGameArt.describe(context, game).first { it.slot == slot }.file
        if (slot != AddedGameArt.Slot.ICON) SteamGrid.write(context, SteamGrid.target(game, context), slot, AddedGameArt.chosen(AddedGameArt.cacheDir(context, game), slot))
        publish(context) {
            if (slot == AddedGameArt.Slot.ICON) SteamLiveShortcuts.setArt(context, game.appId, null, null, iconPath = file?.absolutePath.orEmpty())
            else SteamLiveShortcuts.setArt(context, game.appId, slot.assetType, file)
        }
    }

    private fun apply(context: Context, folder: String): AddedGames.Game? {
        val game = game(context, folder)
        if (game != null) publish(context) { SteamLiveShortcuts.update(context, game) }
        return game
    }
}
