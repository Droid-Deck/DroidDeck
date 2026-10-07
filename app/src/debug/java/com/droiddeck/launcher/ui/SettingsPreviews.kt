package com.droiddeck.launcher.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable

@DroidDeckPreviews
@Composable
internal fun SetupPreview() {
    PreviewHost {
        FrontEndScreen(PreviewData.front(), PreviewData.frontActions, initialSelection = "setup")
    }
}

@DroidDeckPreviews
@Composable
internal fun ControllerMappingPreview() {
    PreviewHost {
        ControllerMappingPage(mapOf("a" to "b"), { _, _ -> }, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun ControlsPreview() {
    PreviewHost {
        val host = rememberMenuHost()
        SettingsPage(host, "Controller", {}, scroll = rememberScrollState()) {
            ControllerRows(host, "auto", PreviewData.controller, PreviewData.frontActions.controller!!)
        }
    }
}

@DroidDeckPreviews
@Composable
internal fun WidgetsPreview() {
    PreviewHost {
        val host = rememberMenuHost()
        SettingsPage(host, "Settings controls", {}, scroll = rememberScrollState()) {
            SettingsGroup("Display") {
                ChoiceRow(host, "resolution", "Resolution", "Virtual screen size", listOf("720p" to "1280 × 720", "1080p" to "1920 × 1080"), "720p") {}
                ToggleRow(host, "hdr", "HDR", "High dynamic range", true) {}
                SliderRow("Sharpness", null, 75, 0..100) {}
                ActionRow("GPU driver", null, "Manage", {})
            }
        }
    }
}

@DroidDeckPreviews
@Composable
internal fun SteamDisplayPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(), PreviewData.modeActions, initialTab = 0)
    }
}

@DroidDeckPreviews
@Composable
internal fun SteamControlsPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(), PreviewData.modeActions, initialTab = 1)
    }
}

@DroidDeckPreviews
@Composable
internal fun SteamGamesPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(), PreviewData.modeActions, initialTab = 2)
    }
}

@DroidDeckPreviews
@Composable
internal fun SteamAudioPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(), PreviewData.modeActions, initialTab = 3)
    }
}

@DroidDeckPreviews
@Composable
internal fun SteamSessionPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(), PreviewData.modeActions, initialTab = 4)
    }
}

@DroidDeckPreviews
@Composable
internal fun SteamSteamPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(), PreviewData.modeActions, initialTab = 5)
    }
}

@DroidDeckPreviews
@Composable
internal fun DesktopSettingsPreview() {
    PreviewHost {
        ModeSettingsPage(PreviewData.mode(steam = false), PreviewData.modeActions)
    }
}

@DroidDeckPreviews
@Composable
internal fun PerformancePreview() {
    PreviewHost {
        PerformancePage(
            cores = List(8) { CoreRow(it, if (it < 4) "Efficiency" else "Performance") },
            clientOverride = false,
            clientCores = setOf(0, 1, 2, 3),
            gameCores = (0..7).toSet(),
            tuSysmem = false,
            zinkLazy = false,
            glThread = false,
            noGlError = false,
            noXalia = false,
            fastSync = false,
            syncFallback = false,
            fsyncFirst = false,
            gamescopeRealtime = false,
            gpuClockPin = false,
            prootNoSeccomp = false,
            prootFastPath = false,
            guestHostname = "droiddeck",
            phantomWarning = null,
            onClientOverride = {},
            onTuSysmem = {},
            onZinkLazy = {},
            onGlThread = {},
            onNoGlError = {},
            onNoXalia = {},
            onFastSync = {},
            onSyncFallback = {},
            onFsyncFirst = {},
            onGamescopeRealtime = {},
            onGpuClockPin = {},
            onProotNoSeccomp = {},
            onProotFastPath = {},
            onGuestHostname = {},
            onClientCore = { _, _ -> },
            onGameCore = { _, _ -> },
            onDismiss = {},
        )
    }
}

@DroidDeckPreviews
@Composable
internal fun SetupControllerPreview() = PreviewHost {
    SetupPanel(PreviewData.front(), PreviewData.frontActions, {}, {}, initialTab = 1)
}

@DroidDeckPreviews
@Composable
internal fun SetupSessionPreview() = PreviewHost {
    SetupPanel(PreviewData.front(), PreviewData.frontActions, {}, {}, initialTab = 2)
}

@DroidDeckPreviews
@Composable
internal fun SetupLauncherPreview() = PreviewHost {
    SetupPanel(PreviewData.front(), PreviewData.frontActions, {}, {}, initialTab = 3)
}
