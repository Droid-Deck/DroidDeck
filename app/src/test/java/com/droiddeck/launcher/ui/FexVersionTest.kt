package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FexVersionTest {
    @Test fun readsTheTagTheLauncherPins() {
        assertEquals("2610 r3", fexVersionOf("#!/bin/bash\nset -eu\nFEX_TAG=fex-2610-r3\nFEX_SHA256=abc\n"))
    }

    @Test fun nothingWithoutAPinnedTag() {
        assertNull(fexVersionOf("FEX_TAG=\$1\n"))
        assertNull(fexVersionOf("# FEX_TAG=fex-2610-r3\n"))
    }

    @Test fun readsAnInstalledOrCatalogTag() {
        assertEquals("2610 r5", fexVersionOfTag("fex-2610-r5\n"))
        assertNull(fexVersionOfTag("latest"))
    }

    @Test fun theShippedLauncherHasOne() {
        val script = java.io.File("../tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-x64").takeIf { it.isFile }
            ?: java.io.File("tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-x64")
        assertEquals(true, fexVersionOf(script.readText()) != null)
    }
}
