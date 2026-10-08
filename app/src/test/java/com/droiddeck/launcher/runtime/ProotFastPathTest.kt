package com.droiddeck.launcher.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ProotFastPathTest {
    private val root = File("/data/data/com.droiddeck.launcher/files/linuxfs")

    @Test fun aSessionsUsualBindsGetAKey() {
        // 87 on a device with two Games folders: past the old limit of 64, which dropped them.
        val binds = (1..87).map { "/host/$it:/guest/$it" }
        assertNotNull(ProotFastPath.key(root, binds))
    }

    @Test fun moreBindsThanTheLibraryHoldsLeaveItOff() {
        val binds = (1..ProotFastPath.MAX_BINDS + 1).map { "/host/$it:/guest/$it" }
        assertNull(ProotFastPath.key(root, binds))
    }
}
