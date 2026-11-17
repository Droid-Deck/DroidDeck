package com.droiddeck.launcher.input

import android.view.MotionEvent

internal fun inertTwoFingerGestures() = TwoFingerGestures(16f, object : TwoFingerGestures.Listener {
    override fun cancelTouch() {}
    override fun replay(event: MotionEvent) {}
    override fun rightClick(x: Float, y: Float) {}
    override fun scrollStart(x: Float, y: Float) {}
    override fun scroll(dx: Float, dy: Float) {}
    override fun scrollEnd() {}
})
