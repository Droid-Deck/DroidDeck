package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.droiddeck.launcher.session.SessionPrefs

object DualScreen {
    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"
    val modes = listOf(MODE_AUTO, MODE_ON, MODE_OFF)
    private const val TYPE_INTERNAL = 1
    private const val TYPE_OVERLAY = 4
    private const val TYPE_VIRTUAL = 5
    private val knownModels = setOf("retroid pocket duo", "ayn thor")

    fun builtInDisplay(displayManager: DisplayManager, primaryDisplayId: Int): Display? {
        val awake = displayManager.getDisplay(primaryDisplayId)?.let { it.state != Display.STATE_OFF } ?: true
        if (displayManager.displays.count { addressable(it, awake) } < 2) return null
        return displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
            it.displayId != primaryDisplayId && (it.flags and Display.FLAG_PRESENTATION) != 0 && addressable(it, awake) &&
                isBuiltIn(displayType(it), Build.MODEL)
        }
    }

    private fun addressable(display: Display, awake: Boolean): Boolean =
        display.isValid && addressable(displayType(display), display.state, awake)

    internal fun addressable(type: Int?, state: Int, awake: Boolean): Boolean =
        type != TYPE_VIRTUAL && type != TYPE_OVERLAY && (!awake || state != Display.STATE_OFF)

    fun display(context: Context, displayManager: DisplayManager, primaryDisplayId: Int): Display? =
        when (SessionPrefs.dualScreenMode(context)) {
            MODE_OFF -> null
            MODE_ON -> builtInDisplay(displayManager, primaryDisplayId) ?: anyDisplay(displayManager, primaryDisplayId)
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
