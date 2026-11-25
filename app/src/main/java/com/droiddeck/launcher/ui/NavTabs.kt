package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.session.SessionPrefs

internal enum class TabPlace { BAR, MORE, HIDDEN }

internal object DeveloperTools {
    var on by mutableStateOf(false)
        private set

    fun load(context: Context) {
        on = SessionPrefs.developerTools(context)
        DevPreview.shortcuts = SessionPrefs.previewShortcuts(context)
    }

    fun set(context: Context, enabled: Boolean) {
        on = enabled
        SessionPrefs.setDeveloperTools(context, enabled)
    }
}

internal object NavTabs {
    const val MORE = "more"
    val ALL = listOf("steam", "desktop", "games", "downloads", "setup", "updates", "stores", "soundtracks", "components", "storage", "sync", "decky", "boot-movies", "android-apps", "agent")
    val DEFAULT_SHELVED = setOf("soundtracks", "components", "storage", "sync", "decky", "boot-movies", "android-apps", "agent")
    val DEFAULT_HIDDEN = setOf("android-apps", "agent")
    private val SHELVED_WHEN_NEW = setOf("storage", "sync", "decky", "boot-movies", "android-apps", "agent")
    private val OLD_ORDER = listOf("steam", "games", "soundtracks", "stores", "downloads", "desktop", "agent", "components", "android-apps", "storage", "sync", "setup", "updates")
    private val OLD_SHELVED = setOf("soundtracks", "agent", "storage", "sync")
    private const val LAYOUT_VERSION = 2

    var order by mutableStateOf(ALL)
        private set
    var shelved by mutableStateOf(emptySet<String>())
        private set
    var hidden by mutableStateOf(emptySet<String>())
        private set

    fun load(context: Context) {
        migrate(context)
        val saved = SessionPrefs.navTabOrder(context)
        order = arrange(saved)
        shelved = (SessionPrefs.navTabShelved(context) ?: DEFAULT_SHELVED).intersect(ALL.toSet()) +
            if (saved.isEmpty()) emptySet() else SHELVED_WHEN_NEW - saved.toSet()
        hidden = (SessionPrefs.navTabHidden(context) ?: DEFAULT_HIDDEN).intersect(ALL.toSet()) - "setup"
    }

    fun reset(context: Context) {
        order = ALL
        shelved = DEFAULT_SHELVED
        hidden = DEFAULT_HIDDEN
        SessionPrefs.setNavTabOrder(context, order)
        SessionPrefs.setNavTabShelved(context, shelved)
        SessionPrefs.setNavTabHidden(context, hidden)
    }

    fun isDefault(): Boolean = order == ALL && shelved == DEFAULT_SHELVED && hidden == DEFAULT_HIDDEN

    // Before layout 2, switches for game stores, soundtracks and the agent tab, and the home-screen requirement for Apps,
    // decided which tabs existed. A layout someone had already touched keeps showing what it showed then.
    private fun migrate(context: Context) {
        if (SessionPrefs.navTabLayoutVersion(context) >= LAYOUT_VERSION) return
        val legacy = SessionPrefs.legacyTabSwitches(context)
        if (legacy.touched) {
            if (SessionPrefs.navTabOrder(context).isEmpty()) SessionPrefs.setNavTabOrder(context, OLD_ORDER)
            if (SessionPrefs.navTabShelved(context) == null) SessionPrefs.setNavTabShelved(context, OLD_SHELVED)
            val hide = (SessionPrefs.navTabHidden(context) ?: emptySet()).toMutableSet()
            if (!legacy.stores) hide += setOf("stores", "downloads")
            if (!legacy.soundtracks) hide += "soundtracks"
            if (!legacy.agent) hide += "agent"
            if (!com.droiddeck.launcher.HomeApp.isHomeScreenEnabled(context) || !com.droiddeck.launcher.HomeApp.isDefault(context)) hide += "android-apps"
            SessionPrefs.setNavTabHidden(context, hide)
        }
        SessionPrefs.finishTabSwitchMigration(context, LAYOUT_VERSION)
    }

    fun arrange(saved: List<String>): List<String> {
        val listed = saved.filter { it in ALL }.distinct()
        val missing = ALL.filterNot { it in listed }
        val out = listed.toMutableList()
        for (key in missing) {
            val before = ALL.subList(0, ALL.indexOf(key)).lastOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, key)
        }
        return out
    }

    fun available(s: FrontEndState, key: String): Boolean = when (key) {
        "agent" -> AgentHost.available
        else -> true
    }

    fun hideable(key: String) = key != "setup"

    fun place(s: FrontEndState, key: String): TabPlace = when {
        key in hidden -> TabPlace.HIDDEN
        key in shelved -> TabPlace.MORE
        else -> TabPlace.BAR
    }

    fun listed(s: FrontEndState) = order.filter { available(s, it) }

    fun more(s: FrontEndState) = listed(s).filter { place(s, it) == TabPlace.MORE }

    fun bar(s: FrontEndState): List<String> {
        val shown = listed(s).filter { place(s, it) == TabPlace.BAR }
        return if (more(s).isEmpty()) shown else shown + MORE
    }

    fun setHidden(context: Context, key: String, hide: Boolean) {
        hidden = if (hide && hideable(key)) hidden + key else hidden - key
        SessionPrefs.setNavTabHidden(context, hidden)
    }

    fun rearrange(context: Context, s: FrontEndState, bar: List<String>, more: List<String>) {
        val placed = bar + more
        if (placed.toSet() != listed(s).toSet()) return
        val next = placed.iterator()
        order = order.map { if (it in placed) next.next() else it }
        shelved = shelved - placed.toSet() + more
        SessionPrefs.setNavTabOrder(context, order)
        SessionPrefs.setNavTabShelved(context, shelved)
    }
}
