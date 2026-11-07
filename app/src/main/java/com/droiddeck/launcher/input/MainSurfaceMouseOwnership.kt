package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent

internal enum class MainSurfaceMouseEvent {
    MOVE,
    BUTTON_PRESS,
    BUTTON_RELEASE,
    HOVER_OR_SCROLL,
    CANCEL,
    OTHER,
}

internal data class MainSurfaceMouseDecision(
    val forward: Boolean,
    val releaseButtons: Int = 0,
)

internal class MainSurfaceMouseOwnership {
    private data class State(var accepted: Int = 0, var rejected: Int = 0)

    private val devices = mutableMapOf<Int, State>()

    fun handle(
        deviceId: Int,
        event: MainSurfaceMouseEvent,
        actionButton: Int,
        insideSurface: Boolean,
    ): MainSurfaceMouseDecision {
        val state = devices.getOrPut(deviceId, ::State)
        val decision = when (event) {
            MainSurfaceMouseEvent.BUTTON_PRESS -> {
                when {
                    state.accepted and actionButton != 0 -> MainSurfaceMouseDecision(false)
                    insideSurface -> {
                        val forward = acceptedByOtherDevices(deviceId, actionButton) == 0
                        state.accepted = state.accepted or actionButton
                        state.rejected = state.rejected and actionButton.inv()
                        MainSurfaceMouseDecision(forward)
                    }
                    else -> {
                        state.rejected = state.rejected or actionButton
                        MainSurfaceMouseDecision(false)
                    }
                }
            }
            MainSurfaceMouseEvent.BUTTON_RELEASE -> {
                val accepted = state.accepted and actionButton != 0
                state.accepted = state.accepted and actionButton.inv()
                state.rejected = state.rejected and actionButton.inv()
                MainSurfaceMouseDecision(accepted && acceptedByOtherDevices(deviceId, actionButton) == 0)
            }
            MainSurfaceMouseEvent.MOVE -> MainSurfaceMouseDecision(
                when {
                    state.accepted != 0 -> true
                    state.rejected != 0 -> false
                    else -> insideSurface
                },
            )
            MainSurfaceMouseEvent.HOVER_OR_SCROLL,
            MainSurfaceMouseEvent.OTHER -> MainSurfaceMouseDecision(insideSurface)
            MainSurfaceMouseEvent.CANCEL -> {
                val release = state.accepted and acceptedByOtherDevices(deviceId, state.accepted).inv()
                state.accepted = 0
                state.rejected = 0
                MainSurfaceMouseDecision(false, release)
            }
        }
        if (state.accepted == 0 && state.rejected == 0) devices.remove(deviceId)
        return decision
    }

    fun clear(): Int = devices.values.fold(0) { buttons, state -> buttons or state.accepted }.also {
        devices.clear()
    }

    fun removeDevice(deviceId: Int): Int {
        val state = devices.remove(deviceId) ?: return 0
        return state.accepted and acceptedByOtherDevices(deviceId, state.accepted).inv()
    }

    private fun acceptedByOtherDevices(deviceId: Int, buttons: Int): Int =
        devices.asSequence().filter { it.key != deviceId }.fold(0) { accepted, entry ->
            accepted or entry.value.accepted
        } and buttons
}

internal fun acceptsMainSurfaceContact(source: Int, action: Int): Boolean {
    val contactSource = source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN ||
        source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
    return contactSource && action in setOf(
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_POINTER_UP,
    )
}

internal fun consumesMainSurfaceActivityFallback(source: Int): Boolean =
    source and InputDevice.SOURCE_CLASS_POINTER == InputDevice.SOURCE_CLASS_POINTER
