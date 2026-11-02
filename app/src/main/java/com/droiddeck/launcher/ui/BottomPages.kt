package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import com.droiddeck.launcher.R

class BottomPageActions(
    val takeScreenshot: ((onDone: (Boolean) -> Unit) -> Unit)? = null,
    val fps: () -> Float? = { null },
    val openSettings: ((intent: android.content.Intent, done: () -> Boolean) -> Unit)? = null,
    val runningGame: () -> String? = { null },
)

internal const val BOTTOM_PAGE_TELEMETRY = "telemetry"

fun sessionBottomPages(actions: BottomPageActions): List<BottomPage> = listOf(
    BottomPage(BOTTOM_PAGE_TELEMETRY, R.string.bottom_page_telemetry, Icons.Outlined.Speed) { BottomTelemetryPage(actions.fps, actions.runningGame) },
    BottomPage("downloads", R.string.bottom_page_downloads, Icons.Outlined.Download) { BottomDownloadsPage() },
    BottomPage("notifications", R.string.bottom_page_notifications, Icons.Outlined.Notifications) { BottomNotificationsPage(actions.openSettings) },
    BottomPage("screenshots", R.string.bottom_page_screenshots, Icons.Outlined.PhotoLibrary) { BottomScreenshotsPage(actions.takeScreenshot) },
    BottomPage("chat", R.string.bottom_page_chat, Icons.Outlined.Forum) { BottomChatPage() },
)
