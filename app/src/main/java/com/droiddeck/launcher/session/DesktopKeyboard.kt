package com.droiddeck.launcher.session

object DesktopKeyboard {
    const val PC = "pc"
    const val ANDROID = "android"
    const val NONE = "none"

    val VALUES = listOf(PC, ANDROID, NONE)

    enum class Action { OPEN_PC, OPEN_ANDROID, NOTHING }

    fun normalize(value: String?): String = value?.takeIf { it in VALUES } ?: ANDROID

    fun decide(pref: String, focused: Boolean, tapped: Boolean, hardwareKeyboard: Boolean, pcOpen: Boolean): Action =
        when {
            !focused || !tapped || hardwareKeyboard || pcOpen -> Action.NOTHING
            normalize(pref) == PC -> Action.OPEN_PC
            normalize(pref) == ANDROID -> Action.OPEN_ANDROID
            else -> Action.NOTHING
        }
}
