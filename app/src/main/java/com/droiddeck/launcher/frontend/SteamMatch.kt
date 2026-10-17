package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

object SteamMatch {
    enum class Certainty { FILES, NAME, NONE }

    class Match(val appId: Int?, val certainty: Certainty, val source: String, val checkedAt: Long)

    private const val FILE = "steam-matches.json"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    private fun store(context: Context) = AtomicFile(File(context.filesDir, FILE))

    @Synchronized
    private fun all(context: Context): JSONObject =
        runCatching { JSONObject(store(context).readFully().toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())

    @Synchronized
    fun get(context: Context, key: String): Match? = all(context).optJSONObject(key)?.let { o ->
        Match(
            o.optInt("appId", 0).takeIf { it > 0 },
            runCatching { Certainty.valueOf(o.optString("certainty")) }.getOrDefault(Certainty.NONE),
            o.optString("source"), o.optLong("at"),
        )
    }

    @Synchronized
    fun put(context: Context, key: String, match: Match) {
        val json = all(context).put(
            key, JSONObject().put("certainty", match.certainty.name).put("source", match.source).put("at", match.checkedAt)
                .apply { match.appId?.let { put("appId", it) } },
        )
        val atomic = store(context)
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out) }
    }

    fun resolve(context: Context, key: String, exe: File, name: String, legacy: File? = null, search: ((String) -> Int?)? = null): Match {
        val now = System.currentTimeMillis()
        val identity = runCatching { GameIdentifier.identify(exe) }.getOrNull()
        identity?.appId?.let { id ->
            return Match(id, Certainty.FILES, identity.source.name.lowercase(), now).also { put(context, key, it) }
        }
        val known = get(context, key)
        if (known != null && known.certainty == Certainty.NAME) return known
        if (known != null && known.certainty == Certainty.NONE && now - known.checkedAt < RETRY_AFTER_MS) return known
        legacy?.takeIf { it.isFile }?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }?.takeIf { it > 0 }?.let { id ->
            return Match(id, Certainty.NAME, "store search", now).also { put(context, key, it) }
        }
        if (search == null) return known?.takeIf { it.certainty != Certainty.FILES } ?: Match(null, Certainty.NONE, "", 0)
        val id = runCatching { search(name) }.getOrNull()
        val match = if (id != null && id > 0) Match(id, Certainty.NAME, "store search", now) else Match(null, Certainty.NONE, "", now)
        put(context, key, match)
        return match
    }
}
