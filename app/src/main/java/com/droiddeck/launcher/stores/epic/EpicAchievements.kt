package com.droiddeck.launcher.stores.epic

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAchievements
import org.json.JSONObject

object EpicAchievements : StoreAchievements(Store.EPIC.id, Store.EPIC.shortLabel) {
    override fun request(context: Context, game: Game): Result {
        val namespace = game.extra["namespace"].orEmpty().ifEmpty { return Result.Failed(Reason.NO_SERVICE) }
        val token = EpicCredentialStore.getValidAccessToken(context) ?: return Result.Failed(Reason.SIGNED_OUT)
        val account = EpicCredentialStore.load(context)?.accountId.orEmpty()
        val answer = EpicNative.call(
            "achievements",
            JSONObject().put("accessToken", token).put("namespace", namespace).put("accountId", account).put("locale", locale()),
        ) ?: return Result.Failed(Reason.UNREACHABLE)
        val list = answer.optJSONArray("list") ?: return Result.Failed(Reason.UNREACHABLE)
        return Result.Found(
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { a ->
                Achievement(
                    id = a.optString("id"),
                    name = a.optString("name"),
                    description = a.optString("description"),
                    hidden = a.optBoolean("hidden", false),
                    unlocked = a.optBoolean("unlocked", false),
                    unlockedAt = a.optLong("unlockedAt", 0L),
                    icon = a.optString("icon").ifEmpty { null },
                    iconLocked = a.optString("iconLocked").ifEmpty { null },
                )
            },
        )
    }
}
