package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

enum class SecondScreenMode(val id: String, @StringRes val label: Int, val bindings: String? = null) {
    NONE("none", R.string.second_mode_none),
    KEYBOARD_TRACKPAD("keyboard-trackpad", R.string.second_mode_keyboard_trackpad, InputStockLayouts.TRACKPAD),
    TERMINAL("terminal", R.string.second_mode_terminal),
    DECK_CONTROLS("deck-controls", R.string.second_mode_deck_controls, InputStockLayouts.DECK),
    CUSTOM_CONTROLS("custom-controls", R.string.input_profile_controls_mode),
    DASHBOARD("dashboard", R.string.second_mode_dashboard),
    DESKTOP("desktop", R.string.second_mode_desktop);

    val controls: Boolean get() = this == CUSTOM_CONTROLS || bindings != null

    fun panel(context: Context): String? = if (this == DECK_CONTROLS) SessionPrefs.deckPanel(context).bindings else bindings

    companion object {
        fun opening(remembered: String?, deckPad: Boolean, dashboard: Boolean = false, bottomDesktop: Boolean = false, desktopSession: Boolean = false): SecondScreenMode {
            entries.firstOrNull {
                it != NONE && it.id == remembered && (it != DECK_CONTROLS || deckPad) && (it != DASHBOARD || dashboard) &&
                    (it != DESKTOP || bottomDesktop)
            }?.let { return it }
            val picked = entries.any { it != NONE && it.id == remembered }
            return when {
                desktopSession -> KEYBOARD_TRACKPAD
                deckPad -> DECK_CONTROLS
                dashboard && !picked -> DASHBOARD
                else -> KEYBOARD_TRACKPAD
            }
        }
    }
}

data class SecondScreenDisplay(val id: Int, val label: String)

object SecondScreenDisplays {
    fun selection(selected: Int, available: List<Int>, gameRequested: Boolean): Int =
        if (selected in available || gameRequested && selected >= 0) selected else available.firstOrNull() ?: -1

    fun selection(selected: Int, available: List<Int>, gameRequested: Boolean, save: (Int) -> Unit): Int =
        selection(selected, available, gameRequested).also { if (it != selected) save(it) }

    fun keyboardOnPrimary(gameRequested: Boolean, selected: Int, available: List<Int>, taken: (Int) -> Boolean): Boolean =
        gameRequested && selected >= 0 && (selected !in available || taken(selected))

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

    fun editorCandidates(selected: Int, available: List<Int>, taken: (Int) -> Boolean): List<Int> =
        (listOf(selected) + available).distinct().filterNot(taken)
}
