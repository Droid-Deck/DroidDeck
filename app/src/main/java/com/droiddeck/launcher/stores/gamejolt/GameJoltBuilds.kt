package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import org.json.JSONArray
import org.json.JSONObject

internal class GameJoltBuild(
    val id: String,
    val packageId: String,
    val packageTitle: String,
    val version: String,
    val filename: String,
    val size: Long,
    val stamp: String,
    val platforms: Set<String>,
    val folder: String,
    val executable: String,
) {
    val installable: Boolean get() = "windows" in platforms
}

internal object GameJoltBuilds {
    fun parse(payload: JSONObject?): List<GameJoltBuild> {
        if (payload == null) return emptyList()
        val packages = objects(payload.optJSONArray("packages")).associate { it.optLong("id") to it.optString("title") }
        val releases = objects(payload.optJSONArray("releases")).associateBy { it.optLong("id") }
        val launch = objects(payload.optJSONArray("launchOptions"))
        val builds = objects(payload.optJSONArray("builds")).filter { it.optJSONObject("primary_file") != null }
        val newest = builds.groupBy { it.optLong("game_package_id") }.mapValues { (_, list) ->
            list.maxOfOrNull { releaseRank(releases[it.optLong("game_release_id")], it.optLong("game_release_id")) }
        }
        return builds
            .filter { newest[it.optLong("game_package_id")] == releaseRank(releases[it.optLong("game_release_id")], it.optLong("game_release_id")) }
            .map { build ->
                val file = build.optJSONObject("primary_file")
                val release = releases[build.optLong("game_release_id")]
                val id = build.optLong("id")
                GameJoltBuild(
                    id = id.toString(),
                    packageId = build.optLong("game_package_id").toString(),
                    packageTitle = packages[build.optLong("game_package_id")].orEmpty(),
                    version = release?.optString("version_number").orEmpty(),
                    filename = file?.optString("filename").orEmpty().substringAfterLast('/'),
                    size = file?.optLong("filesize") ?: 0L,
                    stamp = release?.optLong("published_on")?.takeIf { it > 0L }?.toString().orEmpty(),
                    platforms = platforms(build),
                    folder = build.optString("folder"),
                    executable = launch.firstOrNull { it.optLong("game_build_id") == id && it.optString("os").startsWith("windows") }?.optString("executable_path").orEmpty(),
                )
            }
            .sortedWith(compareByDescending<GameJoltBuild> { it.installable }.thenByDescending { it.stamp.toLongOrNull() ?: 0L }.thenByDescending { it.id.toLongOrNull() ?: 0L })
    }

    fun platforms(build: JSONObject): Set<String> {
        val found = LinkedHashSet<String>()
        if (build.optBoolean("os_windows") || build.optBoolean("os_windows_64")) found.add("windows")
        if (build.optBoolean("os_linux") || build.optBoolean("os_linux_64")) found.add("linux")
        if (build.optBoolean("os_mac") || build.optBoolean("os_mac_64")) found.add("osx")
        if (build.optBoolean("os_other")) found.add("other")
        return if (found.isEmpty()) setOf("web") else found
    }

    fun load(context: Context, gameId: String): List<GameJoltBuild>? {
        val answer = GameJoltApi.overview(gameId, GameJoltAuth.cookies(context))
        if (!answer.ok) return null
        return parse(answer.payload)
    }

    fun byId(builds: List<GameJoltBuild>, id: String): GameJoltBuild? = builds.firstOrNull { it.id == id }

    fun preferred(builds: List<GameJoltBuild>): GameJoltBuild? = builds.firstOrNull { it.installable }

    fun size(context: Context, item: CatalogItem): Long {
        val gameId = item.extra["gameId"].orEmpty().ifEmpty { item.id }
        val builds = load(context, gameId) ?: return 0L
        val chosen = item.extra["buildId"]?.let { byId(builds, it) } ?: preferred(builds)
        return chosen?.size ?: 0L
    }

    private fun releaseRank(release: JSONObject?, id: Long): Long = (release?.optLong("published_on") ?: 0L) * 1_000_000L + id

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
