package com.droiddeck.launcher.stores

enum class Store(val id: String, val label: String, val shortLabel: String) {
    GOG("gog", "GOG", "GOG"),
    EPIC("epic", "Epic Games", "Epic"),
    AMAZON("amazon", "Amazon Games", "Amazon"),
    ITCH("itch", "itch.io", "itch");

    companion object {
        fun byId(id: String?): Store? = entries.firstOrNull { it.id == id }
    }
}
