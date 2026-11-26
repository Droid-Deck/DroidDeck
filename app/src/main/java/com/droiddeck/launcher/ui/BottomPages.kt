package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.People
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
    val quick: () -> DrawerActions? = { null },
    val screenOff: (() -> Unit)? = null,
)

internal const val BOTTOM_PAGE_TELEMETRY = "telemetry"

fun sessionBottomPages(actions: BottomPageActions): List<BottomPage> = listOf(
    BottomPage(BOTTOM_PAGE_TELEMETRY, R.string.bottom_page_telemetry, Icons.Filled.Speed) { BottomTelemetryPage(actions.fps, actions.runningGame) },
    BottomPage("quick", R.string.drawer_quick_access, Icons.Filled.GridView) { BottomQuickPage(actions) },
    BottomPage("achievements", R.string.achievements_title, Icons.Filled.EmojiEvents) { BottomAchievementsPage(actions.runningGame) },
    BottomPage("companion", R.string.bottom_page_companion, Icons.Filled.Extension) { BottomCompanionPage(actions.runningGame) },
    BottomPage("guides", R.string.bottom_page_guides, Icons.Filled.MenuBook) { BottomGuidesPage(actions.runningGame) },
    BottomPage("downloads", R.string.bottom_page_downloads, Icons.Filled.Download) { BottomDownloadsPage() },
    BottomPage("screenshots", R.string.bottom_page_screenshots, Icons.Filled.Image) { BottomScreenshotsPage(actions.takeScreenshot) },
    BottomPage("chat", R.string.bottom_page_chat, Icons.Filled.People) { BottomChatPage() },
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
