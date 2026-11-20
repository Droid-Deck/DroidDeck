package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs

enum class FaceGlyphs { MONO_LETTERS, COLOR_LETTERS, COLOR_SYMBOLS, MONO_SYMBOLS }
enum class DpadStyle { CROSS, DISC, ARROWS }
enum class StickCap { RINGED, DISHED, KNURLED }

enum class PadKit(val face: FaceGlyphs, val dpad: DpadStyle, val stick: StickCap, private val xbox: Boolean = false) {
    STEAM_DECK(FaceGlyphs.MONO_LETTERS, DpadStyle.CROSS, StickCap.RINGED),
    STEAM_CONTROLLER(FaceGlyphs.MONO_LETTERS, DpadStyle.CROSS, StickCap.RINGED),
    XBOX(FaceGlyphs.COLOR_LETTERS, DpadStyle.DISC, StickCap.DISHED, xbox = true),
    DUALSHOCK(FaceGlyphs.COLOR_SYMBOLS, DpadStyle.ARROWS, StickCap.KNURLED),
    DUALSENSE(FaceGlyphs.MONO_SYMBOLS, DpadStyle.ARROWS, StickCap.KNURLED);

    val steam get() = this == STEAM_DECK || this == STEAM_CONTROLLER
    val playStation get() = this == DUALSHOCK || this == DUALSENSE

    fun label(control: InputControl): String = when (control) {
        InputControl.LB -> if (xbox) "LB" else "L1"
        InputControl.RB -> if (xbox) "RB" else "R1"
        InputControl.LT -> if (xbox) "LT" else "L2"
        InputControl.RT -> if (xbox) "RT" else "R2"
        else -> control.label
    }

    /** What the player calls [control] on this controller. */
    fun name(control: InputControl): String = if (!playStation) label(control) else when (control) {
        InputControl.A -> "Cross"
        InputControl.B -> "Circle"
        InputControl.X -> "Square"
        InputControl.Y -> "Triangle"
        else -> label(control)
    }

    companion object {
        fun of(type: String) = when (type) {
            SessionPrefs.CONTROLLER_STEAM -> STEAM_CONTROLLER
            SessionPrefs.CONTROLLER_XBOX360 -> XBOX
            SessionPrefs.CONTROLLER_DS4 -> DUALSHOCK
            SessionPrefs.CONTROLLER_DUALSENSE -> DUALSENSE
            else -> STEAM_DECK
        }

        fun current(context: Context) = of(SessionPrefs.steamController(context))
    }
}
