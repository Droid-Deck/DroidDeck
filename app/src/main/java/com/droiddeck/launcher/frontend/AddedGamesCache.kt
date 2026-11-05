package com.droiddeck.launcher.frontend

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class AddedGamesCache(val file: File) {
    private class Rank(val modified: Long, val at: Long, val ranking: GameExePicker.Ranking)
    private class Name(val name: String, val at: Long)

    private val ranks = ConcurrentHashMap<String, Rank>()
    private val names = ConcurrentHashMap<String, Name>()
    @Volatile private var changed = false

    init {
        val json = runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        json?.optJSONObject("ranks")?.let { all ->
            for (path in all.keys()) runCatching {
                val o = all.getJSONObject(path)
                val exes = o.getJSONArray("exes")
                val scores = o.getJSONArray("scores")
                require(exes.length() > 0 && exes.length() == scores.length())
                ranks[path] = Rank(o.getLong("modified"), o.getLong("at"), GameExePicker.Ranking(
                    List(exes.length()) { File(exes.getString(it)) }, List(scores.length()) { scores.getInt(it) }, o.optBoolean("uncertain"),
                ))
            }
        }
        json?.optJSONObject("names")?.let { all ->
            for (key in all.keys()) runCatching { all.getJSONObject(key).let { names[key] = Name(it.getString("name"), it.getLong("at")) } }
        }
    }

    fun rank(folder: File): GameExePicker.Ranking? {
        val kept = ranks[folder.path] ?: return null
        val age = System.currentTimeMillis() - kept.at
        val top = kept.ranking.exes.first()
        return kept.ranking.takeIf { kept.modified == folder.lastModified() && age in 0 until KEEP_MS && top.isFile }
    }

    fun keepRank(folder: File, ranking: GameExePicker.Ranking) {
        if (ranking.exes.isEmpty()) {
            if (ranks.remove(folder.path) != null) changed = true
            return
        }
        ranks[folder.path] = Rank(folder.lastModified(), System.currentTimeMillis(), ranking)
        changed = true
    }

    fun name(key: String): String? = names[key]?.takeIf { System.currentTimeMillis() - it.at in 0 until KEEP_MS }?.name

    fun keepName(key: String, name: String) {
        names[key] = Name(name, System.currentTimeMillis())
        changed = true
    }

    @Synchronized
    fun save() {
        if (!changed) return
        changed = false
        val oldest = System.currentTimeMillis() - KEEP_MS
        val keptRanks = JSONObject()
        ranks.forEach { (path, r) ->
            if (r.at >= oldest) keptRanks.put(path, JSONObject().put("modified", r.modified).put("at", r.at)
                .put("exes", JSONArray(r.ranking.exes.map { it.path })).put("scores", JSONArray(r.ranking.scores))
                .put("uncertain", r.ranking.uncertain))
        }
        val keptNames = JSONObject()
        names.forEach { (key, n) -> if (n.at >= oldest) keptNames.put(key, JSONObject().put("name", n.name).put("at", n.at)) }
        val atomic = AtomicFile(file)
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(JSONObject().put("ranks", keptRanks).put("names", keptNames).toString().toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            out?.let(atomic::failWrite)
            changed = true
        }
    }

    companion object {
        const val FILE = "added-games-scan.json"
        const val KEEP_MS = 7L * 24 * 3600 * 1000
    }
}
