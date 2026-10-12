package com.droiddeck.launcher.stores.epic

import android.content.Context
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONObject

/** Epic's GraphQL: the sandbox's achievements, then the player's records in it. */
object EpicAchievements : StoreAchievements(Store.EPIC.id, Store.EPIC.shortLabel) {
    private const val GRAPHQL = "https://launcher.store.epicgames.com/graphql"
    private const val USER_AGENT = "EpicGamesLauncher/14.0.8-22004686+++Portal+Release-Live"

    override fun request(context: Context, game: Game): Result {
        val namespace = game.extra["namespace"].orEmpty()
        if (namespace.isEmpty()) return Result.Failed(Reason.NO_SERVICE)
        val token = EpicCredentialStore.getValidAccessToken(context) ?: return Result.Failed(Reason.SIGNED_OUT)
        val account = EpicCredentialStore.load(context)?.accountId.orEmpty()
        val defs = query(token, GAME_ACHIEVEMENTS, JSONObject().put("sandboxId", namespace).put("locale", locale()))
            ?: return Result.Failed(Reason.UNREACHABLE)
        val list = defs.optJSONObject("data")?.optJSONObject("Achievement")?.optJSONObject("productAchievementsRecordBySandbox")
            ?.optJSONArray("achievements") ?: return Result.Found(emptyList())
        val unlocked = if (account.isEmpty()) emptyMap() else unlockTimes(token, account, namespace)
        val out = ArrayList<Achievement>()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i)?.optJSONObject("achievement") ?: continue
            val id = a.optString("name")
            if (id.isEmpty()) continue
            val at = unlocked[id]
            val done = at != null
            out += Achievement(
                id = id,
                name = (if (done) a.optString("unlockedDisplayName") else a.optString("lockedDisplayName"))
                    .ifEmpty { a.optString("unlockedDisplayName") }.ifEmpty { id },
                description = (if (done) a.optString("unlockedDescription") else a.optString("lockedDescription"))
                    .ifEmpty { a.optString("unlockedDescription") },
                hidden = a.optBoolean("hidden"),
                unlocked = done,
                unlockedAt = at ?: 0,
                icon = a.optString("unlockedIconLink").ifEmpty { null },
                iconLocked = a.optString("lockedIconLink").ifEmpty { null },
            )
        }
        return Result.Found(out)
    }

    /** Achievement name to unlock time, for the ones the player has. */
    private fun unlockTimes(token: String, account: String, namespace: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        val records = query(token, PLAYER_ACHIEVEMENTS, JSONObject().put("epicAccountId", account).put("sandboxId", namespace))
            ?.optJSONObject("data")?.optJSONObject("PlayerAchievement")?.optJSONObject("playerAchievementGameRecordsBySandbox")
            ?.optJSONArray("records") ?: return out
        for (r in 0 until records.length()) {
            val player = records.optJSONObject(r)?.optJSONArray("playerAchievements") ?: continue
            for (i in 0 until player.length()) {
                val pa = player.optJSONObject(i)?.optJSONObject("playerAchievement") ?: continue
                if (pa.optBoolean("unlocked")) out[pa.optString("achievementName")] = epochSeconds(pa.optString("unlockDate"))
            }
        }
        return out
    }

    private fun query(token: String, query: String, variables: JSONObject): JSONObject? =
        StoreNet.postJson(GRAPHQL, JSONObject().put("query", query).put("variables", variables).toString(), bearer = token, userAgent = USER_AGENT)
            ?.let(::JSONObject)

    private val GAME_ACHIEVEMENTS = """
        query Achievement(${'$'}sandboxId: String!, ${'$'}locale: String!) {
          Achievement {
            productAchievementsRecordBySandbox(sandboxId: ${'$'}sandboxId, locale: ${'$'}locale) {
              achievements {
                achievement {
                  name hidden unlockedDisplayName lockedDisplayName unlockedDescription lockedDescription
                  unlockedIconLink lockedIconLink XP
                }
              }
            }
          }
        }
    """.trimIndent()

    private val PLAYER_ACHIEVEMENTS = """
        query PlayerAchievement(${'$'}epicAccountId: String!, ${'$'}sandboxId: String!) {
          PlayerAchievement {
            playerAchievementGameRecordsBySandbox(epicAccountId: ${'$'}epicAccountId, sandboxId: ${'$'}sandboxId) {
              records { playerAchievements { playerAchievement { achievementName unlocked unlockDate XP } } }
            }
          }
        }
    """.trimIndent()
}
