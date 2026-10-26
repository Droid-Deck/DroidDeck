package com.droiddeck.launcher.input

import android.content.Context

fun screenKeyLabel(context: Context, codes: List<Int>): String =
    codes.filter { it != 0 }.joinToString("+") { short(context, it) }

private fun short(context: Context, code: Int): String = when (code) {
    28 -> "⏎"
    14 -> "⌫"
    15 -> "Tab"
    57 -> "Space"
    58 -> "Caps"
    42, 54 -> "Shift"
    29, 97 -> "Ctrl"
    56 -> "Alt"
    100 -> "AltGr"
    125 -> "Super"
    else -> pcKeyLabel(context, code)
}

const val SCREEN_KEY_PILL = 0
const val SCREEN_KEY_ROUND = 1
const val SCREEN_KEY_CIRCLE = 2
const val SCREEN_KEY_RECT = 3
const val SCREEN_KEY_TRIGGER = 4
const val SCREEN_KEY_DPAD = 5
const val SCREEN_KEY_STICK = 6

val SCREEN_KEY_DPAD_DIRS = listOf("↑", "→", "↓", "←")
val SCREEN_KEY_STICK_DIRS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

fun screenKeyDirections(shape: Int): List<String> = when (shape) {
    SCREEN_KEY_DPAD -> SCREEN_KEY_DPAD_DIRS
    SCREEN_KEY_STICK -> SCREEN_KEY_STICK_DIRS
    else -> emptyList()
}

val SCREEN_KEY_SHAPES =
    listOf(
        SCREEN_KEY_PILL, SCREEN_KEY_ROUND, SCREEN_KEY_CIRCLE, SCREEN_KEY_RECT,
        SCREEN_KEY_TRIGGER, SCREEN_KEY_DPAD, SCREEN_KEY_STICK,
    )

data class ScreenKey(
    val codes: List<Int>,
    val x: Float,
    val y: Float,
    val size: Int,
    val glyph: String = "",
    val text: String = "",
    val image: String = "",
    val opacity: Float = 1f,
    val shape: Int = SCREEN_KEY_PILL,
    val toggle: Boolean = false,
    val labels: Boolean = true,
) {
    fun shownLabel(context: Context): String = when {
        text.isNotEmpty() -> text
        glyph.isNotEmpty() -> glyph
        else -> screenKeyLabel(context, codes)
    }
}
