package com.droiddeck.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal object PageSwipe {
    var locks by mutableIntStateOf(0)
    var direction by mutableIntStateOf(0)
}

// Runs on the Final pass so carousels, sliders, scrollers and widgets that consumed the drag keep it.
@Composable
internal fun Modifier.pageSwipe(enabled: () -> Boolean, onSwipe: (Int) -> Unit): Modifier {
    val enabledNow by rememberUpdatedState(enabled)
    val onSwipeNow by rememberUpdatedState(onSwipe)
    return pointerInput(Unit) {
    val distance = 72.dp.toPx()
    val flingDistance = 32.dp.toPx()
    val flingVelocity = 900.dp.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (!enabledNow() || PageSwipe.locks > 0) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var total = Offset.Zero
        var mine = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.size > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) break
            if (!mine && change.isConsumed) return@awaitEachGesture
            total += change.position - change.previousPosition
            tracker.addPointerInputChange(change)
            if (!mine && abs(total.x) > slop && abs(total.x) > abs(total.y) * 2f) mine = true
            if (mine) change.consume()
        }
        if (!mine || PageSwipe.locks > 0) return@awaitEachGesture
        val vx = tracker.calculateVelocity().x
        val horizontal = abs(total.x) > abs(total.y) * 1.5f
        val far = abs(total.x) > distance
        val flung = abs(total.x) > flingDistance && abs(vx) > flingVelocity && (vx > 0) == (total.x > 0)
        if (horizontal && (far || flung)) onSwipeNow(if (total.x < 0) 1 else -1)
    }
    }
}
