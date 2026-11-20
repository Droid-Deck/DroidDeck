package com.droiddeck.launcher.storage

import org.json.JSONObject
import java.io.File

/**
 * What the Storage overview shows, kept on disk so the page opens at once after DroidDeck restarts, as it does on
 * every update, while a fresh scan of the whole userland runs behind it.
 */
class StorageSummary(
    val at: Long, val total: Long, val free: Long, val buckets: Map<Bucket, Long>, val safe: Long,
    val games: Int, val gamesOnSd: Int, val prefixes: Long, val workshop: Long, val shaders: Long,
    val tools: Int, val unusedTools: Long, val components: Int, val unusedComponents: Long,
    val storeGames: Int, val flatpakApps: Int, val flatpakShared: Long, val logBytes: Long, val systemClearable: Long,
) {
    val droiddeck: Long get() = buckets.values.sum()

    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("at", at); put("total", total); put("free", free); put("safe", safe)
        put("buckets", JSONObject().apply { buckets.forEach { (b, v) -> put(b.name, v) } })
        put("games", games); put("gamesOnSd", gamesOnSd); put("prefixes", prefixes); put("workshop", workshop); put("shaders", shaders)
        put("tools", tools); put("unusedTools", unusedTools); put("components", components); put("unusedComponents", unusedComponents)
        put("storeGames", storeGames); put("flatpakApps", flatpakApps); put("flatpakShared", flatpakShared)
        put("logBytes", logBytes); put("systemClearable", systemClearable)
    }.toString()

    companion object {
        private const val VERSION = 1

        fun of(r: StorageReport) = StorageSummary(
            r.at, r.total, r.free, r.buckets, StorageState.safeBytes(r),
            r.games.size, r.gamesOnSd, r.prefixes, r.workshop, r.shaders,
            r.tools.size, r.unusedTools.sumOf { it.bytes }, r.components.size, r.unusedComponents.sumOf { it.bytes },
            r.storeGames.size, r.flatpakApps.size, r.flatpakRuntimes + r.flatpakCache, r.logBytes,
            r.systemCacheBytes + r.checkouts.sumOf { it.bytes },
        )

        /** A summary another version wrote, or a damaged file, reads as none, so the page measures as before. */
        fun fromJson(text: String): StorageSummary? = runCatching {
            val o = JSONObject(text)
            if (o.optInt("v") != VERSION) return null
            val b = o.getJSONObject("buckets")
            StorageSummary(
                o.getLong("at"), o.getLong("total"), o.getLong("free"), Bucket.entries.associateWith { b.optLong(it.name) }, o.getLong("safe"),
                o.getInt("games"), o.getInt("gamesOnSd"), o.getLong("prefixes"), o.getLong("workshop"), o.getLong("shaders"),
                o.getInt("tools"), o.getLong("unusedTools"), o.getInt("components"), o.getLong("unusedComponents"),
                o.getInt("storeGames"), o.getInt("flatpakApps"), o.getLong("flatpakShared"), o.getLong("logBytes"), o.getLong("systemClearable"),
            )
        }.getOrNull()

        fun file(filesDir: File) = File(filesDir, "storage-summary.json")
    }
}
