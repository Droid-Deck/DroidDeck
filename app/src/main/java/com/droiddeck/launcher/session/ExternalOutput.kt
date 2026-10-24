package com.droiddeck.launcher.session

import android.os.Build
import android.view.Display

object ExternalOutput {
    fun select(displays: List<Display>, exclude: Set<Int>): Display? = displays.firstOrNull {
        it.isValid && it.displayId != Display.DEFAULT_DISPLAY && it.displayId !in exclude &&
            (it.flags and Display.FLAG_PRESENTATION) != 0
    }

    fun preferredMode(display: Display): Display.Mode =
        display.supportedModes.maxWithOrNull(
            compareBy<Display.Mode> { Math.round(it.refreshRate) }.thenBy { it.physicalWidth.toLong() * it.physicalHeight }
        ) ?: display.mode

    fun describe(display: Display): String {
        val product = if (Build.VERSION.SDK_INT >= 31) display.deviceProductInfo?.name?.takeIf { it.isNotBlank() } else null
        val modes = display.supportedModes.joinToString(", ") { mode(it) }
        return "display ${display.displayId} \"${display.name}\"" + (product?.let { " ($it)" } ?: "") +
            " current ${mode(display.mode)}, preferred ${mode(preferredMode(display))}, supported [$modes]"
    }

    fun mode(m: Display.Mode): String = "${m.physicalWidth}x${m.physicalHeight}@${"%.2f".format(java.util.Locale.ROOT, m.refreshRate)}"
}
