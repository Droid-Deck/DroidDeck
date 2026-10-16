package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import java.io.File

object SteamGameArt {
    internal fun dir(context: Context, appId: Int) = File(context.filesDir, "steam-art/$appId")

    private fun sourceFile(context: Context, appId: Int, slot: AddedGameArt.Slot) = File(dir(context, appId), "source-${slot.key}")

    fun chosenFile(context: Context, appId: Int, slot: AddedGameArt.Slot): File? = AddedGameArt.chosen(dir(context, appId), slot)

    fun withOverrides(context: Context, game: Library.SteamGame): Library.SteamGame {
        if (game.library == Library.ADDED || !dir(context, game.appId).isDirectory) return game
        val cover = chosenFile(context, game.appId, AddedGameArt.Slot.COVER)
        val hero = chosenFile(context, game.appId, AddedGameArt.Slot.BACKGROUND)
        val icon = chosenFile(context, game.appId, AddedGameArt.Slot.ICON)
        if (cover == null && hero == null && icon == null) return game
        return Library.SteamGame(
            game.appId, game.name, cover ?: game.art, game.library, game.gameId,
            hero ?: game.hero, game.lastPlayed, game.gameFiles, game.protonPrefix, icon ?: game.icon, game.source, game.storeId,
        )
    }

    fun chosenSource(context: Context, appId: Int, slot: AddedGameArt.Slot): Pair<String, String> {
        val value = sourceFile(context, appId, slot).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return value.substringBefore('|') to value.substringAfter('|', "")
    }

    fun describe(context: Context, game: Library.SteamGame): List<AddedGameArt.SlotState> {
        val cache = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/appcache/librarycache")
        val own = withOverrides(context, game)
        return AddedGameArt.Slot.values().map { slot ->
            chosenFile(context, game.appId, slot)?.let {
                return@map AddedGameArt.SlotState(slot, it, chosenSource(context, game.appId, slot).first.ifEmpty { AddedGameArt.FILE }, true)
            }
            val auto = when (slot) {
                AddedGameArt.Slot.COVER -> own.art
                AddedGameArt.Slot.BACKGROUND -> own.hero
                AddedGameArt.Slot.ICON -> own.icon
                AddedGameArt.Slot.LOGO -> Library.steamCacheImage(cache, game.appId, listOf("logo.png"))
            }
            AddedGameArt.SlotState(slot, auto, if (auto != null) AddedGameArt.STEAM else AddedGameArt.AUTO, false)
        }
    }

    fun choices(context: Context, game: Library.SteamGame, slot: AddedGameArt.Slot): AddedGameArt.Choices {
        val out = ArrayList<AddedGameArt.Option>()
        AddedGameArt.steamOption(game.appId.toString(), slot)?.let { out.add(it) }
        val (found, status) = AddedGameArt.sgdbChoices(context, File(dir(context, game.appId), "sgdb-id"), game.appId, game.name, slot)
        out.addAll(found)
        return AddedGameArt.Choices(out, status)
    }

    fun choose(context: Context, game: Library.SteamGame, slot: AddedGameArt.Slot, option: AddedGameArt.Option): Boolean {
        val folder = dir(context, game.appId)
        if (!AddedGameArt.store(folder, slot, option)) return false
        sourceFile(context, game.appId, slot).writeText(option.group + "|" + option.full)
        push(context, game, slot)
        return true
    }

    fun reset(context: Context, game: Library.SteamGame, slot: AddedGameArt.Slot) {
        AddedGameArt.chosen(dir(context, game.appId), slot)?.delete()
        sourceFile(context, game.appId, slot).delete()
        push(context, game, slot)
    }

    private fun push(context: Context, game: Library.SteamGame, slot: AddedGameArt.Slot) {
        val type = slot.assetType ?: return
        val file = chosenFile(context, game.appId, slot)
        AddedGamesPublisher.later(context) { SteamLiveShortcuts.setArt(context, game.appId.toLong(), type, file) }
    }
}
