package com.droiddeck.launcher.ui

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoreTabsStateTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
        StoreTabsState.load(ctx)
    }

    @Test fun aReorderIsSavedForTheStoresTabAndTheLibrary() {
        val ids = StoreTabsState.tabs.map { it.store.id }
        val moved = listOf(ids[1], ids[0]) + ids.drop(2)
        StoreTabsState.reorder(ctx, moved)
        assertEquals(moved, StoreTabsState.tabs.map { it.store.id })
        StoreTabsState.load(ctx)
        assertEquals(moved, StoreTabsState.tabs.map { it.store.id })
    }

    @Test fun hidingIsSavedButTheLastShownStoreStays() {
        assertTrue(StoreTabsState.setShown(ctx, Store.GOG, false))
        assertTrue(Store.GOG.id in SessionPrefs.storesTabHidden(ctx))
        for (tab in StoreTabsState.tabs.filter { it.shown }.dropLast(1)) StoreTabsState.setShown(ctx, tab.store, false)
        val last = StoreTabsState.tabs.single { it.shown }.store
        assertFalse(StoreTabsState.setShown(ctx, last, false))
        assertTrue(StoreTabsState.tabs.single { it.store == last }.shown)
    }
}
