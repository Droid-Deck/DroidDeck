package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.stores.Store
import java.io.File
import java.util.Locale

class GameRequest(
    val id: String? = null,
    val source: String = Library.SOURCE_STEAM,
    val title: String? = null,
    val program: File? = null,
) {
    fun find(games: List<Library.SteamGame>): Library.SteamGame? {
        program?.let { return owner(games, it) }
        val exact = id?.let { id -> games.firstOrNull { it.gameIdString == id } }
        if (source == Library.SOURCE_STEAM) return exact ?: games.firstOrNull { it.appId > 0 && it.appId.toString() == id }
        exact?.takeIf { it.source == source }?.let { return it }
        id?.let { id -> games.firstOrNull { it.source == source && it.storeId == id } }?.let { return it }
        val key = title?.let(::titleKey)?.takeIf { it.isNotEmpty() } ?: return null
        return games.firstOrNull { it.source == source && titleKey(it.name) == key }
    }

    private fun owner(games: List<Library.SteamGame>, program: File): Library.SteamGame? {
        val path = program.path
        return games.mapNotNull { game ->
            val folder = game.gameFiles ?: return@mapNotNull null
            val dir = folder.absolutePath.takeIf { inside(path, it) }
                ?: runCatching { folder.canonicalPath }.getOrNull()?.takeIf { inside(path, it) }
            dir?.let { game to it.length }
        }.maxByOrNull { it.second }?.first
    }

    companion object {
        val PROGRAMS = setOf("exe", "bat")
        private const val CUSTOM_GAME = "CUSTOM_GAME"
        private const val CUSTOM_EXTENSION = "pcgame"
        private const val DAIJISHOU = "# Daijishou Player Template"
        private val daijishouId = Regex("""(?i)\[steamappid]\s*([0-9]+)""")
        private val steamLink = Regex("steam://rungameid/([1-9][0-9]{0,19})")

        val extensions: Set<String> = Store.entries.map { it.id }.toSet() + CUSTOM_EXTENSION

        fun extension(game: Library.SteamGame): String = Store.byId(game.source)?.id ?: GameFiles.EXTENSION

        fun sourceNamed(value: String): String? = when {
            value.equals(Library.SOURCE_STEAM, ignoreCase = true) -> Library.SOURCE_STEAM
            value.equals(CUSTOM_GAME, ignoreCase = true) -> Library.ADDED
            else -> Store.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.id
        }

        fun sourceOf(extension: String): String = when (val ext = extension.lowercase(Locale.ROOT)) {
            CUSTOM_EXTENSION -> Library.ADDED
            else -> Store.byId(ext)?.id ?: Library.SOURCE_STEAM
        }

        fun link(text: String): String? = text.takeIf(GameLaunchLink::validId)
            ?: GameLaunchLink.parse(text)
            ?: steamLink.matchEntire(text)?.groupValues?.get(1)?.takeIf(GameLaunchLink::validId)

        fun parse(text: String): String? {
            link(text.trim())?.let { return it }
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val header = lines.firstOrNull() ?: return null
            return when {
                header.equals(DAIJISHOU, ignoreCase = true) -> lines.firstNotNullOfOrNull { daijishouId.matchEntire(it) }
                    ?.groupValues?.get(1)?.takeIf(GameLaunchLink::validId)
                header.equals("[InternetShortcut]", ignoreCase = true) -> lines.firstOrNull { it.startsWith("URL=", ignoreCase = true) }
                    ?.substring(4)?.trim()?.takeIf { !GameLaunchLink.validId(it) }?.let(::link)
                else -> null
            }
        }

        internal fun titleKey(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        private fun inside(path: String, dir: String) = path.startsWith(dir.trimEnd(File.separatorChar) + File.separator)
    }
}
