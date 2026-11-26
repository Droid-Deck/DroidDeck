package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class QuickTile(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val value: String? = null,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

internal object QuickTiles {
    val FPS_STEPS = listOf(0, 30, 40, 45, 60)

    fun nextFps(current: Int): Int = FPS_STEPS.firstOrNull { it > current } ?: 0

    fun nextFrameGen(current: FrameGen.Mode): FrameGen.Mode =
        if (current.engine == FrameGen.ENGINE_OFF) FrameGen.Mode(FrameGen.ENGINE_DDFG) else FrameGen.Mode.OFF
}

private const val REFRESH_MS = 500L

@Composable
internal fun quickTiles(actions: BottomPageActions): List<QuickTile> {
    val a = actions.quick() ?: return emptyList()
    val on = stringResource(R.string.bottom_quick_on)
    val off = stringResource(R.string.bottom_quick_off)
    return buildList {
        actions.takeScreenshot?.let { shoot ->
            add(QuickTile("screenshot", stringResource(R.string.bottom_shots_take), Icons.Filled.PhotoCamera) { shoot {} })
        }
        add(QuickTile("fps", stringResource(R.string.drawer_frame_limit), Icons.Filled.Timer,
            if (a.fpsLimit > 0) "${a.fpsLimit}" else off, a.fpsLimit > 0) { a.onFpsLimit(QuickTiles.nextFps(a.fpsLimit)) })
        add(QuickTile("framegen", stringResource(R.string.frame_gen_title), Icons.Filled.AutoAwesome,
            if (a.frameGen.engine == FrameGen.ENGINE_OFF) off else "${a.frameGen.multiplier}x", a.frameGen.engine != FrameGen.ENGINE_OFF) {
            a.onFrameGenPick(QuickTiles.nextFrameGen(a.frameGen))
        })
        add(QuickTile("hud", stringResource(R.string.drawer_hud), Icons.Filled.Speed, if (a.hudOn) on else off, a.hudOn) { a.onHud(!a.hudOn) })
        if (a.hdrAvailable) add(QuickTile("hdr", stringResource(R.string.mode_hdr), Icons.Filled.HdrOn, if (a.hdr) on else off, a.hdr) { a.onHdr(!a.hdr) })
        if (a.gameSwapAvailable) add(QuickTile("swap", stringResource(R.string.swap_screens), Icons.Filled.SwapVert, active = a.gameOnBottom) {
            a.onGameOnBottom(!a.gameOnBottom)
        })
        actions.screenOff?.let { add(QuickTile("screen-off", stringResource(R.string.bottom_screen_off), Icons.Filled.DarkMode, onClick = it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BottomQuickPage(actions: BottomPageActions) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(REFRESH_MS)
            tick++
        }
    }
    tick.let { QuickTileGrid(quickTiles(actions)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickTileGrid(tiles: List<QuickTile>) {
    FlowRow(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (tile in tiles) QuickTileView(tile)
    }
}

@Composable
private fun QuickTileView(tile: QuickTile) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.width(150.dp).height(96.dp).clip(shape)
            .background(if (tile.active) pal.signal.copy(alpha = 0.18f) else pal.surface)
            .border(1.dp, if (tile.active) pal.signal else pal.line, shape)
            .clickable(role = Role.Button, onClick = tile.onClick).testTag("quick-${tile.id}")
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(tile.icon, contentDescription = null, tint = if (tile.active) pal.signal else colors.onBackground, modifier = Modifier.size(24.dp))
        Column {
            Text(tile.label, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                maxLines = if (tile.value == null) 2 else 1, overflow = TextOverflow.Ellipsis)
            tile.value?.let { Text(it, fontSize = 12.sp, color = if (tile.active) pal.signal else colors.onSurfaceVariant, maxLines = 1) }
        }
    }
}
