package com.droiddeck.xr

import org.junit.Assert.*
import org.junit.Test

class XrReadinessTest {
    @Test fun featuresPreserveQuestRouting() {
        assertEquals(XrPlatform.ANDROID_XR, XrPlatform.detect(true, false))
        assertEquals(XrPlatform.QUEST, XrPlatform.detect(true, true))
        assertEquals(XrPlatform.QUEST, XrPlatform.detect(false, true))
        assertEquals(XrPlatform.NONE, XrPlatform.detect(false, false))
    }

    @Test fun unsupportedDeviceCannotEnterEvenWithGuestReady() {
        assertEquals(XrEntryReadiness.UNSUPPORTED_DEVICE, XrEntryReadiness.evaluate(XrPlatform.NONE, true))
        assertFalse(XrEntryReadiness.evaluate(XrPlatform.NONE, true).canProbe)
    }

    @Test fun supportedDeviceRequiresAnActiveGuestBeforeProbing() {
        for (platform in listOf(XrPlatform.QUEST, XrPlatform.ANDROID_XR)) {
            assertEquals(XrEntryReadiness.GUEST_NOT_READY, XrEntryReadiness.evaluate(platform, false))
            assertTrue(XrEntryReadiness.evaluate(platform, true).canProbe)
        }
    }
}
