package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocNamesTest {
    private fun props(vararg pairs: Pair<String, String>): (String) -> String = { pairs.toMap()[it].orEmpty() }

    @Test fun theModelIsFoundWhereVendorsPutIt() {
        // AYANEO Pocket FIT: ro.soc.model blank, the vendor property set, ro.fota.platform a branch name.
        assertEquals("SG8350P", SocNames.model(props("ro.vendor.qti.soc_model" to "SG8350P", "ro.fota.platform" to "MSM_13.0")))
        assertEquals("QCS8550", SocNames.model(props("ro.soc.model" to "QCS8550", "ro.vendor.qti.soc_model" to "other")))
        assertEquals("", SocNames.model(props("ro.fota.platform" to "MSM_13.0")))
    }

    @Test fun knownModelsAndPlatformsGetTheirNames() {
        assertEquals("Snapdragon 8 Gen 2", SocNames.name("QCS8550", ""))
        assertEquals("Snapdragon 8 Gen 3 family", SocNames.name("SG8350P", "pineapple"))
        assertEquals("Snapdragon X Elite", SocNames.name("SC8380XP", ""))
        assertEquals("Snapdragon X Elite", SocNames.name("X1E80100", ""))
        assertEquals("Snapdragon X Elite family", SocNames.name("other", "hamoa"))
        assertEquals("Snapdragon X Plus family", SocNames.name("other", "purwa"))
        assertNull(SocNames.name("XYZ123", "nowhere"))
        assertEquals("Snapdragon 8 Gen 2 (QCS8550)", SocNames.label("QCS8550", ""))
        assertEquals("Snapdragon 8 Gen 3 family (SG8350P)", SocNames.label("SG8350P", "pineapple"))
        assertEquals("Snapdragon X Elite (SC8380XP)", SocNames.label("SC8380XP", "hamoa"))
        assertEquals("Snapdragon X Elite family (other)", SocNames.label("other", "hamoa"))
        assertEquals("XYZ123", SocNames.label("XYZ123", ""))
    }
}
