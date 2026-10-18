package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

internal class HopOrigin(
    val rail: String,
    val surface: String,
    val tab: String?,
    val control: String,
    val bounds: Rect,
    val back: String,
    val dest: String,
    val page: String,
)

internal const val HOP_SURFACE_GAMES = "games"
internal const val HOP_SURFACE_DESKTOP = "desktop"
internal const val HOP_SURFACE_SHEET = "sheet"

internal object Hops {
    var origin by mutableStateOf<HopOrigin?>(null)

    var sinking by mutableStateOf<String?>(null)

    var railPreview by mutableStateOf<String?>(null)

    var linking by mutableStateOf(false)

    var restore by mutableStateOf<HopOrigin?>(null)

    val railLanded = mutableStateMapOf<String, Int>()

    var arrived by mutableIntStateOf(0)

    val railBounds = HashMap<String, Rect>()

    val sources = HashMap<String, Rect>()

    fun go(origin: HopOrigin, go: () -> Unit) {
        val railAt = railBounds[origin.dest]
        val finish: () -> Unit = {
            railPreview = null
            this.origin = origin
            if (railAt != null && Motion.scale != 0f) PageOrigin.mark(Origin(railAt, 14f * density, owner = origin.dest))
            linking = true
            go()
            railLanded[origin.dest] = (railLanded[origin.dest] ?: 0) + 1
            arrived++
        }
        if (railAt == null || Motion.scale == 0f) { finish(); return }
        sinking = origin.rail
        Flights.fly(Flight(
            origin.bounds, to = { railAt.center },
            onHalf = { railPreview = origin.dest },
            onLand = finish,
        ))
    }

    fun back(close: () -> Unit): Boolean {
        val o = origin ?: return false
        origin = null
        sinking = null
        val railAt = railBounds[o.dest]
        railPreview = if (railAt != null && Motion.scale != 0f) o.dest else null
        restore = o
        linking = true
        close()
        if (railAt == null || Motion.scale == 0f) { railPreview = null; return true }
        Flights.fly(Flight(
            railAt, to = { (sources[o.control] ?: o.bounds).center },
            onHalf = { railPreview = null },
        ))
        return true
    }

    fun forget(pageKey: String?) {
        val o = origin ?: return
        if (pageKey != o.page) { origin = null; sinking = null }
    }

    var density = 1f
}

internal val LocalShowSection = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

internal fun Modifier.hopSource(key: String): Modifier = onGloballyPositioned { Hops.sources[key] = it.boundsInRoot() }

@Composable
internal fun HopBackChip(onBack: () -> Unit) {
    val o = Hops.origin ?: return
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        stringResource(R.string.hop_back, o.back), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.heightIn(min = 44.dp).padding(vertical = 6.dp).clip(shape)
            .background(pal.signal.copy(alpha = if (hot) 0.16f else 0.08f))
            .glideBorder(hot, shape, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onBack)
            .controllerConfirm(onClick = onBack)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
