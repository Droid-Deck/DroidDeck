package com.droiddeck.launcher.session

object SteamSessionUrl {
    private val allowed = Regex("steam://(?:open/downloads|uninstall/[1-9][0-9]{0,9})")
    private const val MAX_STEAM_APP_ID = 4_294_967_295L

    fun isSupported(url: String): Boolean {
        if (!allowed.matches(url)) return false
        val appId = url.removePrefix("steam://uninstall/").takeIf { it != url }?.toLongOrNull()
        return appId == null || appId <= MAX_STEAM_APP_ID
    }

    internal fun wasOpened(ok: Boolean, launched: Boolean): Boolean = ok && launched

    internal fun canResume(
        expectedSessionId: String?,
        currentSessionId: String?,
        running: Boolean,
        stopping: Boolean,
        activityDestroyed: Boolean,
    ): Boolean = expectedSessionId != null && expectedSessionId == currentSessionId &&
        running && !stopping && !activityDestroyed
}
