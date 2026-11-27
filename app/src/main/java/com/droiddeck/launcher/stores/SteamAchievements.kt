package com.droiddeck.launcher.stores

import android.content.Context
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONObject

object SteamAchievements : StoreAchievements("steam", "Steam") {
    fun game(appId: Long) = Game(source, appId.toString())

    override fun request(context: Context, game: Game): Result {
        val appId = game.id.toLongOrNull()?.takeIf { it in 1..4_294_967_295L } ?: return Result.Failed(Reason.NO_SERVICE)
        if (!SessionState.running || SessionState.mode != SessionService.MODE_STEAM) return Result.Failed(Reason.UNREACHABLE)
        val answer = AgentGuest.steamEval(context, expression(appId), EVAL_TIMEOUT_MS)
        if (!answer.optBoolean("ok")) return Result.Failed(Reason.UNREACHABLE)
        val value = answer.opt("value")?.takeIf { it != JSONObject.NULL }?.toString() ?: return Result.Failed(Reason.UNREACHABLE)
        return parse(appId, value)
    }

    internal fun expression(appId: Long) = """(async () => {
        const r = await SteamClient.Apps.GetMyAchievementsForApp('$appId');
        return typeof r === 'string' ? r : JSON.stringify(r);
    })()"""

    internal fun parse(appId: Long, json: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return Result.Failed(Reason.UNREACHABLE)
        if (root.optInt("result", 1) != 1) return Result.Found(emptyList())
        val items = root.optJSONObject("data")?.optJSONArray("rgAchievements") ?: return Result.Found(emptyList())
        val out = ArrayList<Achievement>(items.length())
        for (i in 0 until items.length()) {
            val a = items.optJSONObject(i) ?: continue
            val id = a.optString("strID")
            if (id.isEmpty()) continue
            val unlocked = a.optBoolean("bAchieved")
            out += Achievement(
                id = id,
                name = a.optString("strName").ifEmpty { id },
                description = a.optString("strDescription"),
                hidden = a.optBoolean("bHidden"),
                unlocked = unlocked,
                unlockedAt = if (unlocked) a.optLong("rtUnlocked") else 0,
                icon = image(appId, a.optString("strImage")),
                iconLocked = null,
            )
        }
        return Result.Found(out)
    }

    private fun image(appId: Long, value: String): String? = when {
        value.isEmpty() -> null
        value.startsWith("https://") -> value
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/$appId/${value.substringAfterLast('/')}"
    }

    private const val EVAL_TIMEOUT_MS = 8_000L
}
