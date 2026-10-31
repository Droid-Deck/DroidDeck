package com.droiddeck.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.BottomNotifications
import kotlinx.coroutines.delay

@Composable
fun BottomNotificationsPage() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var granted by remember { mutableStateOf(BottomNotifications.granted(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            granted = BottomNotifications.granted(context)
            delay(2_000L)
        }
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Text(stringResource(R.string.bottom_notifications_why), fontSize = 15.sp, color = colors.onBackground, textAlign = TextAlign.Center)
            BottomPillButton(stringResource(R.string.bottom_notifications_allow), "bottom-notifications-allow") {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        return
    }
    val shown = BottomNotifications.items
    if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.bottom_notifications_none), fontSize = 15.sp, color = colors.onSurfaceVariant)
        }
        return
    }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bottom_notifications_count, shown.size), fontSize = 13.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            BottomPillButton(stringResource(R.string.bottom_notifications_clear), "bottom-notifications-clear") { BottomNotifications.clear() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown.toList(), key = { it.key }) { n ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.app, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(timeFormat.format(java.util.Date(n.postedAt)), fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                    if (n.title.isNotBlank()) Text(n.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.text.isNotBlank()) Text(n.text, fontSize = 14.sp, color = colors.onBackground, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun BottomPillButton(label: String, tag: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(pal.signal.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1)
    }
}
