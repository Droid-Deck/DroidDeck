package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.epic.EpicAchievements
import com.droiddeck.launcher.stores.gog.GogAchievements
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

abstract class StoreAchievements(val source: String, val label: String) {

    data class Game(val source: String, val id: String, val extra: Map<String, String> = emptyMap()) {
        companion object {
            fun of(item: CatalogItem) = Game(item.store.id, item.id, item.extra)
            fun of(sidecar: StoreGameSidecar) = Game(sidecar.store.id, sidecar.id, sidecar.extra)
        }
    }

    class Achievement(
        val id: String,
        val name: String,
        val description: String,
        val hidden: Boolean,
        val unlocked: Boolean,
        val unlockedAt: Long,
        val icon: String?,
        val iconLocked: String?,
    )

    sealed class Result {
        class Found(val list: List<Achievement>) : Result()
        class Failed(val reason: Reason) : Result()
    }

    enum class Reason { SIGNED_OUT, UNREACHABLE, NO_SERVICE }

    protected abstract fun request(context: Context, game: Game): Result

    fun fetch(context: Context, game: Game): Result {
        val result = runCatching { request(context, game) }.getOrElse { e ->
            Log.w(TAG, "$source:${game.id}: ${e.javaClass.simpleName}")
            Result.Failed(Reason.UNREACHABLE)
        }
        if (result is Result.Found) runCatching {
            cacheFile(context, game).apply { parentFile?.mkdirs() }.writeText(toJson(result.list).toString())
        }
        return result
    }

    fun cached(context: Context, game: Game): List<Achievement>? = runCatching {
        val file = cacheFile(context, game)
        if (!file.exists()) null else fromJson(JSONArray(file.readText()))
    }.getOrNull()

    private fun cacheFile(context: Context, game: Game) =
        File(context.filesDir, "achievements/$source/${game.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    protected fun locale(): String = Locale.getDefault().toLanguageTag()

    protected fun epochSeconds(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0
        val normalized = iso.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return runCatching { OffsetDateTime.parse(normalized).toEpochSecond() }
            .recoverCatching { Instant.parse(normalized).epochSecond }
            .getOrDefault(0)
    }

    private fun toJson(list: List<Achievement>) = JSONArray().apply {
        for (a in list) put(
            JSONObject().put("id", a.id).put("name", a.name).put("description", a.description).put("hidden", a.hidden)
                .put("unlocked", a.unlocked).put("unlockedAt", a.unlockedAt).put("icon", a.icon ?: "").put("iconLocked", a.iconLocked ?: ""),
        )
    }

    private fun fromJson(arr: JSONArray) = List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        Achievement(
            id = o.getString("id"), name = o.optString("name"), description = o.optString("description"),
            hidden = o.optBoolean("hidden"), unlocked = o.optBoolean("unlocked"), unlockedAt = o.optLong("unlockedAt"),
            icon = o.optString("icon").ifEmpty { null }, iconLocked = o.optString("iconLocked").ifEmpty { null },
        )
    }

    companion object {
        private const val TAG = "StoreAchievements"
        var revision by androidx.compose.runtime.mutableIntStateOf(0)
            private set
        fun invalidate() { revision++ }

        private val all: List<StoreAchievements> by lazy { listOf(GogAchievements, EpicAchievements) }

        fun forSource(source: String?): StoreAchievements? = all.firstOrNull { it.source == source }
    }
}
