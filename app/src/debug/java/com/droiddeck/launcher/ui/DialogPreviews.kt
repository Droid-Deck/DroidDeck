package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.runtime.UserApps

@DroidDeckPreviews
@Composable
internal fun WirelessAdbPreview() {
    PreviewHost {
        WirelessAdbFixPage({}, true, {}, { _, _, _, done -> done("Preview only") }, { _, done -> done(null) }, { _, _, _, done -> done("Preview only") })
    }
}

@DroidDeckPreviews
@Composable
internal fun ProcessLimitPreview() {
    PreviewHost {
        PhantomProcessGatePage(PhantomProcessStatus.ENABLED, {}, {}, {}, {}, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun AddAppPreview() {
    PreviewHost {
        AddAppDialog(true, {}) { _, _ -> }
    }
}

@DroidDeckPreviews
@Composable
internal fun EditAppPreview() {
    PreviewHost {
        EditAppDialog(UserApps.App("sample", UserApps.Kind.APPIMAGE, "Example app", null, "AppImage", "/preview/example.AppImage", emptyList()), {}) { _, _, _ -> }
    }
}

@DroidDeckPreviews
@Composable
internal fun ConfirmationPreview() {
    PreviewHost {
        ConfirmDialog("Remove runtime?", "Remove the installed Linux runtime.", "Remove", {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun CustomResolutionPreview() {
    PreviewHost {
        CustomResolutionDialog(1280 to 720, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun RomsFolderPreview() {
    PreviewHost {
        RomsDialog("/preview/ROMs", {}, {}, {})
    }
}

@DroidDeckPreviews
@Composable
internal fun GameEnvironmentPreview() {
    PreviewHost {
        GameEnvironmentEditor(false, com.droiddeck.launcher.core.GameEnvironment.Config(shared = mapOf("DXVK_HUD" to "fps", "MANGOHUD" to "1"))) {}
    }
}

@DroidDeckPreviews
@Composable
internal fun WindowsComponentsPreview() {
    PreviewHost {
        WinComponentsDialog("100", "Portal 2", null, false, onClose = {}, previewComponents = listOf(
            com.droiddeck.launcher.session.WinComponents.Component("vcrun2022", "Visual C++ runtime", "Microsoft", "ready", emptyList(), emptyList()),
            com.droiddeck.launcher.session.WinComponents.Component("d3dcompiler_47", "DirectX shader compiler", "Microsoft", "ready", emptyList(), emptyList()),
        ))
    }
}

@DroidDeckPreviews
@Composable
internal fun DeveloperDisplayPreview() = PreviewHost {
    DeveloperDisplayChoiceDialog(listOf(2 to "Secondary screen"), {}, {}, {})
}

@DroidDeckPreviews
@Composable
internal fun AppDisplayPreview() = PreviewHost {
    ChooseAppDisplayDialog(com.droiddeck.launcher.HomeApp.LaunchableApp("Files", "preview.files", "preview.files.Main", null),
        com.droiddeck.launcher.input.SecondScreenDisplay(2, "Secondary screen"), {}, {}, {})
}
