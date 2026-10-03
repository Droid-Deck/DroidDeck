package com.droiddeck.launcher.frontend

/** External callers can select a game, never a guest path or arbitrary Steam command. */
object GameLaunchLink {
    private val idPattern = Regex("[1-9][0-9]{0,19}")
    private val linkPattern = Regex("droiddeck://game/([1-9][0-9]{0,19})")

    fun validId(id: String): Boolean = idPattern.matches(id) && id.toULongOrNull() != null
    fun parse(link: String?): String? = link?.let { linkPattern.matchEntire(it)?.groupValues?.get(1) }
        ?.takeIf(::validId)
    fun uri(id: String): String { require(validId(id)); return "droiddeck://game/$id" }
    fun steamUrl(id: String): String { require(validId(id)); return "steam://rungameid/$id" }
}
