package com.droiddeck.launcher.stores

enum class Store(val id: String, val label: String, val shortLabel: String) {
    EPIC("epic", "Epic Games", "Epic"),
    EA("ea", "EA", "EA"),
    XBOX("xbox", "Xbox", "Xbox"),
    GOG("gog", "GOG", "GOG"),
    ROCKSTAR("rockstar", "Rockstar Games", "Rockstar"),
    ITCH("itch", "Itch.io", "Itch.io"),
    UBISOFT("ubisoft", "Ubisoft Connect", "Ubisoft"),
    AMAZON("amazon", "Amazon Games", "Amazon"),
    BATTLENET("battlenet", "Battle.net", "Battle.net"),
    GAMEJOLT("gamejolt", "Game Jolt", "Game Jolt"),
    STEAM("steamstore", "Steam", "Steam");

    val folder: String get() = if (this == ITCH) "itch" else shortLabel

    /** The Library tab its games list under: Steam's are the Steam client's own tab. */
    val librarySource: String get() = if (this == STEAM) com.droiddeck.launcher.frontend.Library.SOURCE_STEAM else id

    companion object {
        fun byId(id: String?): Store? = entries.firstOrNull { it.id == id }
            ?: STEAM.takeIf { id == "steam-account" }

        /** Stores with a storefront tab and a single account selector in the Library. */
        val tabbed: List<Store> get() = entries
    }
}
