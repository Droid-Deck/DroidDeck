package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LinuxRuntimeLiveFilesTest {
    private val root = Files.createTempDirectory("guest-live-files").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
    }

    @Before fun setUp() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    @After fun tearDown() {
        Thread.interrupted()
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        root.deleteRecursively()
    }

    @Test fun cacheEvictionPreservesGuestSharedMemoryAndRuntimeBindings() {
        val binds = LinuxRuntime.binds(context, null, null, null, null)
        val shm = File(binds.single { it.endsWith(":/dev/shm") }.removeSuffix(":/dev/shm"))
        val live = LinuxRuntime.liveDir(context)
        val files = listOf(File(shm, "steamvr-ipc"), File(LinuxRuntime.prootTempDir(context), "loader"),
            File(live, "drm/device"), File(live, "bwrap/1/state"))
        files.forEach { it.parentFile!!.mkdirs(); it.writeText("active") }
        val disposable = File(context.cacheDir, "download").apply { writeText("cached") }

        context.cacheDir.deleteRecursively()

        assertFalse(disposable.exists())
        files.forEach { assertEquals("active", it.readText()) }
        assertTrue(live.path in binds)
        assertEquals(binds, LinuxRuntime.binds(context, null, null, null, null))
        assertTrue(live.toPath().startsWith(context.noBackupFilesDir.toPath()))
    }

    @Test fun successfulRemovalClearsGuestScratchAndPreservesOtherNoBackupData() {
        val runtime = runtime()
        val scratch = scratch()
        val unrelated = File(context.noBackupFilesDir, "calibration.json").apply { writeText("preserved") }

        assertTrue(LinuxRuntimeInstaller.beginUninstall(context, runtime)!!.run(null))

        assertFalse(runtime.exists())
        assertFalse(scratch.exists())
        assertFalse(File(context.noBackupFilesDir, "guest-runtime").exists())
        assertEquals("preserved", unrelated.readText())
    }

    @Test fun failedRuntimeRemovalPreservesGuestScratch() {
        val runtime = runtime()
        val scratch = scratch()
        val cannotMove = object : File(runtime.path) {
            override fun renameTo(dest: File) = false
        }

        assertFalse(LinuxRuntimeInstaller.beginUninstall(context, cannotMove)!!.run(null))

        assertTrue(runtime.exists())
        assertEquals("active", scratch.readText())
    }

    @Test fun interruptedRemovalPreservesGuestScratch() {
        val runtime = runtime()
        val scratch = scratch()
        val removal = LinuxRuntimeInstaller.beginUninstall(context, runtime)!!

        Thread.currentThread().interrupt()
        try {
            assertFalse(removal.run(null))
        } finally {
            Thread.interrupted()
        }

        assertTrue(runtime.exists())
        assertEquals("active", scratch.readText())
    }

    @Test fun aSessionStartingBeforeTheWorkerRunsPreservesRuntimeAndScratch() {
        val runtime = runtime()
        val scratch = scratch()
        val removal = LinuxRuntimeInstaller.beginUninstall(context, runtime)!!
        SessionState.phase = SessionPhase.STARTING_COMPOSITOR

        assertFalse(removal.run(null))

        assertTrue(runtime.exists())
        assertEquals("active", scratch.readText())
    }

    @Test fun aSessionActiveBeforeScratchCleanupPreservesScratch() {
        val runtime = runtime()
        val scratch = scratch()
        val removal = LinuxRuntimeInstaller.beginUninstall(context, runtime)!!

        assertFalse(removal.run { _, _ -> SessionState.running = true })

        assertEquals("active", scratch.readText())
    }

    private fun runtime() = LinuxRuntime.rootDir(context).apply {
        mkdirs()
        File(this, "runtime-file").writeText("installed")
    }

    private fun scratch() = File(LinuxRuntime.liveDir(context), "shm/steamvr-ipc").apply {
        parentFile!!.mkdirs()
        writeText("active")
    }
}
