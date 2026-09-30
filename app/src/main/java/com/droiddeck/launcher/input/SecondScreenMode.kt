package com.droiddeck.launcher.input

import android.hardware.display.DisplayManager
import android.view.Display

/** The control surface shown on a secondary Android display during a Linux session. */
enum class SecondScreenMode(val id: String, val label: String) {
    NONE("none", "None"),
    KEYBOARD_TRACKPAD("keyboard-trackpad", "Keyboard + trackpad"),
    TERMINAL("terminal", "Terminal"),
    /** The Steam Deck controller's back grips and trackpads; offered while the pad is one. */
    DECK_CONTROLS("deck-controls", "Deck grips + trackpads"),
}

data class SecondScreenDisplay(val id: Int, val label: String)

/** The presentation-capable displays shared by the session controls and Android app launcher. */
object SecondScreenDisplays {
    fun available(displayManager: DisplayManager, primaryDisplayId: Int = Display.DEFAULT_DISPLAY): List<SecondScreenDisplay> =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .asSequence()
            .filter { it.isValid && it.displayId != primaryDisplayId && (it.flags and Display.FLAG_PRESENTATION) != 0 }
            .map { target ->
                val mode = target.mode
                SecondScreenDisplay(
                    target.displayId,
                    "${target.name} · ${mode.physicalWidth}×${mode.physicalHeight}",
                )
            }
            .toList()
}
