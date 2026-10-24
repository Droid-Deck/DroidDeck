package com.droiddeck.launcher.gpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualcommSystemDriverTest {
    @Test fun detectsTheProprietaryDriverByIdentity() {
        assertTrue(QualcommSystemDriver.isQualcomm(mapOf("vendor_id" to "0x5143", "driver_id" to "8")))
        assertFalse(QualcommSystemDriver.isQualcomm(mapOf("vendor_id" to "0x5143", "driver_id" to "18", "driver_name" to "Qualcomm")))
        assertFalse(QualcommSystemDriver.isQualcomm(mapOf("vendor_id" to "0x13b5", "driver_id" to "8")))
    }

    @Test fun olderDriverPropertiesRequireAnExplicitVendorName() {
        assertTrue(QualcommSystemDriver.isQualcomm(mapOf("vendor_id" to "0x5143", "driver_name" to "Qualcomm Technologies Inc.")))
        assertFalse(QualcommSystemDriver.isQualcomm(mapOf("vendor_id" to "0x5143", "driver_name" to "Mesa Turnip")))
        assertFalse(QualcommSystemDriver.isQualcomm(mapOf("vendor_id" to "0x5143", "device" to "Adreno 840")))
    }
}
