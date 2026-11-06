package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun switchingSourcesCancelsPendingPermissionAndRejectsThePreviousRelease() {
        respondWith(404)
        val custom = RepositorySources.source("example/droiddeck-ci")
        AppUpdates.saveSources(activity, AppUpdates.sources(activity) + custom)
        menu.sources = AppUpdates.sources(activity)
        menu.actions().onFollow(AppUpdates.Follow(AppUpdates.Channel.STABLE))
        val previous = release("previous")
        menu.catalog = catalog(previous)
        assertFalse(SelfInstaller.canInstall(activity))
        menu.actions().onInstall(previous)
        assertTrue(menu.askPermission)

        menu.actions().onSource(custom.id)
        finishCheck()

        assertFalse(menu.askPermission)
        assertEquals(custom.id, menu.follow.sourceId)
        menu.catalog = catalog(release("current"))
        menu.actions().onInstall(previous)
        assertEquals(activity.getString(R.string.upd_no_download), menu.error)
        assertFalse(menu.askPermission)
        assertNull(menu.stage)
    }

    @Test
    fun savingSourcesCancelsPendingInstallPermission() {
        respondWith(404)
        menu.actions().onFollow(AppUpdates.Follow(AppUpdates.Channel.STABLE))
        val selected = release("selected")
        menu.catalog = catalog(selected)
        assertFalse(SelfInstaller.canInstall(activity))
        menu.actions().onInstall(selected)
        assertTrue(menu.askPermission)

        menu.actions().onSourcesChanged()
        finishCheck()

        assertFalse(menu.askPermission)
        assertNull(menu.stage)
    }

    private fun release(tag: String) = AppUpdates.Release(
        tag, tag, "", "a".repeat(40), 0, "1.0.0", BuildConfig.VERSION_CODE, 0L, "",
        AppUpdates.Apk(
            "$tag.apk", "https://github.com/example/droiddeck-ci/releases/download/$tag/$tag.apk",
            1, "b".repeat(64), activity.packageName, BuildConfig.VERSION_CODE, BuildConfig.RELEASE_SIGNER,
        ),
    )

    private fun catalog(release: AppUpdates.Release) = AppUpdates.Catalog(
        release, null, emptyList(), 0L, sourceId = menu.follow.sourceId,
    )

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
