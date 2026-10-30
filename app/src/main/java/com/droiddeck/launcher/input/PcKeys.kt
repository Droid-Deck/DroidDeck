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

val PC_KEYS: Map<Int, PcKey> = PC_KEY_ROWS.flatten().associateBy { it.code }

fun pcKeyLabel(context: Context, code: Int): String {
    val key = PC_KEYS[code] ?: return code.toString()
    val text = if (key.labelRes != 0) context.getString(key.labelRes) else key.label
    return if (text.length == 1 && text[0].isLetter()) text.uppercase() else text
}
