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
 * Ported to DroidDeck from FusionHUD (Bannerlator's HUD); DroidDeck changes are marked in the code.
 */

package com.droiddeck.launcher.hud

/**
 * Size modes for the Fusion HUD (the 4th selectable in-game overlay style). One shared color-coded
 * visual language, four amounts of detail — see the approved mockup. The config value is the lower-case
 * token stored under the `hudSize` key; the label is the user-facing string.
 */
enum class FusionSize(val token: String, val label: String) {
    FULL("full", "Full"),
    TILES("tiles", "Tiles"),
    PILL("pill", "Pill"),
    MINIMAL("minimal", "Minimal"),
    MEGA("mega", "Mega");

    /** Next size in the tap-cycle: Full → Tiles → Pill → Minimal → Mega → Full. */
    fun next(): FusionSize {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    /**
     * The metric toggle chips this size actually renders — the SINGLE source of truth queried by BOTH
     * config surfaces ({@code FpsCounterConfigDialog} + in-game {@code XServerDrawer} HudContent) so the
     * chip list can't drift from what the view draws. Chip ids are the chip LABELS used in the UI.
     * (Hiding a chip is purely UI; buildConfig() still emits every key — strip-invariant.)
     */
    fun supportedChips(): Set<String> = when (this) {
        MINIMAL -> MINIMAL_CHIPS
        PILL -> PILL_CHIPS
        FULL, TILES -> STANDARD_CHIPS
        MEGA -> MEGA_CHIPS
    }

    companion object {
        fun from(token: String?): FusionSize =
            values().firstOrNull { it.token.equals(token, ignoreCase = true) } ?: FULL

        // Chip-label ids (must match the labels the config UIs build). "Clock" is a small subtle
        // corner readout available in EVERY size, so it's in all sets.
        // The API/engine label ("Engine" chip) shows on the FPS row in EVERY size, so it's in all sets.
        // "Wrapper" and "DX ver" are Mega-only: the wrapper appears solely below Mega's frametime graph,
        // and "DX ver" gates the DX-version suffix on Mega's FPS-row engine label.
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

/**
 * The Wayland HDR state the Fusion HUD shows on its own line, directly under the latency · display-server
 * line (the host sets it with [FusionHudView.setHdrState]). [NONE] = no line at all: every session whose
 * HDR gate is closed (X11, SDR screens, HDR off) keeps the HUD exactly as it was.
 */
object FusionHdr {
    const val NONE = 0
    const val ON = 1           // HDR frames on screen with HDR headroom
    const val NO_HEADROOM = 2  // HDR frames on screen, but the display gives them no headroom (5 s+)
    const val OFF = 3          // the drawer's HDR output switch is off (tone-mapped to SDR)
    const val READY = 4        // HDR open for the session, no HDR frames on screen right now
    const val TONEMAPPED = 5   // HDR output on, but the frames are shown tone-mapped (frame generation
                               // on a screen that offers no HDR10 swapchain)
    const val NOT_ON_THIS_SCREEN = 6 // HDR open for the session, but the screen the game is on NOW
                               // reports no HDR10 (the TV was unplugged mid-game). The offer to the
                               // game cannot be withdrawn once made, so the gate stays open for the
                               // session — but "ready" would be a lie on a panel that cannot show it.
}
