package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTuDebugTest {
    @Test fun overrideFileWinsOverEverything() {
        assertEquals("noubwc", SessionService.tuDebugFor("noubwc", tuSysmem = true, driverName = "710-720", gpuModel = 741))
    }

    @Test fun x185DefaultsToNolrzAndNolrzfc() {
        assertEquals("nolrz,nolrzfc", SessionService.tuDebugFor(override = null, tuSysmem = false, driverName = "", gpuModel = 741))
    }

    @Test fun a710FamilyGetsSysmem() {
        assertEquals("sysmem", SessionService.tuDebugFor(override = null, tuSysmem = false, driverName = "Turnip-v24-710-720", gpuModel = 730))
        assertEquals("sysmem", SessionService.tuDebugFor(override = null, tuSysmem = false, driverName = "Turnip_710_720", gpuModel = 730))
    }

    @Test fun tuSysmemPreferenceAddsSysmem() {
        assertEquals("sysmem", SessionService.tuDebugFor(override = null, tuSysmem = true, driverName = "", gpuModel = 740))
    }

    @Test fun combinesSysmemAndNolrzWhenBothApply() {
        assertEquals("sysmem,nolrz,nolrzfc", SessionService.tuDebugFor(override = null, tuSysmem = true, driverName = "", gpuModel = 741))
    }

    @Test fun otherGpusDefaultToNull() {
        assertNull(SessionService.tuDebugFor(override = null, tuSysmem = false, driverName = "turnip-v24", gpuModel = 740))
        assertNull(SessionService.tuDebugFor(override = null, tuSysmem = false, driverName = "turnip-v24", gpuModel = 825))
    }
}
