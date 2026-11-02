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

    fun supportedChips(): Set<String> = when (this) {
        MINIMAL -> MINIMAL_CHIPS
        PILL -> PILL_CHIPS
        FULL, TILES, STRIP -> STANDARD_CHIPS
        MEGA -> MEGA_CHIPS
    }

    companion object {
        fun from(token: String?): DroidDeckHudSize =
            values().firstOrNull { it.token.equals(token, ignoreCase = true) } ?: FULL

        val MINIMAL_CHIPS = setOf("FPS", "FPS graph", "0.01% low", "FPS .1", "Engine", "Clock")
        val PILL_CHIPS = setOf("FPS", "FPS graph", "GPU", "CPU", "VRAM", "RAM", "Battery", "GPU model", "FPS .1", "Engine", "Clock")
        val STANDARD_CHIPS = setOf(
            "FPS", "FPS graph", "GPU", "GPU temp", "CPU", "VRAM", "RAM", "Power", "Temp",
            "Battery", "GPU model", "Engine", "0.01% low", "FPS .1", "Clock",
        )
        val MEGA_ONLY = setOf(
            "Per-core", "Swap", "Network", "Resolution", "Proton", "Wrapper", "DX ver", "Session",
        )
        val MEGA_CHIPS = STANDARD_CHIPS + MEGA_ONLY
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
