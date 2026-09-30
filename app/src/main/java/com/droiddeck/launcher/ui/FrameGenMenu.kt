package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
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
    host: MenuHost, engine: String, multiplier: Int, lossless: Lossless.State,
    onPick: (engine: String, multiplier: Int) -> Unit,
    onImportLossless: () -> Unit,
) {
    val context = LocalContext.current
    AnchoredMenu(host.open == "fg", onDismiss = { if (host.open == "fg") host.open = null }, title = stringResource(R.string.frame_gen_title)) { firstItemFocus ->
        val need = when {
            lossless.ready -> null
            lossless.owned -> stringResource(R.string.lsfg_needs_install)
            else -> stringResource(R.string.lsfg_needs_steam)
        }
        fun pick(e: String, m: Int) { onPick(e, m); host.open = null }
        MenuItem(stringResource(R.string.frame_gen_off), checked = engine == FrameGen.ENGINE_OFF, focusRequester = firstItemFocus) { pick(FrameGen.ENGINE_OFF, 2) }
        for (m in 2..4) MenuItem(FrameGen.label(context, FrameGen.ENGINE_WINFG, m), checked = engine == FrameGen.ENGINE_WINFG && multiplier == m) { pick(FrameGen.ENGINE_WINFG, m) }
        for (m in 2..4) MenuItem(FrameGen.label(context, FrameGen.ENGINE_LSFG, m), checked = engine == FrameGen.ENGINE_LSFG && multiplier == m, enabled = lossless.ready, detail = need) { pick(FrameGen.ENGINE_LSFG, m) }
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
