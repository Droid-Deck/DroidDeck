package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.SteamAchievements
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val GAME_POLL_MS = 2_000L

@Composable
internal fun BottomAchievementsPage(runningGame: () -> String?) {
    var appId by remember { mutableStateOf(runningGame()?.toLongOrNull()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            appId = runningGame()?.toLongOrNull()
            delay(GAME_POLL_MS)
        }
    }
    val game = remember(appId) { appId?.let(SteamAchievements::game) }
    val state = rememberAchievements(game)
    if (game == null || state == null) {
        Box(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.bottom_achievements_no_game), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center))
        }
        return
    }
    AchievementsTab(SteamAchievements.label, state, Modifier.verticalScroll(rememberScrollState()))
}
