package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Accessibility tab's state: whether Orca is in the runtime, and the switch. */
@RunWith(RobolectricTestRunner::class)
class OrcaManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun notInstalledWithoutOrca() {
        assertFalse(OrcaManager.isInstalled(context))
    }

    @Test fun installedOnceOrcaIsThere() {
        val orca = File(LinuxRuntime.rootDir(context), "usr/bin/orca")
        orca.parentFile!!.mkdirs()
        orca.writeText("#!/bin/sh\n")
        assertTrue(OrcaManager.isInstalled(context))
        orca.delete()
    }

    @Test fun enabledPersists() {
        assertFalse(OrcaManager.enabled(context))
        OrcaManager.setEnabled(context, true)
        assertTrue(OrcaManager.enabled(context))
        OrcaManager.setEnabled(context, false)
        assertFalse(OrcaManager.enabled(context))
    }
}
