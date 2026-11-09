package com.droiddeck.launcher.lab

import android.app.Application
import com.droiddeck.launcher.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LabOverlayTest {
    private lateinit var app: Application
    private lateinit var root: File
    private val injected = mutableListOf<File>()
    private val originalInject = LabOverlay.injectDex
    private val originalResources = LabOverlay.openResources

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.applicationInfo.sourceDir = "/data/app/~~test==/com.droiddeck.launcher-test==/base.apk"
        root = LabOverlay.root(app).apply { deleteRecursively(); mkdirs() }
        LabOverlay.injectDex = { _, apk -> injected += apk }
        LabOverlay.openResources = { null }
    }

    @After fun restore() {
        LabOverlay.injectDex = originalInject
        LabOverlay.openResources = originalResources
        root.deleteRecursively()
    }

    private fun stage(dir: String, commit: String = BuildConfig.SOURCE_COMMIT, apk: String = app.applicationInfo.sourceDir) =
        File(root, dir).apply {
            mkdirs()
            File(this, "overlay.apk").writeText("dex")
            File(this, "overlay.json").writeText("""{"label":"feat/x @ abc1234","baseCommit":"$commit","baseApk":"$apk"}""")
        }

    @Test fun aBuildForThisInstallIsLoaded() {
        stage("active")
        LabOverlay.load(app, mainProcess = true)
        assertEquals("feat/x @ abc1234", LabOverlay.active?.label)
        assertEquals(listOf(File(root, "active/overlay.apk")), injected)
        assertEquals(1, LabOverlay.failedStarts(root))
    }

    @Test fun aStagedBuildIsPromotedAtStart() {
        stage("next")
        File(root, "starts").writeText("2")
        LabOverlay.load(app, mainProcess = true)
        assertFalse(File(root, "next").exists())
        assertNotNull(LabOverlay.active)
        assertEquals(1, LabOverlay.failedStarts(root))
    }

    @Test fun aBuildForAnotherInstallIsTurnedOff() {
        stage("active", commit = "0000000")
        LabOverlay.load(app, mainProcess = true)
        assertNull(LabOverlay.active)
        assertTrue(injected.isEmpty())
        assertFalse(File(root, "active").exists())
        assertEquals("built for another install", File(root, "last-off-reason").readText())
    }

    @Test fun aBuildForAnEarlierInstallOfTheSameCommitIsTurnedOff() {
        stage("active", apk = "/data/app/old/base.apk")
        LabOverlay.load(app, mainProcess = true)
        assertNull(LabOverlay.active)
        assertEquals("built for another install", File(root, "last-off-reason").readText())
    }

    @Test fun aBuildThatKeepsFailingToStartIsTurnedOff() {
        stage("active")
        File(root, "starts").writeText("2")
        LabOverlay.load(app, mainProcess = true)
        assertNull(LabOverlay.active)
        assertEquals("did not start", File(root, "last-off-reason").readText())
    }

    @Test fun otherProcessesLoadWithoutCountingStarts() {
        stage("active")
        LabOverlay.load(app, mainProcess = false)
        assertNotNull(LabOverlay.active)
        assertEquals(0, LabOverlay.failedStarts(root))
    }

    @Test fun noBuildMeansNothingIsLoaded() {
        LabOverlay.load(app, mainProcess = true)
        assertNull(LabOverlay.active)
        assertTrue(injected.isEmpty())
    }
}
