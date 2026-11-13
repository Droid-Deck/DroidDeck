package com.droiddeck.launcher.frontend

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object SoundtrackOrder {
    private val changes = mutableIntStateOf(0)
    private var cache: Map<String, List<String>>? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "soundtrack-order").apply { isDaemon = true } }

    val version: Int get() = changes.intValue

    private fun file(context: Context) = File(context.filesDir, "soundtrack-order.json")

    @Synchronized
    private fun read(context: Context): Map<String, List<String>> {
        cache?.let { return it }
        val out = HashMap<String, List<String>>()
        val f = file(context)
        if (f.isFile) runCatching {
            val json = JSONObject(f.readText())
            for (key in json.keys()) {
                val array = json.getJSONArray(key)
                out[key] = List(array.length()) { array.getString(it) }
            }
        }
        cache = out
        return out
    }

    @Synchronized
    fun set(context: Context, root: File, paths: List<String>) {
        val next = HashMap(read(context))
        next[root.path] = paths
        cache = next
        val body = JSONObject().also { json -> next.forEach { (k, v) -> json.put(k, JSONArray(v)) } }.toString()
        val target = file(context)
        writer.execute {
            val tmp = File(target.path + ".tmp")
            runCatching {
                tmp.writeText(body)
                if (!tmp.renameTo(target)) tmp.delete()
            }
        }
        changes.intValue++
    }

    internal fun ordered(disc: Map<Int, List<Soundtrack.Track>>, order: List<String>): Map<Int, List<Soundtrack.Track>> {
        if (order.isEmpty()) return disc
        val index = HashMap<String, Int>()
        order.forEachIndexed { i, p -> index.putIfAbsent(p, i) }
        return disc.mapValues { (_, list) ->
            list.sortedBy { index[it.file.path] ?: Int.MAX_VALUE }.mapIndexed { i, t ->
                Soundtrack.Track(t.file, t.title, t.disc, i + 1, t.durationMs, t.artist, t.discLabel)
            }
        }
    }

    fun apply(context: Context, root: File, disc: Map<Int, List<Soundtrack.Track>>): Map<Int, List<Soundtrack.Track>> {
        val order = read(context)[root.path] ?: return disc
        return ordered(disc, order)
    }
}
