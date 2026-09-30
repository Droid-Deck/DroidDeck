package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless

/**
 * The frame generation picker, anchored to whatever opened it (host key "fg"). One copy for Setup,
 * a game's launch settings and the session drawer, so they say the same thing. Importing a
 * Lossless.dll is offered once the account is known to own Lossless Scaling.
 */
@Composable
internal fun FrameGenMenu(
    host: MenuHost, mode: FrameGen.Mode, lossless: Lossless.State,
    onPick: (FrameGen.Mode) -> Unit,
    onImportLossless: () -> Unit,
) {
    val context = LocalContext.current
    AnchoredMenu(host.open == "fg", onDismiss = { if (host.open == "fg") host.open = null }, title = stringResource(R.string.frame_gen_title)) { firstItemFocus ->
        val need = when {
            lossless.ready -> null
            lossless.owned -> stringResource(R.string.lsfg_needs_install)
            else -> stringResource(R.string.lsfg_needs_steam)
        }
        @Composable
        fun item(m: FrameGen.Mode, enabled: Boolean = true, detail: String? = null, focus: FocusRequester? = null) =
            MenuItem(FrameGen.label(context, m), checked = mode == m, enabled = enabled, detail = detail, focusRequester = focus) {
                onPick(m); host.open = null
            }
        item(FrameGen.Mode.OFF, focus = firstItemFocus)
        for (m in 2..4) item(FrameGen.Mode(FrameGen.ENGINE_WINFG, m))
        val lsfg = (2..4).map { FrameGen.Mode(FrameGen.ENGINE_LSFG, it) } +
            FrameGen.ADAPTIVE_TARGETS.map { FrameGen.Mode(FrameGen.ENGINE_LSFG, target = it) }
        lsfg.forEachIndexed { i, m -> item(m, enabled = lossless.ready, detail = need.takeIf { i == 0 }) }
        if (lossless.owned) {
            val using = when (lossless.source) {
                Lossless.Source.STEAM -> stringResource(R.string.lsfg_using_steam)
                Lossless.Source.IMPORT -> stringResource(R.string.lsfg_using_import)
                Lossless.Source.NONE -> null
            }
            MenuItem(stringResource(R.string.lsfg_import), checked = false, detail = using) { host.open = null; onImportLossless() }
        }
    }
}
