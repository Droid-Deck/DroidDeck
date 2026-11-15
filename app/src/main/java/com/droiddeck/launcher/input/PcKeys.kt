package com.droiddeck.launcher.input

import android.content.Context
import androidx.annotation.StringRes
import com.droiddeck.launcher.R

data class PcKey(
    val label: String,
    val code: Int,
    val weight: Float = 1f,
    val shifted: String? = null,
    @StringRes val labelRes: Int = 0,
)

internal fun k(label: String, code: Int, weight: Float = 1f, shifted: String? = null) = PcKey(label, code, weight, shifted)

internal fun k(@StringRes labelRes: Int, code: Int, weight: Float = 1f) = PcKey("", code, weight, null, labelRes)

val PC_KEY_ROWS: List<List<PcKey>> = listOf(
    listOf(
        k(R.string.pckb_key_esc, 1), k("F1", 59), k("F2", 60), k("F3", 61), k("F4", 62), k("F5", 63), k("F6", 64),
        k("F7", 65), k("F8", 66), k("F9", 67), k("F10", 68), k("F11", 87), k("F12", 88),
        k(R.string.pckb_key_ins, 110), k(R.string.pckb_key_del, 111), k(R.string.pckb_key_home, 102), k(R.string.pckb_key_end, 107), k(R.string.pckb_key_pgup, 104), k(R.string.pckb_key_pgdn, 109),
    ),
    listOf(
        k("`", 41, shifted = "~"), k("1", 2, shifted = "!"), k("2", 3, shifted = "@"), k("3", 4, shifted = "#"),
        k("4", 5, shifted = "$"), k("5", 6, shifted = "%"), k("6", 7, shifted = "^"), k("7", 8, shifted = "&"),
        k("8", 9, shifted = "*"), k("9", 10, shifted = "("), k("0", 11, shifted = ")"), k("-", 12, shifted = "_"),
        k("=", 13, shifted = "+"), k(R.string.pckb_key_backspace, 14, 2f),
    ),
    listOf(
        k(R.string.pckb_key_tab, 15, 1.5f), k("q", 16), k("w", 17), k("e", 18), k("r", 19), k("t", 20), k("y", 21),
        k("u", 22), k("i", 23), k("o", 24), k("p", 25), k("[", 26, shifted = "{"), k("]", 27, shifted = "}"),
        k("\\", 43, 1.5f, shifted = "|"),
    ),
    listOf(
        k(R.string.pckb_key_caps, 58, 1.75f), k("a", 30), k("s", 31), k("d", 32), k("f", 33), k("g", 34), k("h", 35),
        k("j", 36), k("k", 37), k("l", 38), k(";", 39, shifted = ":"), k("'", 40, shifted = "\""),
        k(R.string.pckb_key_enter, 28, 2.25f),
    ),
    listOf(
        k(R.string.pckb_key_shift, 42, 2.25f), k("z", 44), k("x", 45), k("c", 46), k("v", 47), k("b", 48), k("n", 49),
        k("m", 50), k(",", 51, shifted = "<"), k(".", 52, shifted = ">"), k("/", 53, shifted = "?"),
        k(R.string.pckb_key_shift, 54, 1.75f), k("↑", 103),
    ),
    listOf(
        k(R.string.pckb_key_ctrl, 29, 1.5f), k(R.string.pckb_key_super, 125, 1.25f), k(R.string.pckb_key_alt, 56, 1.25f), k(R.string.pckb_key_space, 57, 6.25f),
        k(R.string.pckb_key_altgr, 100, 1.25f), k(R.string.pckb_key_ctrl, 97, 1.25f), k("←", 105), k("↓", 108), k("→", 106),
    ),
)

val PC_MODIFIERS: Set<Int> = setOf(42, 54, 29, 97, 56, 100, 125)

// Sticky modifiers are excluded: hiding a locked modifier would leave it pressed forever.
val PC_KEY_HIDDEN_GROUPS: Map<String, Set<Int>> = mapOf(
    "function" to ((59..68).toSet() + setOf(1, 87, 88)),
    "nav" to setOf(110, 111, 102, 107, 104, 109),
    "arrows" to setOf(103, 105, 108, 106),
)

val PC_KEY_GROUP_IDS: Map<Int, String> = mapOf(0 to "function", 1 to "nav", 2 to "arrows")

val PC_KEYS: Map<Int, PcKey> = PC_KEY_ROWS.flatten().associateBy { it.code }

private val PC_KEY_FROM_ANDROID: Map<Int, Int> = mapOf(
    android.view.KeyEvent.KEYCODE_ESCAPE to 1,
    android.view.KeyEvent.KEYCODE_F1 to 59, android.view.KeyEvent.KEYCODE_F2 to 60, android.view.KeyEvent.KEYCODE_F3 to 61,
    android.view.KeyEvent.KEYCODE_F4 to 62, android.view.KeyEvent.KEYCODE_F5 to 63, android.view.KeyEvent.KEYCODE_F6 to 64,
    android.view.KeyEvent.KEYCODE_F7 to 65, android.view.KeyEvent.KEYCODE_F8 to 66, android.view.KeyEvent.KEYCODE_F9 to 67,
    android.view.KeyEvent.KEYCODE_F10 to 68, android.view.KeyEvent.KEYCODE_F11 to 87, android.view.KeyEvent.KEYCODE_F12 to 88,
    android.view.KeyEvent.KEYCODE_GRAVE to 41, android.view.KeyEvent.KEYCODE_1 to 2, android.view.KeyEvent.KEYCODE_2 to 3,
    android.view.KeyEvent.KEYCODE_3 to 4, android.view.KeyEvent.KEYCODE_4 to 5, android.view.KeyEvent.KEYCODE_5 to 6,
    android.view.KeyEvent.KEYCODE_6 to 7, android.view.KeyEvent.KEYCODE_7 to 8, android.view.KeyEvent.KEYCODE_8 to 9,
    android.view.KeyEvent.KEYCODE_9 to 10, android.view.KeyEvent.KEYCODE_0 to 11, android.view.KeyEvent.KEYCODE_MINUS to 12,
    android.view.KeyEvent.KEYCODE_EQUALS to 13, android.view.KeyEvent.KEYCODE_DEL to 14, android.view.KeyEvent.KEYCODE_TAB to 15,
    android.view.KeyEvent.KEYCODE_Q to 16, android.view.KeyEvent.KEYCODE_W to 17, android.view.KeyEvent.KEYCODE_E to 18,
    android.view.KeyEvent.KEYCODE_R to 19, android.view.KeyEvent.KEYCODE_T to 20, android.view.KeyEvent.KEYCODE_Y to 21,
    android.view.KeyEvent.KEYCODE_U to 22, android.view.KeyEvent.KEYCODE_I to 23, android.view.KeyEvent.KEYCODE_O to 24,
    android.view.KeyEvent.KEYCODE_P to 25, android.view.KeyEvent.KEYCODE_LEFT_BRACKET to 26, android.view.KeyEvent.KEYCODE_RIGHT_BRACKET to 27,
    android.view.KeyEvent.KEYCODE_ENTER to 28, android.view.KeyEvent.KEYCODE_CTRL_LEFT to 29, android.view.KeyEvent.KEYCODE_A to 30,
    android.view.KeyEvent.KEYCODE_S to 31, android.view.KeyEvent.KEYCODE_D to 32, android.view.KeyEvent.KEYCODE_F to 33,
    android.view.KeyEvent.KEYCODE_G to 34, android.view.KeyEvent.KEYCODE_H to 35, android.view.KeyEvent.KEYCODE_J to 36,
    android.view.KeyEvent.KEYCODE_K to 37, android.view.KeyEvent.KEYCODE_L to 38, android.view.KeyEvent.KEYCODE_SEMICOLON to 39,
    android.view.KeyEvent.KEYCODE_APOSTROPHE to 40, android.view.KeyEvent.KEYCODE_SHIFT_LEFT to 42,
    android.view.KeyEvent.KEYCODE_BACKSLASH to 43, android.view.KeyEvent.KEYCODE_Z to 44, android.view.KeyEvent.KEYCODE_X to 45,
    android.view.KeyEvent.KEYCODE_C to 46, android.view.KeyEvent.KEYCODE_V to 47, android.view.KeyEvent.KEYCODE_B to 48,
    android.view.KeyEvent.KEYCODE_N to 49, android.view.KeyEvent.KEYCODE_M to 50, android.view.KeyEvent.KEYCODE_COMMA to 51,
    android.view.KeyEvent.KEYCODE_PERIOD to 52, android.view.KeyEvent.KEYCODE_SLASH to 53, android.view.KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
    android.view.KeyEvent.KEYCODE_ALT_LEFT to 56, android.view.KeyEvent.KEYCODE_SPACE to 57, android.view.KeyEvent.KEYCODE_CAPS_LOCK to 58,
    android.view.KeyEvent.KEYCODE_ALT_RIGHT to 100, android.view.KeyEvent.KEYCODE_CTRL_RIGHT to 97,
    android.view.KeyEvent.KEYCODE_META_LEFT to 125, android.view.KeyEvent.KEYCODE_META_RIGHT to 126,
    android.view.KeyEvent.KEYCODE_DPAD_UP to 103, android.view.KeyEvent.KEYCODE_DPAD_DOWN to 108,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT to 105, android.view.KeyEvent.KEYCODE_DPAD_RIGHT to 106,
    android.view.KeyEvent.KEYCODE_MOVE_HOME to 102, android.view.KeyEvent.KEYCODE_MOVE_END to 107,
    android.view.KeyEvent.KEYCODE_PAGE_UP to 104, android.view.KeyEvent.KEYCODE_PAGE_DOWN to 109,
    android.view.KeyEvent.KEYCODE_INSERT to 110, android.view.KeyEvent.KEYCODE_FORWARD_DEL to 111,
    android.view.KeyEvent.KEYCODE_NUM_LOCK to 69, android.view.KeyEvent.KEYCODE_SYSRQ to 99,
    android.view.KeyEvent.KEYCODE_SCROLL_LOCK to 70, android.view.KeyEvent.KEYCODE_BREAK to 119,
)

fun pcCodeOf(androidKeyCode: Int): Int? = PC_KEY_FROM_ANDROID[androidKeyCode]

fun pcKeyLabel(context: Context, code: Int): String {
    val key = PC_KEYS[code] ?: return code.toString()
    val text = if (key.labelRes != 0) context.getString(key.labelRes) else key.label
    return if (text.length == 1 && text[0].isLetter()) text.uppercase() else text
}
