package com.droiddeck.launcher.input

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import com.droiddeck.launcher.R

enum class SecondScreenMode(val id: String, @StringRes val label: Int) {
    NONE("none", R.string.second_mode_none),
    KEYBOARD_TRACKPAD("keyboard-trackpad", R.string.second_mode_keyboard_trackpad),
    TERMINAL("terminal", R.string.second_mode_terminal),
    DECK_CONTROLS("deck-controls", R.string.second_mode_deck_controls),
    DASHBOARD("dashboard", R.string.second_mode_dashboard),
    DESKTOP("desktop", R.string.second_mode_desktop);

    companion object {
        fun opening(remembered: String?, deckPad: Boolean, dashboard: Boolean = false, desktop: Boolean = false): SecondScreenMode {
            entries.firstOrNull {
                it != NONE && it.id == remembered && (it != DECK_CONTROLS || deckPad) && (it != DASHBOARD || dashboard) &&
                    (it != DESKTOP || desktop)
            }?.let { return it }
            // No pick yet: the dashboard. A pick that can't open right now keeps the old fallback.
            val picked = entries.any { it != NONE && it.id == remembered }
            return when {
                dashboard && !picked -> DASHBOARD
                deckPad -> DECK_CONTROLS
                else -> KEYBOARD_TRACKPAD
            }
        }
    }
}

data class SecondScreenDisplay(val id: Int, val label: String)

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
