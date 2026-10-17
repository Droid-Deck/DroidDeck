package com.droiddeck.launcher.runtime

import android.content.Context
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
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

    /** The session language maps to the locale droiddeck-orca-setup generated. */
    @Test fun languageTagMapsToGeneratedLocales() {
        assertEquals("zh_CN", OrcaManager.languageTag(Locale("zh", "CN")))
        assertEquals("zh_CN", OrcaManager.languageTag(Locale("zh", "HK")))
        assertEquals("es_ES", OrcaManager.languageTag(Locale("es", "ES")))
        assertEquals("fr_FR", OrcaManager.languageTag(Locale("fr", "FR")))
        assertEquals("ja_JP", OrcaManager.languageTag(Locale("ja", "JP")))
        assertEquals("ko_KR", OrcaManager.languageTag(Locale("ko", "KR")))
        assertEquals("ru_RU", OrcaManager.languageTag(Locale("ru", "RU")))
        assertEquals("en_US", OrcaManager.languageTag(Locale("en", "US")))
        // A language without a generated locale falls back to English, not to a missing one.
        assertEquals("en_US", OrcaManager.languageTag(Locale("de", "DE")))
    }
}
