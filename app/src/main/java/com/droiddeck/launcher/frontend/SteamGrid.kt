package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File

/** Steam keeps a game's custom art in `userdata/<account>/config/grid`; this is where DroidDeck's choices and Steam's meet. */
object SteamGrid {
    private const val TAG = "SteamGrid"

    /** Every change to a game's art, here or in a scan, takes this first so two of them never interleave. */
    internal val lock = Any()
    private const val MANIFEST = ".droiddeck-art.json"
    private val SUFFIX = mapOf(AddedGameArt.Slot.COVER to "p", AddedGameArt.Slot.WIDE to "", AddedGameArt.Slot.BACKGROUND to "_hero", AddedGameArt.Slot.LOGO to "_logo")
    private val EXTS = listOf("png", "jpg", "jpeg", "webp")

    class Target(val artDir: File, val id: Long, val setSource: (AddedGameArt.Slot, String) -> Unit)

    fun target(game: Library.SteamGame, context: Context) = Target(SteamGameArt.dir(context, game.appId), game.appId.toLong()) { slot, value ->
        SteamGameArt.setSource(context, game.appId, slot, value)
    }

    fun target(game: AddedGames.Game, context: Context) = Target(AddedGameArt.cacheDir(context, game), game.appId and 0xFFFFFFFFL) { slot, value ->
        SessionPrefs.setAddedGameArtSource(context, game.folder.path, slot.key, value)
    }

    internal fun dir(context: Context): File? {
        return AddedGames.accountConfig(SessionPrefs.activeSteamRoot(context))?.let { File(it, "grid") }
    }

    private fun files(grid: File, id: Long, slot: AddedGameArt.Slot): List<File> =
        EXTS.map { File(grid, "$id${SUFFIX[slot]}.$it") }.filter { it.isFile }

    private fun signature(file: File?) = if (file == null) "" else "${file.length()}:${file.lastModified()}"

    /** Each Steam client has its own grid folder, so what was last seen there is kept per client. */
    private fun sigName(context: Context, key: String) =
        if (SessionPrefs.activeSteamClientArch(context) == "x86_64") "grid-x86_64-$key" else "grid-$key"

    private fun sigFile(context: Context, target: Target, slot: AddedGameArt.Slot) = File(target.artDir, sigName(context, slot.key))

    private fun recorded(context: Context, target: Target, slot: AddedGameArt.Slot): String? =
        sigFile(context, target, slot).takeIf { it.isFile }?.readText()

    private fun record(context: Context, target: Target, slot: AddedGameArt.Slot, value: String) {
        target.artDir.mkdirs()
        sigFile(context, target, slot).writeText(value)
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
            record(context, target, slot, "")
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
        record(context, target, slot, signature(placed))
        return true
    }

    private const val POSITION = "logo-position"
    private const val POSITION_SIG = "json"

    private fun positionFile(grid: File, id: Long) = File(grid, "$id.json")

    private fun positionIn(file: File): LogoPosition.Value? = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }?.let(LogoPosition::parse)

    /** The logo position chosen here (or taken over from Steam) for a game; null when Steam's own default applies. */
    fun position(target: Target): LogoPosition.Value? = positionIn(File(target.artDir, POSITION))

    private fun positionRecorded(context: Context, target: Target): String? =
        File(target.artDir, sigName(context, POSITION_SIG)).takeIf { it.isFile }?.readText()

    private fun recordPosition(context: Context, target: Target, value: String) {
        target.artDir.mkdirs()
        File(target.artDir, sigName(context, POSITION_SIG)).writeText(value)
    }

    private fun storePosition(target: Target, value: LogoPosition.Value?): Boolean {
        val file = File(target.artDir, POSITION)
        if (value == null) return !file.exists() || file.delete()
        target.artDir.mkdirs()
        return runCatching { file.writeText(LogoPosition.toJson(value).toString()) }.isSuccess
    }

    /** Sets (or with null, clears) a game's logo position here and in the client's grid folder, whether or not Steam is running. */
    fun writePosition(context: Context, target: Target, value: LogoPosition.Value?): Boolean = synchronized(lock) {
        placePosition(context, target, value) && storePosition(target, value)
    }

    /** Steam keeps the position in `<id>.json`; anything else that file holds is left as it was. */
    private fun placePosition(context: Context, target: Target, value: LogoPosition.Value?): Boolean {
        val grid = dir(context) ?: return true
        val file = positionFile(grid, target.id)
        val existing = if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() else null
        if (file.isFile && existing == null) return value == null
        val next = if (value == null) existing?.let(LogoPosition::without) else LogoPosition.toJson(value, existing ?: JSONObject())
        if (next == null) {
            if (file.isFile && !file.delete()) return false
            recordPosition(context, target, "")
            return true
        }
        grid.mkdirs()
        val tmp = File(grid, file.name + ".tmp")
        val placed = runCatching { tmp.writeText(next.toString()); tmp.renameTo(file) }.getOrDefault(false)
        if (!placed) {
            tmp.delete()
            return false
        }
        recordPosition(context, target, signature(file))
        return true
    }

    /** A `logoPosition` this version cannot read (a pin it does not know, say) is left alone rather than read as "removed". */
    private fun hasUnknownPosition(file: File): Boolean {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
        return json.has("logoPosition") && LogoPosition.parse(json) == null
    }

    private fun reconcilePosition(context: Context, grid: File, target: Target) {
        val file = positionFile(grid, target.id)
        if (hasUnknownPosition(file)) return
        val theirs = positionIn(file)
        val now = if (theirs != null) signature(file) else ""
        val before = positionRecorded(context, target)
        val own = position(target)
        when {
            before == null && own != null && theirs == null -> placePosition(context, target, own)
            before == null || now != before -> {
                // Recorded only once the local side agrees, so a failed take-over is tried again on the next scan.
                if (theirs != null) {
                    if (own == theirs || storePosition(target, theirs)) recordPosition(context, target, now)
                } else {
                    if (own == null || storePosition(target, null)) recordPosition(context, target, "")
                }
            }
            own != null && own != theirs -> placePosition(context, target, own)
        }
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
                val before = recorded(context, target, slot)
                when {
                    before == null && own != null && theirs == null -> write(context, target, slot, own)
                    before == null || now != before -> {
                        if (theirs != null) {
                            if (own != null && own.length() == theirs.length() && own.readBytes().contentEquals(theirs.readBytes())) record(context, target, slot, now)
                            else if (AddedGameArt.store(target.artDir, slot, AddedGameArt.Option(AddedGameArt.STEAM, theirs.path, theirs.path))) {
                                target.setSource(slot, AddedGameArt.STEAM + "|steam-client")
                                record(context, target, slot, now)
                                changed = true
                            }
                        } else {
                            if (own != null && present.isEmpty()) { own.delete(); target.setSource(slot, ""); changed = true }
                            record(context, target, slot, "")
                        }
                    }
                    own != null && theirs != null && now == before && own.length() != theirs.length() -> write(context, target, slot, own)
                    own != null && present.isEmpty() -> write(context, target, slot, own)
                }
            } catch (e: Exception) {
                Log.w(TAG, "${target.id} ${slot.key}: ${e.javaClass.simpleName}")
            }
        }
        for (target in targets) {
            try {
                reconcilePosition(context, grid, target)
            } catch (e: Exception) {
                Log.w(TAG, "${target.id} logo position: ${e.javaClass.simpleName}")
            }
        }
        if (changed) Log.i(TAG, "took over art changed in Steam")
        return changed
    }
}
