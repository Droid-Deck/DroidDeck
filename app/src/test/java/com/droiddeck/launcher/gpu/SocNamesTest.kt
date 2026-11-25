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
        assertEquals("Snapdragon 8 Elite Gen 5", SocNames.name("SM8850", "canoe"))
        assertEquals("Snapdragon 8 Elite Gen 5", SocNames.name("QTI SM8850P", "canoe"))
        assertEquals("Snapdragon 8 Elite Gen 5 family", SocNames.name("", "canoe"))
        assertEquals("Snapdragon 8s Gen 3", SocNames.name("SM8635", "cliff"))
        assertEquals("Snapdragon 8s Gen 3 family", SocNames.name("", "cliff"))
        assertEquals("Snapdragon 8s Gen 4", SocNames.name("SM8735", ""))
        assertEquals("Snapdragon 8s Gen 5", SocNames.name("SM8845", ""))
        assertEquals("Snapdragon G3x Gen 2", SocNames.name("SG8275P", ""))
        assertEquals("Snapdragon 7+ Gen 2", SocNames.name("SM7475", ""))
        assertEquals("Snapdragon 7s Gen 3", SocNames.name("SM7635", ""))
        assertEquals("Snapdragon 7+ Gen 3", SocNames.name("SM7675", ""))
        assertEquals("Snapdragon 8 Gen 3 family", SocNames.name("SG8350P", "pineapple"))
        assertNull(SocNames.name("XYZ123", "nowhere"))
        assertEquals("Snapdragon 8 Gen 2 (QCS8550)", SocNames.label("QCS8550", ""))
        assertEquals("Snapdragon 8 Elite Gen 5 (SM8850)", SocNames.label("SM8850", "canoe"))
        assertEquals("Snapdragon 8 Gen 3 family (SG8350P)", SocNames.label("SG8350P", "pineapple"))
        assertEquals("XYZ123", SocNames.label("XYZ123", ""))
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

    @Test fun fullSnapdragonLineupAndPlatformsAreCovered() {
        val models = mapOf(
            "SM8150" to "Snapdragon 855", "SM8150-AC" to "Snapdragon 855+",
            "SM8250" to "Snapdragon 865", "SM8250-AC" to "Snapdragon 870",
            "SM8350" to "Snapdragon 888", "SM8350-AC" to "Snapdragon 888+",
            "SM8450" to "Snapdragon 8 Gen 1", "SM8475" to "Snapdragon 8+ Gen 1", "SM8475P" to "Snapdragon 8+ Gen 1",
            "SM8550" to "Snapdragon 8 Gen 2", "SM8550P" to "Snapdragon 8 Gen 2",
            "SM8650" to "Snapdragon 8 Gen 3", "QCS8650" to "Snapdragon 8 Gen 3",
            "SM8750" to "Snapdragon 8 Elite", "SM8850" to "Snapdragon 8 Elite Gen 5",
            "SM8845P" to "Snapdragon 8s Gen 5", "SM8975P" to "Snapdragon 8 Elite Extreme Gen 6",
            "SG8150P" to "Snapdragon G3x Gen 1", "SG8275" to "Snapdragon G3x Gen 2", "SG8275P" to "Snapdragon G3x Gen 2",
            "SG6150P" to "Snapdragon G2 Gen 1", "SG4150P" to "Snapdragon G1 Gen 1",
            "CQ8725S" to "Snapdragon 8 Elite Gaming",
            "SM7125" to "Snapdragon 720G", "SM7150" to "Snapdragon 730",
            "SM7225" to "Snapdragon 750G", "SM7250" to "Snapdragon 765G",
            "SM7325" to "Snapdragon 778G", "SM7435" to "Snapdragon 7s Gen 2",
            "SM7450" to "Snapdragon 7 Gen 1", "SM7550" to "Snapdragon 7 Gen 3",
            "SM6115" to "Snapdragon 662", "SM6125" to "Snapdragon 665",
            "SM6225" to "Snapdragon 680", "SM6375" to "Snapdragon 695",
            "SM6450" to "Snapdragon 6 Gen 1",
        )
        for ((id, expected) in models) assertEquals(id, expected, SocNames.name(id, ""))

        val platforms = mapOf(
            "tuna" to "Snapdragon 8s Gen 4 family",
            "cliffs" to "Snapdragon 7+ Gen 3 family",
            "volcano" to "Snapdragon 7s Gen 3 family",
            "crow" to "Snapdragon 7 Gen 3 family",
            "ukee" to "Snapdragon 7+ Gen 2 family",
            "yupik" to "Snapdragon 778G family",
            "lito" to "Snapdragon 765G family",
            "atoll" to "Snapdragon 720G family",
            "parrot" to "Snapdragon 6 Gen 1 family",
            "holi" to "Snapdragon 695 family",
            "khaje" to "Snapdragon 680 family",
            "bengal" to "Snapdragon 662 family",
            "trinket" to "Snapdragon 665 family",
        )
        for ((platform, expected) in platforms) assertEquals(platform, expected, SocNames.name("", platform))
    }
}
