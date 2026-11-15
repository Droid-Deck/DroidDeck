package com.droiddeck.launcher.frontend

import android.content.Context
import java.io.File

object SoundtrackCollectionArt {
    internal fun dir(context: Context, id: Int) = File(context.filesDir, "soundtrack-art/$id")

    private fun sourceFile(context: Context, id: Int) = File(dir(context, id), "source")

    fun chosen(context: Context, id: Int): File? = dir(context, id).takeIf { it.isDirectory }?.let { AddedGameArt.chosen(it, AddedGameArt.Slot.COVER) }

    fun source(context: Context, id: Int): String =
        sourceFile(context, id).takeIf { it.isFile }?.readText()?.trim().orEmpty().substringAfter('|', "")

    fun options(context: Context, id: Int, root: File?): List<AddedGameArt.Option> {
        val paths = if (root != null) {
            val images = root.listFiles()?.filter { it.isFile && it.extension.lowercase() in Soundtrack.COVER_EXT }?.sortedBy { it.name.lowercase() }.orEmpty()
            (images + listOfNotNull(Soundtrack.firstCover(context, root))).map { it.path }
        } else SoundtrackCollections.tracksOf(id).map { it.cover }
        return paths.filter { it.isNotEmpty() && File(it).isFile }.distinct().map { AddedGameArt.Option(AddedGameArt.FOLDER, it, it) }
    }

    fun choose(context: Context, id: Int, option: AddedGameArt.Option): Boolean {
        val folder = dir(context, id)
        val fresh = AddedGameArt.storeNew(folder, AddedGameArt.Slot.COVER, option) ?: return false
        AddedGameArt.dropOlder(folder, AddedGameArt.Slot.COVER, fresh)
        sourceFile(context, id).writeText(option.group + "|" + option.full)
        return true
    }

    fun reset(context: Context, id: Int) {
        chosen(context, id)?.delete()
        sourceFile(context, id).delete()
    }

    fun describe(context: Context, id: Int, root: File? = null): List<AddedGameArt.SlotState> {
        val own = chosen(context, id)
        val state = if (own != null) AddedGameArt.SlotState(AddedGameArt.Slot.COVER, own, AddedGameArt.FILE, true)
        else {
            val auto = if (root != null) Soundtrack.firstCover(context, root) else SoundtrackCollections.autoCover(id)
            AddedGameArt.SlotState(AddedGameArt.Slot.COVER, auto, if (auto != null) AddedGameArt.FOLDER else AddedGameArt.AUTO, false)
        }
        return listOf(state)
    }

    fun clear(context: Context, id: Int) {
        dir(context, id).deleteRecursively()
    }
}
