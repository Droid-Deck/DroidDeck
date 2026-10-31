package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import com.droiddeck.launcher.R

/** The pages the bottom-screen dashboard offers during a session, in tab order. */
fun sessionBottomPages(): List<BottomPage> = listOf(
    BottomPage("downloads", R.string.bottom_page_downloads, Icons.Outlined.Download) { BottomDownloadsPage() },
    BottomPage("notifications", R.string.bottom_page_notifications, Icons.Outlined.Notifications) { BottomNotificationsPage() },
)
