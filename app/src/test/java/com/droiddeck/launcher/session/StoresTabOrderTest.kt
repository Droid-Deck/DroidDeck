package com.droiddeck.launcher.session

import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.arrangeStoreTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoresTabOrderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val defaults = Store.tabbed.map { it.id }
    private fun shown() = arrangeStoreTabs(SessionPrefs.storesTabOrder(app), emptySet()).map { it.store.id }

    @Test
    fun anOldDefaultOrderMovesToTheCurrentOne() {
        SessionPrefs.setStoresTabOrder(app, listOf("gog", "epic", "amazon", "xbox", "itch", "gamejolt", "battlenet", "ubisoft", "ea", "rockstar"))
        SessionPrefs.settleStoresTabOrder(app, defaults)
        assertEquals(defaults, shown())
        assertFalse(SessionPrefs.storesTabOrderCustomized(app))
    }

    @Test
    fun aSavedCurrentDefaultIsNotMistakenForTheUsersOwn() {
        SessionPrefs.setStoresTabOrder(app, defaults)
        repeat(2) { SessionPrefs.settleStoresTabOrder(app, defaults) }
        assertFalse(SessionPrefs.storesTabOrderCustomized(app))
        assertTrue(SessionPrefs.storesTabOrder(app).isEmpty())
    }

    @Test
    fun anArrangedOrderIsKept() {
        val mine = defaults.reversed()
        SessionPrefs.setStoresTabOrder(app, mine)
        SessionPrefs.settleStoresTabOrder(app, defaults)
        assertTrue(SessionPrefs.storesTabOrderCustomized(app))
        assertEquals(mine, shown())
    }

    @Test
    fun hidingAStoreLeavesTheOrderFollowingTheDefault() {
        SessionPrefs.saveStoresTabs(app, defaults, setOf("itch"), arranged = false)
        assertTrue(SessionPrefs.storesTabOrder(app).isEmpty())
        val later = defaults.reversed()
        SessionPrefs.settleStoresTabOrder(app, later)
        assertFalse(SessionPrefs.storesTabOrderCustomized(app))
        assertTrue(SessionPrefs.storesTabOrder(app).isEmpty())
        assertEquals(setOf("itch"), SessionPrefs.storesTabHidden(app))
    }
}
