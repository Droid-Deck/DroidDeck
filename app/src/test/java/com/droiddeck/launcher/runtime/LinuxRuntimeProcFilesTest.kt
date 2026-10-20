package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LinuxRuntimeProcFilesTest {
    private val root = Files.createTempDirectory("guest-proc-files").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
    }
    private val fakeProc get() = File(LinuxRuntime.rootDir(context), "etc/droiddeck/proc")

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun theGuestGetsAnOvercommitPolicyItCanRead() {
        LinuxRuntime.binds(context, null, null, null, null)
        assertEquals("0\n", File(fakeProc, "overcommit_memory").readText())
        assertEquals("9223372036854775807\n", File(fakeProc, "file-max").readText())
    }

    @Test fun aSeededFileIsKept() {
        File(fakeProc, "overcommit_memory").apply { parentFile!!.mkdirs(); writeText("1\n") }
        LinuxRuntime.binds(context, null, null, null, null)
        assertEquals("1\n", File(fakeProc, "overcommit_memory").readText())
    }
}
