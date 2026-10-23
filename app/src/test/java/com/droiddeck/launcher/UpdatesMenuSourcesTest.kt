package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdatesMenuSourcesTest {
    private val productionConnection = GitHubArtifacts.openConnection
    private lateinit var activity: Activity
    private lateinit var menu: UpdatesMenu
    private var calls = 0

    @Before
    fun setUp() {
        GitHubArtifacts.clearRateLimits()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppUpdates.applyInstalledSignerIdentity(
            SelfInstaller.VerifiedIdentity(setOf(BuildConfig.RELEASE_SIGNER), emptySet()), true,
        )
        menu = UpdatesMenu(activity, Handler(Looper.getMainLooper()))
        menu.start()
    }

    @After
    fun tearDown() {
        menu.unregister()
        GitHubArtifacts.openConnection = productionConnection
        GitHubArtifacts.clearRateLimits()
        AppUpdates.applyInstalledSignerIdentity(SelfInstaller.VerifiedIdentity(emptySet(), emptySet()), false)
    }

    @Test
    fun backgroundRateLimitShowsActionablePopupAndKeepsCachedCatalog() {
        val source = AppUpdates.sources(activity).single()
        activity.getSharedPreferences("app_updates", 0).edit().putString(
            AppUpdates.catalogCacheKey(source), """{"tests":[],"checkedAt":123}""",
        ).commit()
        menu.catalog = AppUpdates.cached(activity, source.id)
        respondWith(429)

        menu.check(force = false)
        finishCheck()

        val expected = activity.getString(R.string.sources_github_rate_limited)
        assertEquals(expected, menu.error)
        assertEquals(expected, ShadowToast.getTextOfLatestToast())
        assertNotNull(menu.catalog)
        assertEquals(123L, menu.catalog!!.checkedAt)
        assertEquals(1, calls)
    }

    @Test
    fun savingSourcesRestartsSelectedVisibleSourceButDoesNotChooseAnotherSource() {
        respondWith(404)
        val selected = menu.follow.sourceId
        menu.actions().onSourcesChanged()
        finishCheck()
        assertEquals(1, calls)
        assertTrue(menu.error!!.contains(activity.getString(R.string.sources_update_catalog_missing)))

        AppUpdates.saveSources(activity, AppUpdates.sources(activity).map { it.copy(hidden = true) })
        menu.actions().onSourcesChanged()
        assertFalse(menu.checking)
        assertEquals(selected, menu.follow.sourceId)
        assertTrue(menu.sources.isEmpty())
        assertEquals(1, calls)
    }

    private fun respondWith(status: Int) {
        GitHubArtifacts.openConnection = { url ->
            calls++
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = status
            }
        }
    }

    private fun finishCheck() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menu.checking && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertFalse(menu.checking)
    }
}
