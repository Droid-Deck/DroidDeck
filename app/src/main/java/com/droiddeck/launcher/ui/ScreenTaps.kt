package com.droiddeck.launcher.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.first

internal object ScreenTaps {
    var count by mutableStateOf(0)
}

/** A field that opens on a heavy page can take focus after the next tap has already finished. */
@Composable
internal fun CancelPendingFocus(pending: Boolean, askedAtTap: Int, focused: Boolean, cancel: () -> Unit) {
    val focusedNow by rememberUpdatedState(focused)
    val cancelNow by rememberUpdatedState(cancel)
    LaunchedEffect(pending, askedAtTap) {
        if (!pending) return@LaunchedEffect
        snapshotFlow { ScreenTaps.count }.first { it != askedAtTap }
        if (!focusedNow) cancelNow()
    }
}

/**
 * Tapped controls do not take focus in touch mode, so a text field would otherwise keep it, and its highlight.
 * [x] and [y] share coordinates with [origin], the field's own position.
 */
internal fun releaseTextField(field: EditText, origin: IntArray, x: Float, y: Float) {
    val margin = 12 * field.resources.displayMetrics.density
    val insideX = x >= origin[0] - margin && x <= origin[0] + field.width + margin
    val insideY = y >= origin[1] - margin && y <= origin[1] + field.height + margin
    if (insideX && insideY) return
    field.clearFocus()
    (field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(field.windowToken, 0)
}

/** A dialog's touches never reach the activity, so a dialog holding a search field counts its taps and releases the field itself. */
internal fun Modifier.releaseTextFieldOnTap(): Modifier = composed {
    val view = LocalView.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onGloballyPositioned { placed[0] = it }.pointerInput(view) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            ScreenTaps.count++
            var up: Offset? = null
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                if (!change.pressed) { up = change.position; break }
            }
            val at = up?.let { placed[0]?.takeIf { c -> c.isAttached }?.localToWindow(it) } ?: return@awaitEachGesture
            view.post {
                val field = view.findFocus() as? EditText ?: return@post
                releaseTextField(field, IntArray(2).also(field::getLocationInWindow), at.x, at.y)
            }
        }
    }
}
