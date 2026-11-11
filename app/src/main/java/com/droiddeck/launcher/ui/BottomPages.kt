package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

class BottomPageActions(
    val takeScreenshot: ((onDone: (Boolean) -> Unit) -> Unit)? = null,
    val fps: () -> Float? = { null },
    val runningGame: () -> String? = { null },
)

internal const val BOTTOM_PAGE_TELEMETRY = "telemetry"

fun sessionBottomPages(actions: BottomPageActions): List<BottomPage> = listOf(
    BottomPage(BOTTOM_PAGE_TELEMETRY, R.string.bottom_page_telemetry, Icons.Outlined.Speed) { BottomTelemetryPage(actions.fps, actions.runningGame) },
    BottomPage("downloads", R.string.bottom_page_downloads, Icons.Outlined.Download) { BottomDownloadsPage() },
    BottomPage("screenshots", R.string.bottom_page_screenshots, Icons.Outlined.PhotoLibrary) { BottomScreenshotsPage(actions.takeScreenshot) },
    BottomPage("chat", R.string.bottom_page_chat, Icons.Outlined.Forum) { BottomChatPage() },
)

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
