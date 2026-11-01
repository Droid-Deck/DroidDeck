package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoLibrary
import com.droiddeck.launcher.R

/** What the bottom-screen pages can ask the running session to do. */
class BottomPageActions(
    val takeScreenshot: ((onDone: (Boolean) -> Unit) -> Unit)? = null,
    val fps: () -> Float? = { null },
    /** Opens an Android settings page on the bottom screen; the dashboard comes back once [done] is true. */
    val openSettings: ((intent: android.content.Intent, done: () -> Boolean) -> Unit)? = null,
    /** The focused game's Steam app id as text (or another key for non-Steam programs); null in the Steam menus. */
    val runningGame: () -> String? = { null },
)

/** The In-game stats page's id; it has its own HUD, so the strip under the tabs is not drawn there. */
internal const val BOTTOM_PAGE_TELEMETRY = "telemetry"

/** The pages the bottom-screen dashboard offers during a session, in tab order. */
fun sessionBottomPages(actions: BottomPageActions): List<BottomPage> = listOf(
    BottomPage(BOTTOM_PAGE_TELEMETRY, R.string.bottom_page_telemetry, Icons.Outlined.Speed) { BottomTelemetryPage(actions.fps, actions.runningGame) },
    BottomPage("downloads", R.string.bottom_page_downloads, Icons.Outlined.Download) { BottomDownloadsPage() },
    BottomPage("notifications", R.string.bottom_page_notifications, Icons.Outlined.Notifications) { BottomNotificationsPage(actions.openSettings) },
    BottomPage("screenshots", R.string.bottom_page_screenshots, Icons.Outlined.PhotoLibrary) { BottomScreenshotsPage(actions.takeScreenshot) },
    BottomPage("chat", R.string.bottom_page_chat, Icons.Outlined.Forum) { BottomChatPage() },
)
