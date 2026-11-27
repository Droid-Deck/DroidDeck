package com.droiddeck.launcher.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamLocalConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

class IdleGame(
    val appId: Int,
    val name: String,
    val art: File?,
    val playtimeMinutes: Long,
    val lastPlayed: Long,
    val highlighted: Boolean,
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
            val art = games.firstOrNull { it.appId == id }?.let { it.hero ?: it.art } ?: runCatching {
                Library.steamCacheImage(File(SessionPrefs.activeSteamRoot(context), "appcache/librarycache"), id,
                    listOf("library_hero.jpg", "library_600x900.jpg", "header.jpg"))
            }.getOrNull()
            return IdleGame(id, o.name, art, o.minutes, o.lastPlayed, highlighted = true)
        }
        val (game, highlighted) = pick(games, selected) ?: return null
        val stats = runCatching { SteamLocalConfig.appStats(SessionPrefs.activeSteamRoot(context), game.appId) }.getOrNull()
        return IdleGame(
            game.appId, game.name, game.hero ?: game.art, stats?.playtimeMinutes ?: 0L,
            maxOf(stats?.lastPlayed ?: 0L, game.lastPlayed), highlighted,
        )
    }

    fun runExpression(appId: Int): String = "SteamClient.Apps.RunGame('$appId', '', -1, 100)"
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
internal fun BottomIdleHome(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var game by remember { mutableStateOf<IdleGame?>(null) }
    LaunchedEffect(Unit) {
        val games = withContext(Dispatchers.IO) { runCatching { Library.steamGames(context) }.getOrDefault(emptyList()) }
        while (isActive) {
            val selected = withContext(Dispatchers.IO) { runCatching { AgentGuest.selectedSteamAppId(context) }.getOrNull() }
            val next = withContext(Dispatchers.IO) { IdleHome.load(context, games, selected) }
            if (next?.appId != game?.appId || next?.highlighted != game?.highlighted) game = next
            delay(SELECTION_MS)
        }
    }
    game?.let { g ->
        IdleHomeCard(g, modifier) {
            Thread { runCatching { AgentGuest.steamEval(context, IdleHome.runExpression(g.appId), 5_000) } }.start()
        }
    }
}

@Composable
internal fun IdleHomeCard(game: IdleGame, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    val pal = LocalPalette.current
    Box(modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(pal.surface)) {
        if (game.art != null) AsyncImage(model = game.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(pal.primary.copy(alpha = 0.45f), pal.surface))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (game.highlighted) R.string.bottom_idle_selected else R.string.bottom_idle_recent),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(game.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(details(game), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BottomPillButton(stringResource(R.string.bottom_idle_play), "bottom-idle-play", onPlay)
            }
        }
    }
}

@Composable
private fun details(game: IdleGame): String {
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
