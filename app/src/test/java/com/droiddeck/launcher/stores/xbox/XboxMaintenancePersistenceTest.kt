package com.droiddeck.launcher.stores.xbox

import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class XboxMaintenancePersistenceTest {
    @Test fun completedMaintenanceDoesNotPersistQueueMarkers() {
        val persisted = XboxInstaller.maintenanceSidecarExtra(
            mapOf(
                "contentId" to "old-content",
                "market" to "US",
                "preferredLanguage" to "fr-FR",
                StoreMaintenance.OPERATION_KEY to "UPDATE",
                StoreMaintenance.FOLDER_KEY to "/games/example",
            ),
            "new-content",
            "GB",
        )

        assertEquals("new-content", persisted["contentId"])
        assertEquals("GB", persisted["market"])
        assertEquals("fr-FR", persisted["preferredLanguage"])
        assertFalse(persisted.containsKey(StoreMaintenance.OPERATION_KEY))
        assertFalse(persisted.containsKey(StoreMaintenance.FOLDER_KEY))
    }
}
