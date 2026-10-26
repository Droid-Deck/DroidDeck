package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.ComponentsManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ComponentsMenuSourceRaceTest {
    private val productionReader = componentsCatalogReader

    @Before
    fun setUp() {
        componentsCatalogReader = productionReader
    }

    @After
    fun tearDown() {
        componentsCatalogReader = productionReader
    }

    @Test
    fun preSaveSnapshotCannotRestoreHiddenAvailableRows() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val staleDone = CountDownLatch(1)
        val refreshedDone = CountDownLatch(1)
        val stale = ComponentsManager.CatalogItem(
            "stale.wcp", "dxvk", "Dxvk-Linux",
            "https://github.com/the412banner/nightlies/releases/download/Dxvk-Linux/stale.wcp",
            1, "sha256:${"a".repeat(64)}",
        )
        componentsCatalogReader = { _, force ->
            if (!force) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                staleDone.countDown()
                ComponentsManager.Catalog(listOf(stale), 1)
            } else {
                refreshedDone.countDown()
                ComponentsManager.Catalog(emptyList(), 2)
            }
        }

        menu.refreshComponents()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        menu.sourcesChanged("dxvk")
        assertTrue(refreshedDone.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(staleDone.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(menu.compCatalog.isEmpty())
    }

    @Test
    fun manualRefreshShowsRateLimitPopupWhenCachedCatalogIsReturned() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        componentsCatalogReader = { _, _ -> ComponentsManager.Catalog(emptyList(), 123, listOf("source"), rateLimited = true) }
        menu.refreshComponentCatalog()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compChecking && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(!menu.compChecking)
        assertEquals(activity.getString(R.string.sources_github_rate_limited), ShadowToast.getTextOfLatestToast())
        assertEquals(123L, menu.compCatalogAt)
    }
}
