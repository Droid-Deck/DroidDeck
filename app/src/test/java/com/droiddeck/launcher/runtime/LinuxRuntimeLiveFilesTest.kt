package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @After fun tearDown() {
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
}
