package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionPrefs

internal enum class SessionDrawerPage(val firstControl: String) {
    CONTROLLER("input-profile"),
    GLASSES("glasses-game"),
    DISPLAY("fg"),
    HUD("hud"),
    EFFECTS("look"),
    VR("steamvr"),
    GAMES("anisotropy"),
    AGENT("agent-input"),
    SESSION("suspend"),
}

internal object DrawerPages {
    var order by mutableStateOf(SessionDrawerPage.entries.toList())
        private set
    var hidden by mutableStateOf(emptySet<SessionDrawerPage>())
        private set

    /** The order captured when a page row was grabbed in Arrange pages; Back restores it. */
    var heldFrom: List<SessionDrawerPage>? = null

    fun hideable(page: SessionDrawerPage) = page != SessionDrawerPage.SESSION

    fun load(context: Context) {
        order = arrange(SessionPrefs.drawerPageOrder(context))
        hidden = SessionPrefs.drawerPageHidden(context).mapNotNull { name -> SessionDrawerPage.entries.firstOrNull { it.name == name } }
            .filter(::hideable).toSet()
    }

    fun arrange(saved: List<String>): List<SessionDrawerPage> {
        val all = SessionDrawerPage.entries
        val out = saved.mapNotNull { name -> all.firstOrNull { it.name == name } }.distinct().toMutableList()
        for (page in all.filterNot { it in out }) {
            val before = all.subList(0, all.indexOf(page)).lastOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, page)
        }
        return out
    }

    fun setHidden(context: Context, page: SessionDrawerPage, hide: Boolean) {
        hidden = if (hide && hideable(page)) hidden + page else hidden - page
        if (DevPreview.drawer == null) SessionPrefs.setDrawerPageHidden(context, hidden.map { it.name }.toSet())
    }

    /** [placed] holds the pages on offer in their new order; pages not on offer keep their slots. */
    fun rearrange(context: Context, placed: List<SessionDrawerPage>) {
        if (placed.isEmpty() || !order.containsAll(placed)) return
        val next = placed.iterator()
        order = order.map { if (it in placed) next.next() else it }
        if (DevPreview.drawer == null) SessionPrefs.setDrawerPageOrder(context, order.map { it.name })
    }

    fun reset(context: Context) {
        order = SessionDrawerPage.entries.toList()
        hidden = emptySet()
        if (DevPreview.drawer != null) return
        SessionPrefs.setDrawerPageOrder(context, order.map { it.name })
        SessionPrefs.setDrawerPageHidden(context, emptySet())
    }

    fun isDefault() = order == SessionDrawerPage.entries.toList() && hidden.isEmpty()
}

internal fun offeredDrawerPages(
    hasGlasses: Boolean = false, hasAgent: Boolean = com.droiddeck.launcher.agent.AgentTab.shown,
): List<SessionDrawerPage> =
    DrawerPages.order.filter {
        (hasGlasses || it != SessionDrawerPage.GLASSES) &&
            (hasAgent || it != SessionDrawerPage.AGENT)
    }

internal fun sessionDrawerPages(
    hasGlasses: Boolean = false, hasAgent: Boolean = com.droiddeck.launcher.agent.AgentTab.shown,
): List<SessionDrawerPage> = offeredDrawerPages(hasGlasses, hasAgent).filter { it !in DrawerPages.hidden }

internal fun List<SessionDrawerPage>.step(current: SessionDrawerPage, direction: Int): SessionDrawerPage {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}
