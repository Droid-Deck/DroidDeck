package com.droiddeck.launcher.stores

enum class Store(val id: String, val label: String, val shortLabel: String) {
    GOG("gog", "GOG", "GOG"),
    EPIC("epic", "Epic Games", "Epic"),
    AMAZON("amazon", "Amazon Games", "Amazon"),
    XBOX("xbox", "Xbox", "Xbox"),
    ITCH("itch", "Itch.io", "Itch.io"),
    GAMEJOLT("gamejolt", "Game Jolt", "Game Jolt"),
    BATTLENET("battlenet", "Battle.net", "Battle.net");

    val folder: String get() = if (this == ITCH) "itch" else shortLabel

    companion object {
        fun byId(id: String?): Store? = entries.firstOrNull { it.id == id }
    }
}
