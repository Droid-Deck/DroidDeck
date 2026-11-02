package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.SystemVulkanComponentStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemVulkanSessionSnapshotTest {
    private fun snapshot(hash: Char) = SystemVulkanComponentStore.Snapshot(
        hash.toString().repeat(64), "powervr", hash.toString(), File("/$hash"), false,
    )

    @Test fun olderTeardownCannotClearNewerSessionSnapshot() {
        val old = snapshot('a')
        val current = snapshot('b')
        assertFalse(shouldClearSystemVulkanSnapshot(1, 2, old, current))
        assertFalse(shouldClearSystemVulkanSnapshot(2, 2, old, current))
        assertTrue(shouldClearSystemVulkanSnapshot(2, 2, current, current))
    }
}
