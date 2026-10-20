package com.droiddeck.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import java.text.Collator

internal enum class GestureActionGroup(@StringRes val title: Int) {
    MOUSE(R.string.gesture_group_mouse), APP(R.string.gesture_group_app),
    NAVIGATION(R.string.gesture_group_navigation), LETTERS(R.string.gesture_group_letters),
    NUMBERS(R.string.gesture_group_numbers), FUNCTION(R.string.gesture_group_function),
    MODIFIERS(R.string.gesture_group_modifiers), NUMPAD(R.string.gesture_group_numpad),
    SYMBOLS(R.string.gesture_group_symbols),
}

internal data class GestureAction(val id: String, val title: String, val group: GestureActionGroup)

internal fun gestureActionGroup(id: String): GestureActionGroup {
    if (id == "show_keyboard") return GestureActionGroup.APP
    if (id in setOf("left_click", "right_click", "middle_click", "left_click_drag", "right_click_drag",
            "middle_mouse_pan", "inverted_middle_mouse_pan", "scroll_wheel")) return GestureActionGroup.MOUSE
    val key = id.removePrefix("key_")
    return when {
        key.startsWith("KP_") -> GestureActionGroup.NUMPAD
        key.length == 1 && key[0] in 'A'..'Z' -> GestureActionGroup.LETTERS
        key.length == 1 && key[0].isDigit() -> GestureActionGroup.NUMBERS
        key.matches(Regex("F(?:[1-9]|1[0-2])")) -> GestureActionGroup.FUNCTION
        key.startsWith("CTRL_") || key.startsWith("SHIFT_") || key.startsWith("ALT_") || key.endsWith("_LOCK") -> GestureActionGroup.MODIFIERS
        key in setOf("MINUS", "EQUAL", "BRACKET_LEFT", "BRACKET_RIGHT", "SEMICOLON", "APOSTROPHE",
            "GRAVE", "TILDE", "BACKSLASH", "COMMA", "PERIOD", "SLASH") || id == "plus_minus" -> GestureActionGroup.SYMBOLS
        else -> GestureActionGroup.NAVIGATION
    }
}

internal fun filterGestureActions(actions: List<GestureAction>, query: String, group: GestureActionGroup?): List<GestureAction> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val collator = Collator.getInstance()
    return actions.filter { action ->
        (group == null || action.group == group) && words.all { word ->
            action.title.contains(word, ignoreCase = true) || action.id.removePrefix("key_").replace('_', ' ').contains(word, ignoreCase = true)
        }
    }.sortedWith(compareBy<GestureAction> { it.group.ordinal }.thenComparator { a, b ->
        // Function keys follow their numeric sequence; names use the UI locale.
        if (a.group == GestureActionGroup.FUNCTION) {
            a.id.removePrefix("key_F").toInt().compareTo(b.id.removePrefix("key_F").toInt())
        } else collator.compare(a.title, b.title)
    }.thenBy { it.id })
}

@Composable
internal fun gestureActionTitle(action: String): String {
    val named = when (action) {
        "left_click" -> R.string.gesture_left_click
        "right_click" -> R.string.gesture_right_click
        "middle_click" -> R.string.gesture_middle_click
        "show_keyboard" -> R.string.gesture_keyboard
        "left_click_drag" -> R.string.gesture_left_drag
        "right_click_drag" -> R.string.gesture_right_drag
        "middle_mouse_pan" -> R.string.gesture_middle_pan
        "inverted_middle_mouse_pan" -> R.string.gesture_middle_pan_inverted
        "arrow_keys" -> R.string.gesture_arrows
        "inverted_arrow_keys" -> R.string.gesture_arrows_inverted
        "inverted_wasd" -> R.string.gesture_wasd_inverted
        "scroll_wheel" -> R.string.gesture_scroll
        else -> null
    }
    if (named != null) return stringResource(named)
    return when (action) {
        "wasd" -> "WASD"
        "plus_minus" -> "+ / −"
        "page_up_down" -> stringResource(R.string.gesture_key_page_up) + " / " + stringResource(R.string.gesture_key_page_down)
        else -> gestureKeyTitle(action.removePrefix("key_"))
    }
}

@Composable
private fun gestureKeyTitle(key: String): String {
    if (key.startsWith("KP_")) return stringResource(R.string.gesture_key_numpad, gestureKeyTitle(key.removePrefix("KP_")))
    val named = when (key) {
        "ESC" -> R.string.gesture_key_escape
        "BKSP" -> R.string.gesture_key_backspace
        "ENTER" -> R.string.gesture_key_enter
        "TAB" -> R.string.gesture_key_tab
        "SPACE" -> R.string.gesture_key_space
        "DEL" -> R.string.gesture_key_delete
        "INSERT" -> R.string.gesture_key_insert
        "HOME" -> R.string.gesture_key_home
        "END" -> R.string.gesture_key_end
        "PRIOR" -> R.string.gesture_key_page_up
        "NEXT" -> R.string.gesture_key_page_down
        "PRTSCN" -> R.string.gesture_key_print_screen
        "UP" -> R.string.gesture_key_up
        "DOWN" -> R.string.gesture_key_down
        "LEFT" -> R.string.gesture_key_left_arrow
        "RIGHT" -> R.string.gesture_key_right_arrow
        "CAPS_LOCK" -> R.string.gesture_key_caps_lock
        "NUM_LOCK" -> R.string.gesture_key_num_lock
        "SCROLL_LOCK" -> R.string.gesture_key_scroll_lock
        else -> null
    }
    if (named != null) return stringResource(named)
    if (key.startsWith("CTRL_") || key.startsWith("SHIFT_") || key.startsWith("ALT_")) {
        val name = stringResource(when (key.substringBefore('_')) {
            "CTRL" -> R.string.pckb_key_ctrl
            "SHIFT" -> R.string.gesture_key_shift
            else -> R.string.pckb_key_alt
        })
        return stringResource(if (key.endsWith("_L")) R.string.gesture_key_left else R.string.gesture_key_right, name)
    }
    return when (key) {
        "MINUS", "SUBTRACT" -> "−"
        "EQUAL" -> "="
        "ADD" -> "+"
        "MULTIPLY" -> "×"
        "DIVIDE", "SLASH" -> "/"
        "BRACKET_LEFT" -> "["
        "BRACKET_RIGHT" -> "]"
        "SEMICOLON" -> ";"
        "APOSTROPHE" -> "'"
        "GRAVE" -> "`"
        "TILDE" -> "~"
        "BACKSLASH" -> "\\"
        "COMMA" -> ","
        "PERIOD" -> "."
        else -> key.replace('_', ' ')
    }
}
