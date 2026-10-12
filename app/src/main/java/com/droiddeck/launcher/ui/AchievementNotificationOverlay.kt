package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.stores.AchievementNotifications
import kotlinx.coroutines.delay

@Composable
internal fun AchievementNotificationOverlay() {
    val unlock = AchievementNotifications.pending.firstOrNull()
    var visible by remember(unlock) { mutableStateOf(false) }
    LaunchedEffect(unlock) {
        if (unlock == null) return@LaunchedEffect
        visible = true
        SessionEvents.record("achievement.notification-shown", mapOf("achievementId" to unlock.id))
        delay(5_000)
        visible = false
        delay(Motion.ms(220).toLong())
        AchievementNotifications.dismiss(unlock)
    }
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(
            visible, enter = fadeIn(Motion.tw(220)) + slideInVertically(Motion.tw(300)) { it / 2 },
            exit = fadeOut(Motion.tw(180)) + slideOutVertically(Motion.tw(220)) { it / 3 },
        ) { if (unlock != null) AchievementNotificationCard(unlock) }
    }
}

@Composable
private fun AchievementNotificationCard(unlock: AchievementNotifications.Unlock) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.widthIn(max = 360.dp).clip(shape).background(pal.surface.copy(alpha = 0.97f))
            .border(1.dp, pal.signal.copy(alpha = 0.65f), shape).padding(14.dp)
            .testTag("achievement-notification"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(pal.surfaceVariant), contentAlignment = Alignment.Center) {
            Trophy(26.dp, description = null)
            if (unlock.icon != null) AsyncImage(unlock.icon, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Trophy(13.dp, description = null)
                Text(stringResource(R.string.achievement_unlocked).uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = pal.signal)
            }
            Text(unlock.name, color = pal.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (unlock.game.isNotBlank()) Text(unlock.game, color = pal.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Preview(widthDp = 420, heightDp = 150)
@Composable
private fun AchievementNotificationPreview() = DroidDeckTheme {
    Box(Modifier.padding(20.dp)) {
        AchievementNotificationCard(AchievementNotifications.Unlock("1", "first", "First Steps", "", null, "Hollow Valley"))
    }
}
