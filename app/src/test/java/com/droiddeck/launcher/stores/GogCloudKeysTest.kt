package com.droiddeck.launcher.stores

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GogCloudKeysTest {
    @Test fun defaultNamespaceIsPrefixedAndStripped() {
        assertEquals("__default/savegame0.sav", gogCloudKey("savegame0.sav", "__default"))
        assertEquals("savegame0.sav", gogLocalKey("__default/savegame0.sav", "__default"))
        assertNull(gogLocalKey("saves/slot0.sav", "__default"))
        assertEquals("slot0.sav", gogCloudKey("slot0.sav", ""))
        assertEquals("slot0.sav", gogLocalKey("slot0.sav", ""))
        assertEquals("__default/savegame0.sav", gogLocalKey("__default/savegame0.sav", ""))
    }

    @Test fun gogCloudBodiesAreGunzippedWhenGalaxyStoredThemCompressed() {
        val raw = "savegame0".toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray()
        assertArrayEquals(raw, gunzipIfNeeded(gz))
        assertArrayEquals(raw, gunzipIfNeeded(raw))
    }
}
