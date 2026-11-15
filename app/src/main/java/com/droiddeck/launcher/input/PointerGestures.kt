package com.droiddeck.launcher.input

import android.view.ViewConfiguration

class PointerGestures {
    interface Listener {
        fun onMove(x: Float, y: Float)
        fun onButton(button: Int, pressed: Boolean, x: Float, y: Float)
        fun onWheel(steps: Int)
        fun onLongPress()
        fun onScroll(dx: Float, dy: Float) {}
        fun onScrollEnd() {}
        fun onZoom(steps: Int) {}
    }

    companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112

        fun slop(context: android.content.Context) = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
}
