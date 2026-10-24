package com.droiddeck.launcher.storage

import android.content.Context
import com.droiddeck.launcher.files.FileOps
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SteamLibraryRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A crash-recovery journal for game moves. It is written after a move is validated and
 * registered, and deleted when the move finishes or is cleaned up. If the app dies
 * mid-move the journal survives, the Storage page reports the interrupted move, and the
 * partial destination can be discarded safely: a destination file is only deleted while
 * its source still exists, so finished files are never touched.
 */
object MoveJournal {
    private const val FILE = "pending-move.json"

    class Registration(val arch: String, val path: String, val label: String, val marker: String?, val markerExisted: Boolean)

    class Entry(
        val kind: String,
        val name: String,
        val items: List<Pair<String, String>>,
        val registration: Registration?,
        val startedAt: Long,
    )

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun write(context: Context, entry: Entry) {
        val items = JSONArray()
        for ((src, dst) in entry.items) items.put(JSONObject().put("src", src).put("dst", dst))
        val root = JSONObject()
            .put("kind", entry.kind)
            .put("name", entry.name)
            .put("items", items)
            .put("startedAt", entry.startedAt)
        entry.registration?.let {
            root.put("registration", JSONObject()
                .put("arch", it.arch)
                .put("path", it.path)
                .put("label", it.label)
                .put("marker", it.marker ?: JSONObject.NULL)
                .put("markerExisted", it.markerExisted))
        }
        val tmp = File(file(context).path + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file(context))) { file(context).delete(); tmp.renameTo(file(context)) }
    }

    fun read(context: Context): Entry? = runCatching {
        val text = file(context).takeIf { it.isFile }?.readText() ?: return null
        val root = JSONObject(text)
        val items = root.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val src = o.optString("src"); val dst = o.optString("dst")
            if (src.isNotEmpty() && dst.isNotEmpty()) list.add(src to dst)
        }
        val reg = root.optJSONObject("registration")?.let {
            val marker = it.optString("marker").takeIf { m -> m.isNotEmpty() }
            Registration(it.optString("arch"), it.optString("path"), it.optString("label", ""), marker, it.optBoolean("markerExisted"))
        }
        Entry(root.optString("kind"), root.optString("name"), list, reg, root.optLong("startedAt"))
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * Deletes the partial destination of an interrupted move. A destination is only
     * removed while its source still exists; finished files are left alone. Returns
     * true when there is nothing left to discard.
     */
    fun discard(context: Context): Boolean {
        val entry = read(context) ?: return true
        val app = context.applicationContext
        for ((src, dst) in entry.items) {
            try {
                val source = File(src)
                val dest = File(dst)
                if (source.exists() && dest.exists()) FileOps.delete(dest)
            } catch (e: Exception) {
                android.util.Log.w("MoveJournal", "could not discard $dst", e)
                return false
            }
        }
        entry.registration?.let { reg ->
            try {
                val lib = File(reg.path)
                if (!GameStorage.hasInstalledGames(lib)) {
                    if (reg.arch.isNotEmpty()) SteamLibraryRegistry.unregister(app, reg.arch, lib)
                    reg.marker?.let { File(it).takeIf { m -> m.isFile }?.delete() }
                } else return false
            } catch (e: Exception) {
                android.util.Log.w("MoveJournal", "could not unregister ${reg.path}", e)
                return false
            }
        }
        clear(context)
        return read(context) == null
    }
}
