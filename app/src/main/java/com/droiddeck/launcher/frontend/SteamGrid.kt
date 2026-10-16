package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File

/** Steam keeps a game's custom art in `userdata/<account>/config/grid`; this is where DroidDeck's choices and Steam's meet. */
object SteamGrid {
    private const val TAG = "SteamGrid"

    /** Every change to a game's art, here or in a scan, takes this first so two of them never interleave. */
    internal val lock = Any()
    private const val MANIFEST = ".droiddeck-art.json"
    private val SUFFIX = mapOf(AddedGameArt.Slot.COVER to "p", AddedGameArt.Slot.BACKGROUND to "_hero", AddedGameArt.Slot.LOGO to "_logo")
    private val EXTS = listOf("png", "jpg", "jpeg", "webp")

    class Target(val artDir: File, val id: Long, val setSource: (AddedGameArt.Slot, String) -> Unit)

    fun target(game: Library.SteamGame, context: Context) = Target(SteamGameArt.dir(context, game.appId), game.appId.toLong()) { slot, value ->
        SteamGameArt.setSource(context, game.appId, slot, value)
    }

    fun target(game: AddedGames.Game, context: Context) = Target(AddedGameArt.cacheDir(context, game), game.appId and 0xFFFFFFFFL) { slot, value ->
        SessionPrefs.setAddedGameArtSource(context, game.folder.path, slot.key, value)
    }

    internal fun dir(context: Context): File? {
        val root = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
        return AddedGames.accountConfig(root)?.let { File(it, "grid") }
    }

    private fun files(grid: File, id: Long, slot: AddedGameArt.Slot): List<File> =
        EXTS.map { File(grid, "$id${SUFFIX[slot]}.$it") }.filter { it.isFile }

    private fun signature(file: File?) = if (file == null) "" else "${file.length()}:${file.lastModified()}"

    private fun sigFile(target: Target, slot: AddedGameArt.Slot) = File(target.artDir, "grid-${slot.key}")

    private fun recorded(target: Target, slot: AddedGameArt.Slot): String? = sigFile(target, slot).takeIf { it.isFile }?.readText()

    private fun record(target: Target, slot: AddedGameArt.Slot, value: String) {
        target.artDir.mkdirs()
        sigFile(target, slot).writeText(value)
    }

    private fun writtenByDroidDeck(grid: File): Set<String> = runCatching {
        val manifest = JSONObject(File(grid, MANIFEST).readText())
        manifest.keys().asSequence().filter { name -> File(grid, name).let { it.isFile && it.length() == manifest.optLong(name, -1L) } }.toSet()
    }.getOrDefault(emptySet())

    /** Puts a choice (or, with null, the lack of one) where Steam reads it, whether or not Steam is running. */
    fun write(context: Context, target: Target, slot: AddedGameArt.Slot, file: File?): Boolean = synchronized(lock) { place(context, target, slot, file) }

    private fun place(context: Context, target: Target, slot: AddedGameArt.Slot, file: File?): Boolean {
        val suffix = SUFFIX[slot] ?: return true
        val grid = dir(context) ?: return true
        if (file == null) {
            if (files(grid, target.id, slot).map { it.delete() }.any { !it }) return false
            record(target, slot, "")
            return true
        }
        val ext = file.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
        if (ext !in EXTS) return false
        grid.mkdirs()
        val dst = File(grid, "${target.id}$suffix.$ext")
        val tmp = File(grid, dst.name + ".tmp")
        val placed = runCatching { file.copyTo(tmp, overwrite = true); if (tmp.renameTo(dst)) dst else null }.getOrNull()
        if (placed == null) {
            tmp.delete()
            return false
        }
        if (files(grid, target.id, slot).filter { it != dst }.map { it.delete() }.any { !it }) return false
        record(target, slot, signature(placed))
        return true
    }

    /** Takes over what changed in Steam since the last look; returns true when a choice was added, replaced or dropped. */
    fun sync(context: Context, targets: List<Target>): Boolean = synchronized(lock) { reconcile(context, targets) }

    private fun reconcile(context: Context, targets: List<Target>): Boolean {
        val grid = dir(context) ?: return false
        val ours = writtenByDroidDeck(grid)
        var changed = false
        for (target in targets) for (slot in SUFFIX.keys) {
            try {
                val own = AddedGameArt.chosen(target.artDir, slot)
                val present = files(grid, target.id, slot)
                val theirs = present.filter { it.name !in ours }.maxByOrNull { it.lastModified() }
                val now = signature(theirs)
                val before = recorded(target, slot)
                when {
                    before == null && own != null && theirs == null -> write(context, target, slot, own)
                    before == null || now != before -> {
                        if (theirs != null) {
                            if (own != null && own.length() == theirs.length() && own.readBytes().contentEquals(theirs.readBytes())) record(target, slot, now)
                            else if (AddedGameArt.store(target.artDir, slot, AddedGameArt.Option(AddedGameArt.STEAM, theirs.path, theirs.path))) {
                                target.setSource(slot, AddedGameArt.STEAM + "|steam-client")
                                record(target, slot, now)
                                changed = true
                            }
                        } else {
                            if (own != null && present.isEmpty()) { own.delete(); target.setSource(slot, ""); changed = true }
                            record(target, slot, "")
                        }
                    }
                    own != null && theirs != null && now == before && own.length() != theirs.length() -> write(context, target, slot, own)
                    own != null && present.isEmpty() -> write(context, target, slot, own)
                }
            } catch (e: Exception) {
                Log.w(TAG, "${target.id} ${slot.key}: ${e.javaClass.simpleName}")
            }
        }
        if (changed) Log.i(TAG, "took over art changed in Steam")
        return changed
    }
}
