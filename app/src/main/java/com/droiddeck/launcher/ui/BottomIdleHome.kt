package com.droiddeck.launcher.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamLocalConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class IdleGame(
    val appId: Int,
    val name: String,
    val art: File?,
    val playtimeMinutes: Long,
    val lastPlayed: Long,
    val highlighted: Boolean,
    val logo: File? = null,
)

internal object IdleHome {
    fun pick(games: List<Library.SteamGame>, selected: Long?): Pair<Library.SteamGame, Boolean>? {
        games.firstOrNull { selected != null && it.appId.toLong() == selected }?.let { return it to true }
        return games.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }?.let { it to false }
    }

    class Overview(val name: String, val minutes: Long, val lastPlayed: Long)

    fun overviewExpression(appId: Long): String = "(() => { const o = globalThis.appStore?.GetAppOverviewByAppID?.($appId); " +
        "return o ? JSON.stringify({name: o.display_name, minutes: o.minutes_playtime_forever, last: o.rt_last_time_played}) : null; })()"

    fun parseOverview(json: String?): Overview? = runCatching {
        val o = org.json.JSONObject(json ?: return null)
        Overview(o.optString("name").ifEmpty { return null }, o.optLong("minutes"), o.optLong("last"))
    }.getOrNull()

    internal fun overview(context: Context, appId: Long): Overview? = runCatching {
        val answer = AgentGuest.steamEval(context, overviewExpression(appId), 4_000)
        if (!answer.optBoolean("ok")) null else parseOverview(answer.opt("value")?.takeIf { it != org.json.JSONObject.NULL }?.toString())
    }.getOrNull()

    fun load(context: Context, games: List<Library.SteamGame>, selected: Long?): IdleGame? {
        if (selected != null) overview(context, selected)?.let { o ->
            val id = selected.toInt()
            val known = games.firstOrNull { it.appId == id }
            val cache = File(SessionPrefs.activeSteamRoot(context), "appcache/librarycache")
            val art = known?.let { it.hero ?: it.art } ?: runCatching {
                Library.steamCacheImage(cache, id, listOf("library_hero.jpg", "library_600x900.jpg", "header.jpg"))
            }.getOrNull()
            val logo = known?.logo ?: runCatching { Library.steamCacheImage(cache, id, Library.STEAM_LOGOS) }.getOrNull()
            return IdleGame(id, o.name, art, o.minutes, o.lastPlayed, highlighted = true, logo = logo)
        }
        val (game, highlighted) = pick(games, selected) ?: return null
        val stats = runCatching { SteamLocalConfig.appStats(SessionPrefs.activeSteamRoot(context), game.appId) }.getOrNull()
        return IdleGame(
            game.appId, game.name, game.hero ?: game.art, stats?.playtimeMinutes ?: 0L,
            maxOf(stats?.lastPlayed ?: 0L, game.lastPlayed), highlighted, game.logo,
        )
    }
}

private const val SELECTION_MS = 2_000L

@Composable
internal fun rememberShownGame(runningGame: () -> String?): Long? {
    val context = LocalContext.current
    var appId by remember { mutableStateOf(runningGame()?.toLongOrNull()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            appId = runningGame()?.toLongOrNull()
                ?: withContext(Dispatchers.IO) { runCatching { AgentGuest.selectedSteamAppId(context) }.getOrNull() }
            delay(SELECTION_MS)
        }
    }
    return appId
}

@Composable
internal fun idleDetails(game: IdleGame): String {
    val hours = game.playtimeMinutes / 60
    val played = when {
        game.playtimeMinutes <= 0 -> stringResource(R.string.bottom_idle_never)
        hours == 0L -> stringResource(R.string.bottom_idle_minutes, game.playtimeMinutes.toInt())
        else -> stringResource(R.string.bottom_idle_hours, hours.toInt())
    }
    if (game.lastPlayed <= 0) return played
    val last = DateUtils.getRelativeTimeSpanString(game.lastPlayed * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return stringResource(R.string.bottom_idle_details, played, last)
}
