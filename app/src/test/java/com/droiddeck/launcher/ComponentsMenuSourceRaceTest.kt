package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.ComponentsManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        componentsCatalogReader = { _, force, components ->
            if (!force) {
                assertEquals(ComponentsManager.COMPONENTS.toSet(), components)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                staleDone.countDown()
                ComponentsManager.Catalog(listOf(stale), 1)
            } else {
                assertEquals(setOf("dxvk"), components)
                refreshedDone.countDown()
                ComponentsManager.Catalog(emptyList(), 2)
            }
        }

        menu.refreshComponents()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        menu.sourcesChanged("dxvk")
        assertTrue(refreshedDone.await(5, TimeUnit.SECONDS))
        waitForWorker("components-catalog")
        release.countDown()
        assertTrue(staleDone.await(5, TimeUnit.SECONDS))
        waitForWorker("components")
        assertTrue(menu.compCatalog.isEmpty())
    }

    @Test
    fun manualRefreshShowsRateLimitPopupWhenCachedCatalogIsReturned() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        componentsCatalogReader = { _, _, components ->
            assertEquals(ComponentsManager.COMPONENTS.toSet(), components)
            ComponentsManager.Catalog(emptyList(), 123, listOf("source"), rateLimited = true)
        }
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

    @Test
    fun sharedRefreshShowsIdenticalFailureOnlyOnceWithoutHidingDistinctErrors() {
        val shown = ArrayList<String>()
        val feedback = SharedRefreshFeedback(shown::add)

        feedback.complete("rate limited")
        feedback.complete("rate limited")

        assertEquals(listOf("rate limited"), shown)

        val distinct = ArrayList<String>()
        val distinctFeedback = SharedRefreshFeedback(distinct::add)
        distinctFeedback.complete("component failure")
        distinctFeedback.complete("proton failure")
        assertEquals(listOf("component failure", "proton failure"), distinct)

        val partial = ArrayList<String>()
        val partialFeedback = SharedRefreshFeedback(partial::add)
        partialFeedback.complete(null)
        partialFeedback.complete("proton failure")
        assertEquals(listOf("proton failure"), partial)
    }

    @Test
    fun sourceSaveRefreshesOnlyThatComponentAndKeepsOtherCachedRows() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val fex = item("fex", "fex.wcp")
        val oldDxvk = item("dxvk", "old-dxvk.wcp")
        val newDxvk = item("dxvk", "new-dxvk.wcp")
        menu.compCatalog = listOf(fex, oldDxvk)
        val refreshed = CountDownLatch(1)
        componentsCatalogReader = { _, force, components ->
            assertTrue(force)
            assertEquals(setOf("dxvk"), components)
            refreshed.countDown()
            ComponentsManager.Catalog(listOf(newDxvk), 456)
        }

        menu.sourcesChanged("dxvk")
        assertTrue(refreshed.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compChecking && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(listOf(fex, newDxvk).map { it.id }.toSet(), menu.compCatalog.map { it.id }.toSet())
        assertEquals(456L, menu.compCatalogAt)
    }

    @Test
    fun scopedRefreshSupersedesOnlyItsPartOfAnOverlappingCheckAll() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val allEntered = CountDownLatch(1)
        val releaseAll = CountDownLatch(1)
        val scopedDone = CountDownLatch(1)
        val staleDxvk = item("dxvk", "stale-dxvk.wcp")
        val freshDxvk = item("dxvk", "fresh-dxvk.wcp")
        val fex = item("fex", "fex.wcp")
        componentsCatalogReader = { _, _, components ->
            if (components.size > 1) {
                allEntered.countDown()
                check(releaseAll.await(5, TimeUnit.SECONDS))
                ComponentsManager.Catalog(
                    listOf(staleDxvk, fex), 1, listOf("dxvk:stale-repository"),
                    rateLimited = true, rateLimitedComponents = setOf("dxvk"),
                )
            } else {
                assertEquals(setOf("dxvk"), components)
                scopedDone.countDown()
                ComponentsManager.Catalog(listOf(freshDxvk), 2)
            }
        }

        menu.refreshComponentCatalog()
        assertTrue(allEntered.await(5, TimeUnit.SECONDS))
        menu.sourcesChanged("dxvk")
        assertTrue(scopedDone.await(5, TimeUnit.SECONDS))
        val scopedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compCatalog.none { it.id == freshDxvk.id } && System.nanoTime() < scopedDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(menu.compChecking)
        releaseAll.countDown()
        val allDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compChecking && System.nanoTime() < allDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertFalse(menu.compChecking)
        assertEquals(setOf(freshDxvk.id, fex.id), menu.compCatalog.map { it.id }.toSet())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun delayedCacheReadCannotReplaceACompletedForcedRefresh() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val forcedEntered = CountDownLatch(1)
        val releaseForced = CountDownLatch(1)
        val cacheEntered = CountDownLatch(1)
        val releaseCache = CountDownLatch(1)
        val cacheDone = CountDownLatch(1)
        val stale = item("dxvk", "stale-cache.wcp")
        val fresh = item("dxvk", "fresh-forced.wcp")
        componentsCatalogReader = { _, force, _ ->
            if (force) {
                forcedEntered.countDown()
                check(releaseForced.await(5, TimeUnit.SECONDS))
                ComponentsManager.Catalog(listOf(fresh), 20)
            } else {
                cacheEntered.countDown()
                check(releaseCache.await(5, TimeUnit.SECONDS))
                cacheDone.countDown()
                ComponentsManager.Catalog(listOf(stale), 10)
            }
        }

        menu.refreshComponentCatalog(components = setOf("dxvk"))
        assertTrue(forcedEntered.await(5, TimeUnit.SECONDS))
        menu.refreshComponents()
        assertTrue(cacheEntered.await(5, TimeUnit.SECONDS))
        releaseForced.countDown()
        val forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compCatalog.none { it.id == fresh.id } && System.nanoTime() < forcedDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        releaseCache.countDown()
        assertTrue(cacheDone.await(5, TimeUnit.SECONDS))
        waitForWorker("components")

        assertEquals(listOf(fresh.id), menu.compCatalog.map { it.id })
        assertEquals(20L, menu.compCatalogAt)
    }

    @Test
    fun preRequestCacheReadCannotRestoreRowsAfterANewerForcedFailure() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val cacheEntered = CountDownLatch(1)
        val releaseCache = CountDownLatch(1)
        val cacheDone = CountDownLatch(1)
        val forcedDone = CountDownLatch(1)
        val stale = item("dxvk", "stale-before-failure.wcp")
        componentsCatalogReader = { _, force, _ ->
            if (force) {
                forcedDone.countDown()
                throw java.io.IOException("forced refresh failed")
            }
            cacheEntered.countDown()
            check(releaseCache.await(5, TimeUnit.SECONDS))
            cacheDone.countDown()
            ComponentsManager.Catalog(listOf(stale), 10)
        }

        menu.refreshComponents()
        assertTrue(cacheEntered.await(5, TimeUnit.SECONDS))
        menu.sourcesChanged("dxvk")
        assertTrue(forcedDone.await(5, TimeUnit.SECONDS))
        val forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compChecking && System.nanoTime() < forcedDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertFalse(menu.compChecking)
        assertEquals(activity.getString(R.string.comp_nightlies_unreachable), ShadowToast.getTextOfLatestToast())
        releaseCache.countDown()
        assertTrue(cacheDone.await(5, TimeUnit.SECONDS))
        waitForWorker("components")

        assertTrue(menu.compCatalog.isEmpty())
        assertEquals(10L, menu.compCatalogAt)
        assertEquals(activity.getString(R.string.comp_nightlies_unreachable), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun cacheReadDuringForcedRefreshCanLandFirstButForcedSuccessWins() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val forcedEntered = CountDownLatch(1)
        val releaseForced = CountDownLatch(1)
        val stale = item("dxvk", "temporary-cache.wcp")
        val fresh = item("dxvk", "forced-winner.wcp")
        componentsCatalogReader = { _, force, _ ->
            if (force) {
                forcedEntered.countDown()
                check(releaseForced.await(5, TimeUnit.SECONDS))
                ComponentsManager.Catalog(listOf(fresh), 20)
            } else ComponentsManager.Catalog(listOf(stale), 10)
        }

        menu.refreshComponentCatalog(components = setOf("dxvk"))
        assertTrue(forcedEntered.await(5, TimeUnit.SECONDS))
        menu.refreshComponents()
        val cacheDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compCatalog.none { it.id == stale.id } && System.nanoTime() < cacheDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(listOf(stale.id), menu.compCatalog.map { it.id })
        assertTrue(menu.compChecking)
        releaseForced.countDown()
        val forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compChecking && System.nanoTime() < forcedDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(listOf(fresh.id), menu.compCatalog.map { it.id })
        assertEquals(20L, menu.compCatalogAt)
    }

    @Test
    fun laterCheckAllSupersedesAnEarlierScopedRefresh() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = ComponentsMenu(activity, Handler(Looper.getMainLooper()))
        val scopedEntered = CountDownLatch(1)
        val releaseScoped = CountDownLatch(1)
        val scoped = item("dxvk", "old-scoped.wcp")
        val allDxvk = item("dxvk", "new-check-all.wcp")
        val fex = item("fex", "check-all-fex.wcp")
        componentsCatalogReader = { _, _, components ->
            if (components.size == 1) {
                scopedEntered.countDown()
                check(releaseScoped.await(5, TimeUnit.SECONDS))
                ComponentsManager.Catalog(listOf(scoped), 10)
            } else ComponentsManager.Catalog(listOf(allDxvk, fex), 20)
        }

        menu.refreshComponentCatalog(components = setOf("dxvk"))
        assertTrue(scopedEntered.await(5, TimeUnit.SECONDS))
        menu.refreshComponentCatalog()
        val allDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compCatalog.none { it.id == allDxvk.id } && System.nanoTime() < allDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(menu.compChecking)
        releaseScoped.countDown()
        val doneDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.compChecking && System.nanoTime() < doneDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertEquals(setOf(allDxvk.id, fex.id), menu.compCatalog.map { it.id }.toSet())
        assertEquals(20L, menu.compCatalogAt)
    }

    private fun item(comp: String, file: String) = ComponentsManager.CatalogItem(
        file, comp, "${comp.replaceFirstChar { it.uppercase() }}-Linux",
        "https://github.com/the412banner/nightlies/releases/download/test/$file",
        1, "sha256:${comp.first().code.toString(16).repeat(64).take(64)}",
    )

    private fun waitForWorker(name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive } && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertFalse(Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive })
        shadowOf(Looper.getMainLooper()).idle()
    }
}
