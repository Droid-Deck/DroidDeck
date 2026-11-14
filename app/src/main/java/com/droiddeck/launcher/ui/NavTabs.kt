package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentTab
import com.droiddeck.launcher.session.SessionPrefs

internal enum class TabPlace { BAR, MORE, HIDDEN }

internal object NavTabs {
    const val MORE = "more"
    val FEATURES = setOf("stores", "soundtracks", "agent")
    val ALL = listOf("steam", "games", "soundtracks", "stores", "desktop", "agent", "components", "android-apps", "setup", "updates")

    var order by mutableStateOf(ALL)
        private set
    var shelved by mutableStateOf(emptySet<String>())
        private set
    var hidden by mutableStateOf(emptySet<String>())
        private set

    fun load(context: Context) {
        order = arrange(SessionPrefs.navTabOrder(context))
        shelved = SessionPrefs.navTabShelved(context).intersect(ALL.toSet())
        hidden = SessionPrefs.navTabHidden(context).intersect(ALL.toSet()) - FEATURES - "setup"
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
        "android-apps" -> s.isHomeApp
        "agent" -> AgentHost.available
        else -> true
    }

    fun hideable(key: String) = key != "setup"

    fun place(s: FrontEndState, key: String): TabPlace = when {
        featureOff(s, key) || key in hidden -> TabPlace.HIDDEN
        key in shelved -> TabPlace.MORE
        else -> TabPlace.BAR
    }

    private fun featureOff(s: FrontEndState, key: String) = when (key) {
        "stores" -> !s.gameStoresEnabled
        "soundtracks" -> !s.soundtracksEnabled
        "agent" -> !AgentTab.shown
        else -> false
    }

    fun listed(s: FrontEndState) = order.filter { available(s, it) }

    fun more(s: FrontEndState) = listed(s).filter { place(s, it) == TabPlace.MORE }

    fun bar(s: FrontEndState): List<String> {
        val shown = listed(s).filter { place(s, it) == TabPlace.BAR }
        return if (more(s).isEmpty()) shown else shown + MORE
    }

    fun move(context: Context, s: FrontEndState, key: String, step: Int) {
        val visible = listed(s)
        val at = visible.indexOf(key)
        val other = visible.getOrNull(at + step) ?: return
        val next = order.toMutableList()
        val i = next.indexOf(key)
        val j = next.indexOf(other)
        next[i] = other
        next[j] = key
        order = next
        SessionPrefs.setNavTabOrder(context, next)
    }

    fun setPlace(context: Context, key: String, place: TabPlace) {
        shelved = if (place == TabPlace.MORE) shelved + key else shelved - key
        hidden = if (place == TabPlace.HIDDEN && hideable(key) && key !in FEATURES) hidden + key else hidden - key
        SessionPrefs.setNavTabShelved(context, shelved)
        SessionPrefs.setNavTabHidden(context, hidden)
    }
}
