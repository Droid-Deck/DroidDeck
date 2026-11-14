package com.droiddeck.launcher.gpu

import android.content.Context
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SystemVulkanPruneAbsentRuntimeTest {
    @Test fun pruningWithoutTheRuntimeDoesNotThrowAndLeavesFilesAlone() {
        val context: Context = RuntimeEnvironment.getApplication()
        val base = File(context.filesDir, "system_vulkan").apply { mkdirs() }
        val stage = File(base, ".stage-" + java.util.UUID.randomUUID()).apply { mkdir() }
        val version = File(base, "a".repeat(64)).apply { mkdir() }

        SystemVulkanDriver.pruneUnused(context, true)

        assertTrue(stage.exists())
        assertTrue(version.exists())
    }
}
