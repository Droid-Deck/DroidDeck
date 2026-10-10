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

// A link from a control in one section to a page in another (the Play sheet's Graphics drivers,
// a game's Components card): the control squashes into a dot that flies to the destination's rail
// item, the rail's selection glides there as it passes the top of its arc, and the page floods
// out of the rail item once it lands. B on that page flies the dot back and puts the source
// surface back as it was, focus on the control that asked.

/**
 * Where a hop came from: the rail item that was showing ([rail]), the surface on it (a sheet, the
 * Games page) and its tab, the control's key and its root bounds, and the words for the way back.
 * [page] is the page key the hop opened, so leaving it any other way forgets the way back.
 */
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

/** The surfaces a hop can start from and come back to. */
internal const val HOP_SURFACE_GAMES = "games"
internal const val HOP_SURFACE_SHEET = "sheet"

internal object Hops {
    /** The hop the page on screen was reached by, while B can still undo it. */
    var origin by mutableStateOf<HopOrigin?>(null)

    /** Set while a hop's dot is flying: the rail item its selection has already glided to. */
    var railPreview by mutableStateOf<String?>(null)

    /**
     * Set as a page leaves through a hop (either way), so the pane sinks it rather than draining
     * it; the pane clears it once the change has started.
     */
    var linking by mutableStateOf(false)

    /** A surface to put back as it was (a sheet re-lifted on its tab), once, and the control to focus. */
    var restore by mutableStateOf<HopOrigin?>(null)

    /** Per rail item, bumped when a dot lands on it: the item pops. */
    val railLanded = mutableStateMapOf<String, Int>()

    /** Bumped when a hop's page has flooded in: its target (a layer card) sends out one ring. */
    var arrived by mutableIntStateOf(0)

    /** Where each rail item sits, root px. */
    val railBounds = HashMap<String, Rect>()

    /** Where each control that can start a hop sits, root px. */
    val sources = HashMap<String, Rect>()

    /**
     * Hops from the control at [origin].bounds to [origin].dest: [go] opens the page there once the dot lands.
     * With animations off there is no dot and no flood, only the page.
     */
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
        Flights.fly(Flight(
            origin.bounds, to = { railAt.center },
            onHalf = { railPreview = origin.dest },
            onLand = finish,
        ))
    }

    /**
     * B on a page reached by a hop: the page sinks, the source surface comes back as it was, and
     * the dot flies home from the rail item to the control. False when there is no hop to undo.
     */
    fun back(close: () -> Unit): Boolean {
        val o = origin ?: return false
        origin = null
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

    /** The page the hop opened has gone some other way: there is no way back to keep. */
    fun forget(pageKey: String?) {
        val o = origin ?: return
        if (pageKey != o.page) origin = null
    }

    /** Screen density, for the rail item's corner; set by the front end. */
    var density = 1f
}

/** Remembers where this control sits, so a hop from it can fly from it and back to it. */
internal fun Modifier.hopSource(key: String): Modifier = onGloballyPositioned { Hops.sources[key] = it.boundsInRoot() }

/**
 * "B · back to <source>" at the right of a page's header while it was reached by a hop; a tap is B.
 * Nothing when it was not.
 */
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
