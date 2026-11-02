package com.droiddeck.launcher.input

import android.view.MotionEvent

internal fun MotionEvent.pointerSubset(pointerIdBits: Int): MotionEvent? {
    val indices = (0 until pointerCount).filter { index ->
        pointerIdBits and (1 shl getPointerId(index)) != 0
    }
    if (indices.isEmpty()) return null
    val splitIndex = indices.indexOf(actionIndex)
    val splitAction = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> if (splitIndex >= 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
        MotionEvent.ACTION_POINTER_DOWN -> if (splitIndex >= 0) {
            if (indices.size == 1) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or (splitIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else MotionEvent.ACTION_MOVE
        MotionEvent.ACTION_UP -> if (splitIndex >= 0) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
        MotionEvent.ACTION_POINTER_UP -> if (splitIndex >= 0) {
            if (indices.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (splitIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else MotionEvent.ACTION_MOVE
        else -> action
    }
    val properties = Array(indices.size) { MotionEvent.PointerProperties() }
    val coordinates = Array(indices.size) { MotionEvent.PointerCoords() }
    indices.forEachIndexed { target, index ->
        getPointerProperties(index, properties[target])
        getPointerCoords(index, coordinates[target])
    }
    return MotionEvent.obtain(
        downTime, eventTime, splitAction, indices.size, properties, coordinates,
        metaState, buttonState, xPrecision, yPrecision,
        deviceId, edgeFlags, source, flags,
    )
}
