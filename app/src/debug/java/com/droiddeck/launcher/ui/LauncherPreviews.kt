package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@DroidDeckPreviews
@Composable
internal fun LauncherPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(), PreviewData.frontActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun RuntimeMissingPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(ready = false), PreviewData.frontActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun RuntimeDownloadingPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(ready = false, busy = true), PreviewData.frontActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun LibraryPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(), PreviewData.frontActions, initialSelection = "games")
    }
}

@DroidDeckPreviews
@Composable
internal fun EmptyLibraryPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(empty = true), PreviewData.frontActions, initialSelection = "games")
    }
}

@DroidDeckPreviews
@Composable
internal fun GameDetailsPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(), PreviewData.frontActions, initialSelection = "app:100")
    }
}

@DroidDeckPreviews
@Composable
internal fun DesktopPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(), PreviewData.frontActions, initialSelection = "desktop")
    }
}

@DroidDeckPreviews
@Composable
internal fun EmulatorPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(), PreviewData.frontActions, initialSelection = "emu:dolphin")
    }
}

@DroidDeckPreviews
@Composable
internal fun ArtPreview() {
    PreviewHost {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            ArtGrid(PreviewData.games.map { Tile(it.name, "Installed", null, it.gameIdString, onClick = {}) })
        }
    }
}

@DroidDeckPreviews
@Composable
internal fun UpdatesPreview() {
    PreviewHost {
        UpdatesPage(PreviewData.front(updates = UpdatesState(catalog = PreviewData.updatesCatalog)), PreviewData.frontActions, Modifier.fillMaxSize().padding(24.dp), installed = PreviewData.installed)
    }
}

@DroidDeckPreviews
@Composable
internal fun UpdatesDownloadingPreview() {
    PreviewHost {
        UpdatesPage(PreviewData.front(updates = UpdatesState(catalog = PreviewData.updatesCatalog, stage = "Downloading", percent = 42)), PreviewData.frontActions, Modifier.fillMaxSize().padding(24.dp), installed = PreviewData.installed)
    }
}

@DroidDeckPreviews
@Composable
internal fun UpdatesErrorPreview() {
    PreviewHost {
        UpdatesPage(PreviewData.front(updates = UpdatesState(error = "Could not check for updates")), PreviewData.frontActions, Modifier.fillMaxSize().padding(24.dp), installed = PreviewData.installed)
    }
}

@Preview(name = "Paper theme", widthDp = 960, heightDp = 540, apiLevel = 34)
@Composable
internal fun AlternateThemePreview() {
    PreviewHost(theme = Themes.PAPER) { FrontEndScreen(PreviewData.front(), PreviewData.frontActions) }
}

@DroidDeckPreviews
@Composable
internal fun StorePreview() = PreviewHost {
    val apps = listOf(
        com.droiddeck.launcher.store.FlathubApi.AppSummary("org.videolan.VLC", "VLC", "Media player", null, "VideoLAN", true, 100_000),
        com.droiddeck.launcher.store.FlathubApi.AppSummary("org.mozilla.firefox", "Firefox", "Web browser", null, "Mozilla", true, 100_000),
    )
    StorePage(PreviewData.front(), PreviewData.frontActions, Modifier.fillMaxSize(), previewSections = mapOf("popular" to apps, "trending" to apps))
}

@DroidDeckPreviews
@Composable
internal fun AndroidAppsPreview() = PreviewHost {
    FrontEndScreen(PreviewData.front(home = true), PreviewData.frontActions, initialSelection = "android-apps")
}
