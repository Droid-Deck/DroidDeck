package com.droiddeck.launcher.stores.xbox

import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XboxInstallerRefusalTest {
    private val refused = "NEXUS_CONTENT_LICENSE_DIAGNOSTIC_V1 content-device-name 400"
    private val full = "NEXUS_CONTENT_LICENSE_DIAGNOSTIC_V1 content-device-limit 200"
    private val granted = "NEXUS_CONTENT_LICENSE_DIAGNOSTIC_V1 content-parsed 200"

    @Test fun theLastLicensingAnswerDecidesTheMessage() {
        assertEquals(R.string.stores_xbox_license_refused, XboxInstaller.licenseRefusal(listOf(refused, "NEXUS_LEGACY_INSTALL_DIAGNOSTIC_V1 license-failed")))
        assertEquals(R.string.stores_xbox_device_group_full, XboxInstaller.licenseRefusal(listOf(full)))
    }

    @Test fun aRefusalAnOlderBuildRecoveredFromIsNotReported() {
        assertNull(XboxInstaller.licenseRefusal(listOf(refused, "NEXUS_LEGACY_INSTALL_DETAIL_V1 newest build has no license for this device, trying an older build", granted, "NEXUS_TRANSFER_DIAGNOSTIC_V1 transfer-short 0")))
        assertNull(XboxInstaller.licenseRefusal(listOf("NEXUS_TRANSFER_DIAGNOSTIC_V1 transfer-short 0")))
    }
}
