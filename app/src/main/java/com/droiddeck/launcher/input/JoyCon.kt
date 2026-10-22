package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object JoyCon {
    const val LEFT = 0
    const val RIGHT = 1

    const val VR_TRIGGER = 1 shl 0
    const val VR_GRIP = 1 shl 1
    const val VR_A = 1 shl 2
    const val VR_B = 1 shl 3
    const val VR_STICK = 1 shl 4
    const val VR_SYSTEM = 1 shl 5
    const val VR_RUMBLE_SLOT = 0x100

    private const val VENDOR = 0x057e
    private const val PRODUCT_LEFT = 0x2006
    private const val PRODUCT_RIGHT = 0x2007

    private const val BTN_SOUTH = 0x130
    private const val BTN_EAST = 0x131
    private const val BTN_NORTH = 0x133
    private const val BTN_WEST = 0x134
    private const val BTN_Z = 0x135
    private const val BTN_TL = 0x136
    private const val BTN_TR = 0x137
    private const val BTN_TL2 = 0x138
    private const val BTN_TR2 = 0x139
    private const val BTN_SELECT = 0x13a
    private const val BTN_START = 0x13b
    private const val BTN_MODE = 0x13c
    private const val BTN_THUMBL = 0x13d
    private const val BTN_THUMBR = 0x13e
    private const val BTN_DPAD_UP = 0x220
    private const val BTN_DPAD_DOWN = 0x221
    private const val BTN_DPAD_LEFT = 0x222
    private const val BTN_DPAD_RIGHT = 0x223
    private val NO_VR_DEVICES = intArrayOf(PadBridge.NO_CONTROLLER, PadBridge.NO_CONTROLLER)
    private var vrOwner: Any? = null
    @Volatile private var vrDevices = NO_VR_DEVICES

    @JvmStatic
    fun side(device: InputDevice?): Int = when {
        device == null || device.isVirtual || device.vendorId != VENDOR -> -1
        device.productId == PRODUCT_LEFT -> LEFT
        device.productId == PRODUCT_RIGHT -> RIGHT
        else -> -1
    }

    @JvmStatic
    fun stickAxes(side: Int, device: InputDevice, source: Int): IntArray = when {
        side == LEFT -> intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        device.getMotionRange(MotionEvent.AXIS_RX, source) != null -> intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        else -> intArrayOf(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
    }

    @JvmStatic
    fun isRail(side: Int, scanCode: Int): Boolean = when (side) {
        LEFT -> scanCode == BTN_TR || scanCode == BTN_TR2
        RIGHT -> scanCode == BTN_TL || scanCode == BTN_TL2
        else -> false
    }

    internal fun padKey(side: Int, scanCode: Int): Int = when (side) {
        LEFT -> when (scanCode) {
            BTN_TL -> KeyEvent.KEYCODE_BUTTON_L1
            BTN_TL2 -> KeyEvent.KEYCODE_BUTTON_L2
            BTN_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
            BTN_THUMBL -> KeyEvent.KEYCODE_BUTTON_THUMBL
            BTN_Z -> KeyEvent.KEYCODE_BUTTON_Z
            BTN_DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
            BTN_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            BTN_DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            BTN_DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
        RIGHT -> when (scanCode) {
            BTN_SOUTH -> KeyEvent.KEYCODE_BUTTON_A
            BTN_EAST -> KeyEvent.KEYCODE_BUTTON_B
            BTN_NORTH -> KeyEvent.KEYCODE_BUTTON_Y
            BTN_WEST -> KeyEvent.KEYCODE_BUTTON_X
            BTN_TR -> KeyEvent.KEYCODE_BUTTON_R1
            BTN_TR2 -> KeyEvent.KEYCODE_BUTTON_R2
            BTN_START -> KeyEvent.KEYCODE_BUTTON_START
            BTN_THUMBR -> KeyEvent.KEYCODE_BUTTON_THUMBR
            BTN_MODE -> KeyEvent.KEYCODE_BUTTON_MODE
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    @JvmStatic
    fun normalize(event: KeyEvent): KeyEvent {
        val side = side(event.device)
        if (side < 0) return event
        val code = padKey(side, event.scanCode)
        if (code == KeyEvent.KEYCODE_UNKNOWN || code == event.keyCode) return event
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source)
    }

    internal fun vrButton(side: Int, scanCode: Int): Int = when (side) {
        LEFT -> when (scanCode) {
            BTN_TL2 -> VR_TRIGGER
            BTN_TL, BTN_TR, BTN_TR2 -> VR_GRIP
            BTN_DPAD_DOWN, BTN_DPAD_RIGHT -> VR_A
            BTN_DPAD_UP, BTN_DPAD_LEFT, BTN_SELECT -> VR_B
            BTN_THUMBL -> VR_STICK
            BTN_Z -> VR_SYSTEM
            else -> 0
        }
        RIGHT -> when (scanCode) {
            BTN_TR2 -> VR_TRIGGER
            BTN_TR, BTN_TL, BTN_TL2 -> VR_GRIP
            BTN_SOUTH, BTN_EAST -> VR_A
            BTN_NORTH, BTN_WEST, BTN_START -> VR_B
            BTN_THUMBR -> VR_STICK
            BTN_MODE -> VR_SYSTEM
            else -> 0
        }
        else -> 0
    }

    @JvmStatic
    fun vrRumbleDevice(slot: Int): Int? =
        if (slot == VR_RUMBLE_SLOT + LEFT || slot == VR_RUMBLE_SLOT + RIGHT) vrDevices[slot - VR_RUMBLE_SLOT] else null

    @Synchronized
    internal fun setVrDevices(owner: Any, devices: IntArray) {
        vrOwner = owner
        vrDevices = devices
    }

    @Synchronized
    internal fun clearVrDevices(owner: Any) {
        if (vrOwner !== owner) return
        vrOwner = null
        vrDevices = NO_VR_DEVICES
    }
}
