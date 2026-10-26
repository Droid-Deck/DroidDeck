package com.droiddeck.launcher

import android.app.Activity
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentLinkedQueue
import java.io.File
import com.droiddeck.launcher.artifact.RepositorySources
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.gpu.TpUbwcHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DriverMenusAutoTest {
    @Before fun reset() {
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "resolved", false)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "cached", null)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "activeMode", null)
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(RuntimeEnvironment.getApplication().filesDir, "repository-sources").deleteRecursively()
        ShadowBuild.setModel("Quest 3")
        ShadowBuild.setDevice("eureka")
    }

    @Test fun selectingAutoUsesTheQuestPairWithoutStartingADownload() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.setMode(true)
        assertEquals(QuestExperimental.DRIVER, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
        assertEquals(activity.getString(R.string.drivers_using_quest), menus.autoStatus)
        assertFalse(menus.releaseChecking)
        assertNull(menus.pairBusy)
        menus.ensureAuto(force = true)
        assertFalse(menus.releaseChecking)
    }

    @Test fun openingManualDriverSettingsRetainsTheSelectedPair() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        DriverMenus(activity, Handler(Looper.getMainLooper())).ensureAuto(force = true)
        assertEquals("chosen-display", SessionPrefs.androidDriver(activity))
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
    }

    @Test fun savingDriverSourcesChecksRemoteMetadataWithoutChangingTheSelection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        val checked = CountDownLatch(1)
        menus.releaseRefresher = {
            checked.countDown()
            TurnipReleases.Check(emptyList(), emptyList(), emptyList(), 123L)
        }

        menus.sourcesChanged()
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (menus.releaseChecking && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals("chosen-display", SessionPrefs.androidDriver(activity))
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
        assertFalse(menus.releaseChecking)
        assertNull(menus.pairBusy)
    }

    @Test fun sameFilenameFromTwoSourcesResolvesBySourceQualifiedIdentity() {
        val first = TurnipReleases.Asset(
            "first", "v1", "driver.zip", "https://github.com/first/repo/releases/download/v1/driver.zip", 1, false, "first",
            "a".repeat(64), sourceId = "source-a", assetId = 11,
        )
        val second = TurnipReleases.Asset(
            "second", "v1", "driver.zip", "https://github.com/second/repo/releases/download/v1/driver.zip", 1, false, "second",
            "b".repeat(64), sourceId = "source-b", assetId = 22,
        )
        assertEquals(second, releaseAssetById(listOf(first, second), second.id))
    }

    @Test fun sourceChangeAfterAdoptionRollsBackOnlyTheFreshDriverWithoutReplacingItsPriorRecord() {
        val app: Context = RuntimeEnvironment.getApplication()
        val builtIns = TurnipReleases.sourceBuiltIns()
        val source = RepositorySources.visible(app, TurnipReleases.SOURCE_SCOPE, builtIns).first()
        val generation = RepositorySources.generation(app, TurnipReleases.SOURCE_SCOPE, builtIns)
        val installed = mutableSetOf("existing-driver", "fresh-driver")
        var recorded = "existing-driver"
        RepositorySources.save(app, TurnipReleases.SOURCE_SCOPE, builtIns,
            RepositorySources.list(app, TurnipReleases.SOURCE_SCOPE, builtIns).map {
                if (it.id == source.id) it.copy(hidden = true) else it
            })

        assertTrue(runCatching {
            finishRemoteDriverInstall(
                app, source.id, generation,
                rollback = { installed.remove("fresh-driver") },
                record = { recorded = "fresh-driver" },
            )
        }.isFailure)
        assertEquals(setOf("existing-driver"), installed)
        assertEquals("existing-driver", recorded)
    }

    @Test fun recordingRemoteDriverDownloadExcludesConcurrentSourceChanges() {
        val app: Context = RuntimeEnvironment.getApplication()
        val builtIns = TurnipReleases.sourceBuiltIns()
        val source = RepositorySources.visible(app, TurnipReleases.SOURCE_SCOPE, builtIns).first()
        val generation = RepositorySources.generation(app, TurnipReleases.SOURCE_SCOPE, builtIns)
        val enteredRecord = CountDownLatch(1)
        val releaseRecord = CountDownLatch(1)
        val sourceSaveStarted = CountDownLatch(1)
        val sourceSaved = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        var recorded = false

        val installer = thread {
            runCatching {
                finishRemoteDriverInstall(app, source.id, generation, rollback = { error("unexpected rollback") }) {
                    enteredRecord.countDown()
                    check(releaseRecord.await(2, TimeUnit.SECONDS))
                    recorded = true
                }
            }.onFailure { errors.add(it) }
        }
        assertTrue(enteredRecord.await(2, TimeUnit.SECONDS))
        val policy = thread {
            sourceSaveStarted.countDown()
            runCatching {
                RepositorySources.save(app, TurnipReleases.SOURCE_SCOPE, builtIns,
                    RepositorySources.list(app, TurnipReleases.SOURCE_SCOPE, builtIns).map {
                        if (it.id == source.id) it.copy(hidden = true) else it
                    })
            }.onFailure { errors.add(it) }
            sourceSaved.countDown()
        }
        assertTrue(sourceSaveStarted.await(2, TimeUnit.SECONDS))
        assertFalse(sourceSaved.await(150, TimeUnit.MILLISECONDS))
        releaseRecord.countDown()
        installer.join(2_000)
        policy.join(2_000)

        assertFalse(installer.isAlive)
        assertFalse(policy.isAlive)
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(recorded)
        assertTrue(RepositorySources.visible(app, TurnipReleases.SOURCE_SCOPE, builtIns).none { it.id == source.id })
    }

    @Test fun driverMenusCanBeConstructedBeforeActivityContextIsAttached() {
        val menus = DriverMenus(Activity(), Handler(Looper.getMainLooper()))
        assertEquals(SessionPrefs.TP_UBWC_AUTO, menus.tpUbwcMode)
    }

    @Test fun refreshingDriversLoadsTheSavedUbwcModeAfterContextAttachment() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertEquals(SessionPrefs.TP_UBWC_ON, menus.tpUbwcMode)
        assertFalse(menus.state().tpUbwcRestartRequired)
    }

    @Test fun changingUbwcBeforeTheFirstSessionDoesNotRequireRestart() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        menus.refreshDrivers()
        assertFalse(menus.state().tpUbwcRestartRequired)
    }

    @Test fun recreatingTheMenuKeepsTheActiveHintRestartWarning() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        TpUbwcHint.forThisDevice(activity)
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertTrue(menus.state().tpUbwcRestartRequired)
    }
}
