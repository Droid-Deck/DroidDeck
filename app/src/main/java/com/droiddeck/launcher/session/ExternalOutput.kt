package com.droiddeck.launcher.session

import android.os.Build
import android.view.Display

object ExternalOutput {
    fun select(displays: List<Display>, exclude: Set<Int>): Display? = displays.firstOrNull {
        it.isValid && it.displayId != Display.DEFAULT_DISPLAY && it.displayId !in exclude &&
            (it.flags and Display.FLAG_PRESENTATION) != 0
    }

    fun preferredMode(display: Display): Display.Mode {
        val modes = display.supportedModes
        val fastest = modes.maxOfOrNull { it.refreshRate } ?: return display.mode
        val smooth = minOf(fastest, SMOOTH_HZ) - 1f
        return modes.filter { it.refreshRate >= smooth }.maxWithOrNull(
            compareBy<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight }.thenBy { Math.round(it.refreshRate) }
        ) ?: display.mode
    }

    fun swapchainSize(display: Display.Mode, sessionHeight: Int): Pair<Int, Int>? {
        val w = display.physicalWidth
        val h = display.physicalHeight
        if (w <= 0 || h <= 0 || h <= MAX_SWAPCHAIN_HEIGHT) return null
        val height = maxOf(sessionHeight, MAX_SWAPCHAIN_HEIGHT).coerceAtMost(h)
        return (w.toLong() * height / h).toInt() to height
    }

    private const val SMOOTH_HZ = 60f
    private const val MAX_SWAPCHAIN_HEIGHT = 1440

    fun describe(display: Display): String {
        val product = if (Build.VERSION.SDK_INT >= 31) display.deviceProductInfo?.name?.takeIf { it.isNotBlank() } else null
        val modes = display.supportedModes.joinToString(", ") { mode(it) }
        return "display ${display.displayId} \"${display.name}\"" + (product?.let { " ($it)" } ?: "") +
            " current ${mode(display.mode)}, preferred ${mode(preferredMode(display))}, supported [$modes]"
    }

    fun mode(m: Display.Mode): String = "${m.physicalWidth}x${m.physicalHeight}@${"%.2f".format(java.util.Locale.ROOT, m.refreshRate)}"
}
