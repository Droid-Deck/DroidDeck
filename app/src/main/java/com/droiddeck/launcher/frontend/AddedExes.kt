package com.droiddeck.launcher.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AddedExes {
    class Entry(val folder: String, val exe: String, val picked: Boolean = true)

    private fun file(context: Context) = File(context.filesDir, "added-exes.json")

    @Synchronized
    fun list(context: Context): List<Entry> = try {
        val array = JSONArray(file(context).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val folder = o.optString("folder"); val exe = o.optString("exe")
            if (folder.isEmpty() || exe.isEmpty()) null else Entry(folder, exe, o.optBoolean("picked", true))
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun put(context: Context, entry: Entry) = write(context, list(context).filter { it.folder != entry.folder } + entry)

    @Synchronized
    fun drop(context: Context, folder: String) = write(context, list(context).filter { it.folder != folder })

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("folder", it.folder).put("exe", it.exe).put("picked", it.picked)) }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
