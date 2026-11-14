package com.droiddeck.launcher.input

import android.graphics.RectF

/**
 * The area of the session view that drives the guest pointer.
 *
 * The view is also the compositor output only while it shows the picture: the phone, or the
 * bottom screen. With the glasses owning the output the view is a touchpad for a scene drawn
 * elsewhere, so the whole view has to drive the pointer. Using the picture rect there would
 * confine it to the letterboxed band the scene would occupy on the phone, and the pointer could
 * not reach the scene edges.
 */
object PointerSpace {
    fun area(viewWidth: Int, viewHeight: Int, glassesOwnOutput: Boolean, picture: RectF?): RectF? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (glassesOwnOutput) return RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        return picture
    }
}
