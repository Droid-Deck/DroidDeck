package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R


@Composable
internal fun FrameGenGroup(s: FrontEndState, a: FrontEndActions) {
    val host = rememberMenuHost()
    SettingsGroup(stringResource(R.string.frame_gen_title)) {
        SettingsRow(stringResource(R.string.frame_gen_title), stringResource(R.string.frame_gen_hint), highlighted = host.open == "fg") {
            Box {
                ValueChip(s.frameGenLabel, host.open == "fg") { host.open = if (host.open == "fg") null else "fg" }
                FrameGenMenu(host, s.frameGen, s.lossless, a.onFrameGenPick, a.onImportLossless)
            }
        }
    }
}

@Composable
internal fun PerformanceLink(a: FrontEndActions, rail: String, tab: String) {
    val id = "sheet:performance"
    val title = stringResource(R.string.setup_tool_performance)
    SettingsGroup(title) {
        LinkRow(title, stringResource(R.string.setup_tool_performance_hint), stringResource(R.string.store_open), id, arrow = "›") {
            Hops.sources[id]?.let { at ->
                PageOrigin.mark(Origin(at, 12f * Hops.density))
                Hops.restore = HopOrigin(rail, HOP_SURFACE_SHEET, tab, id, at, "", dest = rail, page = "performance")
            }
            a.onPerformance()
        }
    }
}

@Composable
internal fun DriversLink(a: FrontEndActions, summary: String?, rail: String, back: String) {
    val id = "sheet:gpu-drivers"
    SettingsGroup(stringResource(R.string.mode_drivers)) {
        LinkRow(
            stringResource(R.string.sheet_graphics_drivers), summary ?: stringResource(R.string.common_auto),
            stringResource(R.string.rail_components), id,
        ) {
            val at = Hops.sources[id]
            if (at == null) a.onComponentsTab(GPU_TAB)
            else Hops.go(HopOrigin(rail, HOP_SURFACE_SHEET, SheetTab.DISPLAY, id, at, back, dest = "components", page = "components")) {
                a.onComponentsTab(GPU_TAB)
            }
        }
    }
}
