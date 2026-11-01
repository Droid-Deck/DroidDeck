package com.droiddeck.launcher.session

import android.content.Context
import android.os.storage.StorageManager
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGames
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Steam libraries the user added beside the internal one and the card library, each a folder of its own. */
object SteamLibraries {
    const val GUEST_ROOT = "/mnt/droiddeck-lib"
    // Each library costs a bind per redirected tool and prefix folder; the PRoot fast path holds 256 in all.
    const val MAX = 4
    private val lock = Any()

    class Entry(val id: String, val path: String, val label: String) {
        val host: File get() = File(path)
        val guest: String get() = "$GUEST_ROOT/$id"
        val x64Host: File get() = File(path, GameStorage.X64_CARD_DIR)
        val x64Guest: String get() = "$guest/${GameStorage.X64_CARD_DIR}"
    }

    fun list(context: Context): List<Entry> = parse(SessionPrefs.steamLibraries(context))

    internal fun parse(json: String): List<Entry> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val path = o.optString("path")
            if (!id.matches(Regex("[0-9]{1,6}")) || path.isEmpty()) null else Entry(id, path, clean(o.optString("label")).ifEmpty { File(path).name })
        }.distinctBy { it.id }
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        for (e in entries) array.put(JSONObject().put("id", e.id).put("path", e.path).put("label", e.label))
        SessionPrefs.setSteamLibraries(context, array.toString())
    }

    /** Steam's libraryfolders.vdf stores labels between quotes with no escaping DroidDeck relies on. */
    internal fun clean(label: String): String = label.replace('"', ' ').replace('\\', ' ').replace(Regex("\\s+"), " ").trim()

    sealed class AddResult {
        class Added(val entry: Entry) : AddResult()
        class Refused(val reason: String) : AddResult()
    }

    fun add(context: Context, path: String, label: String = defaultLabel(context, File(path))): AddResult {
        val entries = list(context)
        if (entries.size >= MAX) return AddResult.Refused(context.getString(R.string.libraries_full, MAX))
        val host = canonical(File(path))
        val taken = entries.map { canonical(it.host) } + listOfNotNull(
            GameStorage.effective(context)?.let { canonical(File(it.path)) },
            canonical(SessionPrefs.steamRoot(context, "arm64")),
        )
        if (taken.any { overlaps(it, host) }) return AddResult.Refused(context.getString(R.string.libraries_overlap))
        // Adding a Games folder itself is fine; a child of one would make it overlap and hide the rest of that folder.
        if (AddedGames.roots(context).any { host.startsWith(canonical(it.host) + "/") }) return AddResult.Refused(context.getString(R.string.libraries_overlap))
        GameStorage.prepare(context, path)?.let { return AddResult.Refused(it) }
        synchronized(lock) {
            val current = list(context)
            if (current.size >= MAX) return AddResult.Refused(context.getString(R.string.libraries_full, MAX))
            if (current.any { overlaps(canonical(it.host), host) }) return AddResult.Refused(context.getString(R.string.libraries_overlap))
            // A removed library's mount stays registered with Steam until its next session, so ids are never reused.
            val next = maxOf(SessionPrefs.steamLibraryNextId(context), (current.maxOfOrNull { it.id.toInt() } ?: 0) + 1)
            val entry = Entry(next.toString(), path, clean(label).ifEmpty { File(path).name })
            save(context, current + entry)
            SessionPrefs.setSteamLibraryNextId(context, next + 1)
            return AddResult.Added(entry)
        }
    }

    fun rename(context: Context, id: String, label: String) {
        val name = clean(label).takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) { save(context, list(context).map { if (it.id == id) Entry(it.id, it.path, name) else it }) }
    }

    /** Forgets the library; its folder and games stay where they are. */
    fun remove(context: Context, id: String) {
        synchronized(lock) { save(context, list(context).filterNot { it.id == id }) }
    }

    fun defaultLabel(context: Context, folder: File): String {
        val volume = try {
            context.getSystemService(StorageManager::class.java)?.getStorageVolume(folder)?.getDescription(context)
        } catch (e: Exception) {
            null
        }
        val name = folder.name.ifEmpty { context.getString(R.string.gstore_folder) }
        return clean(if (volume.isNullOrBlank()) name else context.getString(R.string.gstore_folder_on_volume, name, volume))
    }

    internal fun overlaps(a: String, b: String): Boolean = a == b || a.startsWith("$b/") || b.startsWith("$a/")

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}
