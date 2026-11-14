package com.droiddeck.launcher.session

enum class SessionMenuScreen(val id: String) {
    GAME("game"),
    COMPANION("companion");

    fun onSecondaryDisplay(gameOnSecondary: Boolean, secondScreenAvailable: Boolean): Boolean =
        secondScreenAvailable && (gameOnSecondary == (this == GAME))

    companion object {
        fun fromId(id: String?): SessionMenuScreen = entries.firstOrNull { it.id == id } ?: COMPANION
    }
}
