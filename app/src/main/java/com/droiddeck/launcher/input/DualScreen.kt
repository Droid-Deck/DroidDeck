package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.abs
import kotlin.math.roundToInt

object DualScreen {
    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"
    val modes = listOf(MODE_AUTO, MODE_ON, MODE_OFF)
    private const val TYPE_INTERNAL = 1
    private val knownModels = setOf("retroid pocket duo", "ayn thor")
    private val touchedDisplays = mutableSetOf<Int>()

    fun builtInDisplay(displayManager: DisplayManager, primaryDisplayId: Int): Display? {
        val primary = displayManager.getDisplay(primaryDisplayId)
        val awake = primary?.state != Display.STATE_OFF
        val panels = if (awake) touchPanels() else emptyList()
        return internalDisplay(displayManager, primaryDisplayId) {
            if (awake && it.state == Display.STATE_OFF) return@internalDisplay false
            synchronized(touchedDisplays) {
                if (awake && hasOwnTouchPanel(size(it), primary?.let(::size), panels)) touchedDisplays += it.displayId
                it.displayId in touchedDisplays
            }
        }
    }

    private fun internalDisplay(displayManager: DisplayManager, primaryDisplayId: Int, accept: (Display) -> Boolean = { true }): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
            it.isValid && it.displayId != primaryDisplayId && (it.flags and Display.FLAG_PRESENTATION) != 0 &&
                isBuiltIn(displayType(it), Build.MODEL) && accept(it)
        }

    internal fun hasOwnTouchPanel(display: Pair<Int, Int>, primary: Pair<Int, Int>?, panels: List<Pair<Int, Int>>): Boolean {
        val matching = panels.count { covers(it, display) }
        return if (primary != null && covers(primary, display)) matching >= 2 else matching >= 1
    }

    private fun covers(panel: Pair<Int, Int>, display: Pair<Int, Int>): Boolean =
        (near(panel.first, display.first) && near(panel.second, display.second)) ||
            (near(panel.first, display.second) && near(panel.second, display.first))

    private fun near(a: Int, b: Int) = abs(a - b) <= 2

    private fun size(display: Display): Pair<Int, Int> =
        display.mode.let { it.physicalWidth to it.physicalHeight }

    private fun touchPanels(): List<Pair<Int, Int>> =
        InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val device = InputDevice.getDevice(id) ?: return@mapNotNull null
            if (device.isVirtual || !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return@mapNotNull null
            val x = device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHSCREEN) ?: return@mapNotNull null
            val y = device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHSCREEN) ?: return@mapNotNull null
            (x.max - x.min + 1f).roundToInt() to (y.max - y.min + 1f).roundToInt()
        }

    fun display(context: Context, displayManager: DisplayManager, primaryDisplayId: Int): Display? =
        when (SessionPrefs.dualScreenMode(context)) {
            MODE_OFF -> null
            MODE_ON -> internalDisplay(displayManager, primaryDisplayId) ?: anyDisplay(displayManager, primaryDisplayId)
            else -> builtInDisplay(displayManager, primaryDisplayId)
        }

    fun anyDisplay(displayManager: DisplayManager, primaryDisplayId: Int): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
            it.isValid && it.displayId != primaryDisplayId && (it.flags and Display.FLAG_PRESENTATION) != 0
        }

    internal fun isBuiltIn(type: Int?, model: String): Boolean =
        if (type != null) type == TYPE_INTERNAL else model.trim().lowercase() in knownModels

    private fun displayType(display: Display): Int? =
        runCatching { Display::class.java.getMethod("getType").invoke(display) as Int }.getOrNull()
}
