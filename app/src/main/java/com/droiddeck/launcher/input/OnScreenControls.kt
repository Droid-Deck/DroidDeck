package com.droiddeck.launcher.input

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import com.droiddeck.launcher.ui.InputSurfaceView

class OnScreenControls(context: Context, pad: PadBridge?) : FrameLayout(context) {
    private val surface = InputSurfaceView(context, pad, inputRole = InputRole.PRIMARY)
    private var layout: SavedInputLayout? = null
    var isButtonsOnly = false
        private set
    var onTouchMiss: ((MotionEvent) -> Unit)?
        get() = surface.onTouchMiss
        set(value) { surface.onTouchMiss = value }

    init { addView(surface, LayoutParams(-1, -1)) }

    fun updateLayout(value: SavedInputLayout?) {
        if (layout == value) return
        releaseAll()
        layout = value
        render()
    }

    fun setButtonsOnly(enabled: Boolean) {
        if (isButtonsOnly == enabled) return
        releaseAll()
        isButtonsOnly = enabled
        render()
    }

    private fun render() {
        val elements = layout?.elements.orEmpty()
        surface.updateCustomLayout(if (isButtonsOnly) elements.filter { it.control == InputControl.GUIDE || it.control == InputControl.QAM || it.id == "builtin:keyboard" } else elements)
        surface.updateStyle(layout?.style ?: InputSurfaceStyle())
    }

    fun takesTouchAt(x: Float, y: Float) = surface.takesTouchAt(x, y)
    fun releaseAll() = surface.releaseInputs()
    fun reload() { releaseAll(); render() }

    override fun onDetachedFromWindow() { releaseAll(); super.onDetachedFromWindow() }
}
