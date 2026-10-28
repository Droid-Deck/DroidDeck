package com.droiddeck.launcher.session

object SteamSessionUrl {
    private val allowed = Regex("steam://(?:open/downloads|uninstall/[1-9][0-9]{0,9})")

    fun isSupported(url: String): Boolean = allowed.matches(url)

    internal fun wasOpened(ok: Boolean, launched: Boolean): Boolean = ok && launched
}
