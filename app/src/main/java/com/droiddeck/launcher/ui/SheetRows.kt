package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionDisplay
import com.droiddeck.launcher.session.SessionPrefs

// The rows the lifted sheets add to what the session settings already had: frame generation,
// Performance and the link to the GPU drivers on Components.

/**
 * What a mode's lip says: its session size, then (when [full]) its frame-rate cap and upscaler,
 * then its touch mode, each left out at its default.
 */
internal fun lipFor(ctx: Context, mode: String, full: Boolean): List<Pair<String, String>> {
    val panel = SessionDisplay.panelSize(ctx)
    val size = SessionDisplay.resolveChoice(panel, SessionPrefs.resolutionChoice(ctx, mode, panel))
    val fps = if (full) SessionPrefs.fpsLimit(ctx, mode) else 0
    val upscaler = if (!full) null else SessionPrefs.upscaler(ctx).takeIf { it != 0 }
        ?.let { m -> SessionPrefs.upscalerChoices(ctx).firstOrNull { it.first == m }?.second }
    val touch = when (SessionPrefs.touchMode(ctx)) {
        SessionPrefs.TOUCH_PAD -> ctx.getString(R.string.mode_touch_touchpad)
        SessionPrefs.TOUCH_DIRECT -> ctx.getString(R.string.mode_touch_direct)
        SessionPrefs.TOUCH_OFF -> ctx.getString(R.string.lip_touch_off)
        else -> null
    }
    return lipParts(size, fps, { ctx.getString(R.string.lip_fps, it) }, upscaler, touch)
}

/** Frame generation, moved here from Setup: it is how Play's games are drawn. */
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

/**
 * Performance (CPU core assignment), opened from the sheet's Session chip: its page floods out of
 * this row, and Back draws it into the row again with the sheet lifted where it was.
 */
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

/**
 * The GPU drivers in use, read only, and a link to where they are set: A hops to Components with
 * the GPU drivers layer chosen, and B there comes back to this row.
 */
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
