package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocNamesTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String = { pairs.toMap()[it].orEmpty() }

    @Test fun theModelIsFoundWhereVendorsPutIt() {
        assertEquals("SG8350P", SocNames.model(props("ro.vendor.qti.soc_model" to "SG8350P", "ro.fota.platform" to "MSM_13.0")))
        assertEquals("QCS8550", SocNames.model(props("ro.soc.model" to "QCS8550", "ro.vendor.qti.soc_model" to "other")))
        assertEquals("", SocNames.model(props("ro.fota.platform" to "MSM_13.0")))
    }

    @Test fun knownModelsAndPlatformsGetTheirNames() {
        assertEquals("Snapdragon 8 Gen 2", SocNames.name("QCS8550", ""))
        assertEquals("Snapdragon 870", SocNames.name("SM8250-AC", "kona"))
        assertEquals("Snapdragon 8 Gen 3", SocNames.name("SM8650-AC", "pineapple"))
        assertEquals("Snapdragon 8 Elite", SocNames.name("SM8750-AC", "sun"))
        assertEquals("Snapdragon 8 Elite Gen 5", SocNames.name("SM8850-AC", "canoe"))
        assertEquals("Snapdragon 8 Gen 3 family", SocNames.name("SG8350P", "pineapple"))
        assertEquals("Snapdragon 8 Elite Gen 5 family", SocNames.name("", "canoe"))
        assertNull(SocNames.name("XYZ123", "nowhere"))
        assertEquals("Snapdragon 8 Gen 2 (QCS8550)", SocNames.label("QCS8550", ""))
        assertEquals("Snapdragon 8 Elite Gen 5 (SM8850-AC)", SocNames.label("SM8850-AC", "canoe"))
        assertEquals("Snapdragon 8 Gen 3 family (SG8350P)", SocNames.label("SG8350P", "pineapple"))
        assertEquals("XYZ123", SocNames.label("XYZ123", ""))
    }
}
