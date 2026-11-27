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
        assertEquals("QTI SM8750P", SocNames.model(props("ro.soc.model" to "unknown", "ro.vendor.qti.soc_model" to "QTI SM8750P")))
    }

    @Test fun knownModelsAndPlatformsGetTheirNames() {
        assertEquals("Snapdragon 8 Gen 2", SocNames.name("QCS8550", ""))
        assertEquals("Snapdragon G3 Gen 3", SocNames.name("SG8350P", "pineapple"))
        assertNull(SocNames.name("XYZ123", "nowhere"))
        assertEquals("Snapdragon 8 Gen 2 (QCS8550)", SocNames.label("QCS8550", ""))
        assertEquals("Snapdragon G3 Gen 3 (SG8350P)", SocNames.label("SG8350P", "pineapple"))
        assertEquals("XYZ123", SocNames.label("XYZ123", ""))
    }
    @Test fun platformFamilyOnlyForPhonePartsOrNoModel() {
        assertEquals("Dragonwing Q-7790", SocNames.name("CQ7790S", "sun"))
        assertNull(SocNames.name("CQ1234X", "sun"))
        assertEquals("Snapdragon 8 Elite family", SocNames.name("SM9999", "sun"))
        assertEquals("Snapdragon 8 Elite family", SocNames.name("", "sun"))
        assertEquals("Snapdragon 8 Elite Gen 5 family", SocNames.name("", "canoe"))
    }

    @Test fun qtiPrefixIsNormalizedWithoutGuessingOtherModelVariants() {
        assertEquals("Snapdragon 8 Elite Extreme Gen 6", SocNames.name("SM8975", ""))
        assertEquals("Snapdragon 8 Elite Extreme Gen 6", SocNames.name("QTI SM8975", ""))
        assertEquals("Snapdragon 8 Elite Extreme Gen 6", SocNames.name(" qti sm8975 ", ""))
        assertEquals("Snapdragon 8 Elite Extreme Gen 6 (QTI SM8975)", SocNames.label("QTI SM8975", ""))
        assertEquals("Snapdragon 8 Elite", SocNames.name("QTI SM8750P", ""))
        assertNull(SocNames.name("SM89750", ""))
        assertNull(SocNames.name("SM8975-PRO", ""))
        assertNull(SocNames.name("Qualcomm SM8975", ""))
    }
    @Test fun plainNameNeverGuessesFromThePlatform() {
        assertEquals("Snapdragon 8 Gen 2 (SM8550)", SocNames.plainName("SM8550", "QTI"))
        assertEquals("Dragonwing Q-7790 (CQ7790S)", SocNames.plainName("CQ7790S", "Moorechip"))
        assertEquals("Moorechip CQ1234X", SocNames.plainName("CQ1234X", "Moorechip"))
        assertEquals("CQ1234X", SocNames.plainName("CQ1234X", ""))
    }
}
