/*
 * FusionHUD — a lightweight in-game performance HUD for Android / Winlator overlays.
 * Copyright (C) The412Banner  <https://github.com/The412Banner>
 *
 * Licensed under the GNU General Public License v3.0 (see LICENSE), with an
 * ADDITIONAL attribution requirement under GPL-3.0 §7(b) (see ATTRIBUTION.md):
 * any use, fork, or distribution must preserve credit to "The412Banner" and a
 * link to https://github.com/The412Banner/FusionHUD in the project documentation
 * AND in the in-app credits/about screen.
 *
 * Ported to DroidDeck from FusionHUD (Bannerlator's HUD) and modified for DroidDeck.
 */

package com.droiddeck.launcher.hud

/**
 * Size modes for the DroidDeck HUD (the 4th selectable in-game overlay style). One shared color-coded
 * visual language, four amounts of detail — see the approved mockup. The config value is the lower-case
 * token stored under the `hudSize` key; the label is the user-facing string.
 */
/** The text sizes and spacing a [DroidDeckHudSize] lays its rows out with, in sp. */
data class DroidDeckHudStyle(
    val row: Float,
    val unit: Float,
    val pad: Float,
    val lineGap: Float,
    val labelGap: Float,
    val key: Float = 0f,
    val sub: Float = 0f,
    val foot: Float = 0f,
    val band: Float = 0f,
    val gutter: Float = 0f,
    val tileGap: Float = 0f,
    val innerPad: Float = 0f,
)

enum class DroidDeckHudSize(val token: String, val label: String) {
    FULL("full", "Full"),
    TILES("tiles", "Tiles"),
    PILL("pill", "Pill"),
    MINIMAL("minimal", "Minimal"),
    MEGA("mega", "Mega"),
    STRIP("strip", "Strip");

    fun next(): DroidDeckHudSize {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    /**
     * The rows this size draws, in the order it draws them before the user moves any. This is the single
     * source of truth for both surfaces: [DroidDeckHudView] draws exactly these and the settings screen
     * offers exactly these. A switch outside the set is ignored by the size, so the two cannot drift.
     */
    val rows: List<String> get() = ROWS.getValue(this)

    /** This size's rows: the ones the user moved first, then the rest in the order [rows] lists them. */
    fun rowsIn(stored: List<String>): List<String> =
        stored.filter { it in rows } + rows.filter { it !in stored }
    /**
     * The numbers each size sets its text in, in sp. One table so a size's scale can be read (and changed)
     * in one place instead of hunting the `sp(...)` literals scattered through its builder.
     */
    val style: DroidDeckHudStyle get() = STYLE.getValue(this)


    companion object {
        fun from(token: String?): DroidDeckHudSize =
            values().firstOrNull { it.token.equals(token, ignoreCase = true) } ?: FULL

        private val EXTRAS = setOf("showClock")

        val ROWS: Map<DroidDeckHudSize, List<String>> = mapOf(
            FULL to listOf(
                "showFPS", "showFPSGraph", "showGpuModel", "showDisplay", "showHdr", "showGPULoad", "showGpuTemp",
                "showCPUUsage", "showTemp", "showVram", "showRAM", "showBattery", "showBatteryTemp",
                "showPower", "showLatency", "showAvg", "showLow1", "showLow01", "showLow001",
            ),
            TILES to listOf(
                "showFPS", "showFPSGraph", "showLatency", "showGPULoad", "showGpuTemp", "showCPUUsage", "showTemp",
                "showVram", "showRAM", "showEngine", "showDisplay", "showHdr", "showAvg", "showLow1",
                "showLow01", "showLow001", "showGpuModel", "showBattery", "showBatteryTemp", "showPower",
            ),
            PILL to listOf(
                "showFPS", "showFPSGraph", "showGpuModel", "showGPULoad", "showGpuTemp", "showCPUUsage", "showTemp",
                "showRAM", "showBattery", "showBatteryTemp", "showPower", "showLatency", "showDisplay",
                "showVram", "showHdr",
            ),
            MINIMAL to listOf(
                "showFPS", "showFPSGraph", "showLow1", "showLow01", "showLow001", "showAvg", "showLatency",
                "showEngine", "showDisplay", "showHdr",
            ),
            MEGA to DroidDeckHudConfig.ORDER.filter { it !in EXTRAS },
            STRIP to listOf(
                "showFPS", "showFPSGraph", "showLow1", "showLow01", "showLow001", "showGPULoad", "showGpuTemp",
                "showCPUUsage", "showTemp", "showVram", "showRAM", "showBattery", "showBatteryTemp",
                "showPower", "showDisplay", "showHdr", "showGpuModel",
            ),
        )


        val STYLE: Map<DroidDeckHudSize, DroidDeckHudStyle> = mapOf(
            FULL to DroidDeckHudStyle(row = 12f, unit = 12f * 0.62f, pad = 10f, lineGap = 4f, labelGap = 8f),
            TILES to DroidDeckHudStyle(row = 18f, unit = 11f, pad = 9f, lineGap = 4f, labelGap = 8f,
                key = 10f, sub = 10f, tileGap = 6f, innerPad = 8f),
            PILL to DroidDeckHudStyle(row = 30f, unit = 30f * 0.36f, pad = 10f, lineGap = 3f, labelGap = 12f,
                sub = 11.5f),
            MINIMAL to DroidDeckHudStyle(row = 34f, unit = 34f * 0.32f, pad = 10f, lineGap = 4f, labelGap = 8f,
                sub = 11.5f, foot = 9.5f),
            MEGA to DroidDeckHudStyle(row = 11.5f, unit = 11.5f * 0.62f, pad = 10f, lineGap = 3.5f, labelGap = 7f,
                band = 9.5f, gutter = 16f),
            STRIP to DroidDeckHudStyle(row = 11.5f, unit = 11.5f * 0.8f, pad = 10f, lineGap = 0f, labelGap = 13f),
        )

        fun rowsFor(size: DroidDeckHudSize, values: Map<String, String>): List<String> =
            size.rowsIn(DroidDeckHudConfig.storedOrder(values, size))
    }
}

enum class DroidDeckHudDock(val token: String) {
    NONE("none"), LEFT("left"), RIGHT("right"), TOP("top"), BOTTOM("bottom");

    companion object {
        fun from(token: String?): DroidDeckHudDock = values().firstOrNull { it.token == token } ?: NONE
    }
}

object DroidDeckHudHdr {
    const val NONE = 0
    const val ON = 1
    const val NO_HEADROOM = 2
    const val OFF = 3
    const val READY = 4
    const val TONEMAPPED = 5
    const val NOT_ON_THIS_SCREEN = 6
}
