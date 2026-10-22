package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.TouchscreenGestures.Gesture

@Composable
fun GestureFeedback(gesture: Gesture?) {
    Box(Modifier.fillMaxSize()) {
        if (gesture != null) Text(stringResource(when (gesture) {
            Gesture.TAP -> R.string.gesture_tap
            Gesture.DRAG -> R.string.gesture_drag
            Gesture.LONG_PRESS -> R.string.gesture_long_press
            Gesture.DOUBLE_TAP -> R.string.gesture_double_tap
            Gesture.TWO_FINGER_TAP -> R.string.gesture_two_tap
            Gesture.TWO_FINGER_DRAG -> R.string.gesture_two_drag
            Gesture.TWO_FINGER_HOLD -> R.string.gesture_two_hold
            Gesture.PINCH -> R.string.gesture_pinch
            Gesture.THREE_FINGER_TAP -> R.string.gesture_three_tap
            Gesture.THREE_FINGER_DRAG -> R.string.gesture_three_drag
            Gesture.THREE_FINGER_HOLD -> R.string.gesture_three_hold
        }),
            Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface)
    }
}
