package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.droiddeck.launcher.session.LoadingState

@DroidDeckPreviews
@Composable
internal fun LoadingPreview() {
    PreviewHost {
        LoadingOverlay("Downloading runtime", 42, "00:32", "", false, LoadingState.Topic.RUNTIME, true, onCancel = {})
    }
}

@DroidDeckPreviews
@Composable
internal fun SessionStartingPreview() {
    PreviewHost {
        LoadingOverlay("Starting Steam", -1, "00:08", "", false, LoadingState.Topic.STEAM_STARTING, true, onCancel = {})
    }
}

@DroidDeckPreviews
@Composable
internal fun SessionErrorPreview() {
    PreviewHost {
        LoadingOverlay("Steam stopped", -1, "00:08", "Try starting Steam again.", true, LoadingState.Topic.STEAM, true, endedDetail = "Exit code 1", onRetry = {}, onShareLogs = {}, onClose = {})
    }
}

@DroidDeckPreviews
@Composable
internal fun KeyboardPreview() {
    PreviewHost {
        PcKeyboard({ _, _ -> }, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun HudPreview() {
    PreviewHost {
        HudText("60 FPS  •  GPU 72%  •  CPU 38%")
    }
}

@DroidDeckPreviews
@Composable
internal fun CursorPreview() {
    PreviewHost {
        CursorOverlay(Offset(220f, 180f), true, 2f)
    }
}

@DroidDeckPreviews
@Composable
internal fun DrawerControllerPreview() {
    PreviewHost {
        SessionDrawer(true, SessionDrawerPage.CONTROLLER, false, {}, PreviewData.drawerActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun DrawerSecondScreenPreview() {
    PreviewHost {
        SessionDrawer(true, SessionDrawerPage.SECOND_SCREEN, false, {}, PreviewData.drawerActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun DrawerDisplayPreview() {
    PreviewHost {
        SessionDrawer(true, SessionDrawerPage.DISPLAY, false, {}, PreviewData.drawerActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun DrawerEffectsPreview() {
    PreviewHost {
        SessionDrawer(true, SessionDrawerPage.EFFECTS, false, {}, PreviewData.drawerActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun DrawerGamesPreview() {
    PreviewHost {
        SessionDrawer(true, SessionDrawerPage.GAMES, false, {}, PreviewData.drawerActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun DrawerSessionPreview() {
    PreviewHost {
        SessionDrawer(true, SessionDrawerPage.SESSION, false, {}, PreviewData.drawerActions)
    }
}
