package com.droiddeck.launcher.input

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent

object AndroidPointerSource {
    enum class Kind { MOUSE, TOUCHPAD, TOUCHSCREEN, OTHER }

    fun classification(event: MotionEvent): Int =
        if (Build.VERSION.SDK_INT >= 29) event.classification else 0

    fun kind(event: MotionEvent, classification: Int = classification(event)): Kind {
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return Kind.TOUCHPAD
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        if (mouse) {
            val tool = event.getToolType(0)
            if (classification in 3..5 || tool == MotionEvent.TOOL_TYPE_FINGER ||
                (tool == MotionEvent.TOOL_TYPE_UNKNOWN &&
                    event.device?.supportsSource(InputDevice.SOURCE_TOUCHPAD) == true))
                return Kind.TOUCHPAD
            return Kind.MOUSE
        }
        return if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) Kind.TOUCHSCREEN else Kind.OTHER
    }

    fun requestsKeyboardFocus(event: MotionEvent): Boolean = when (kind(event)) {
        Kind.TOUCHSCREEN -> event.actionMasked == MotionEvent.ACTION_DOWN
        Kind.MOUSE, Kind.TOUCHPAD -> event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            (event.actionMasked == MotionEvent.ACTION_DOWN && event.buttonState != 0)
        Kind.OTHER -> false
    }
}
