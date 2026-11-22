package com.droiddeck.launcher.runtime

object CurseForgeApp {
    const val NAME = "CurseForge"
    const val FEED = "https://curseforge.overwolf.com/electron/linux/latest-linux.yml"

    fun find(apps: List<UserApps.App>): UserApps.App? = apps.firstOrNull { it.feed == FEED }

    fun request(): UserApps.Request = UserApps.Request(UserApps.Source.Feed(FEED), NAME, null)
}
