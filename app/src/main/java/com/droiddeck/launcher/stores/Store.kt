package com.droiddeck.launcher.stores

/**
 * The three storefronts the Stores section talks to. [id] is what the sidecar, the prefs file
 * names and the Games badge carry; it never changes once a game is installed under it.
 */
enum class Store(val id: String, val label: String, val shortLabel: String) {
    GOG("gog", "GOG", "GOG"),
    EPIC("epic", "Epic Games", "Epic"),
    AMAZON("amazon", "Amazon Games", "Amazon");

    companion object {
        fun byId(id: String?): Store? = entries.firstOrNull { it.id == id }
    }
}
