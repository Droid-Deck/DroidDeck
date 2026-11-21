package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.MaliGraphicsMode.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MaliGraphicsTest {
    @Test fun exactlyThreeSavedChoices() {
        assertEquals(listOf("auto", "bridge", "venus"), MaliGraphicsMode.entries.map { it.id })
    }

    @Test fun fixedChoiceWinsOverSelectedDriverAndAvailability() {
        for (choice in listOf(BRIDGE, VENUS)) for (selected in listOf(false, true)) for (venus in listOf(false, true)) {
            assertEquals(choice, MaliGraphics.resolve(choice, selected, venus))
        }
    }

    @Test fun autoUsesExistingSelectedDriverBeforeFallbackComponents() {
        assertEquals(AUTO, MaliGraphics.resolve(AUTO, true, true))
        assertEquals(AUTO, MaliGraphics.resolve(AUTO, true, false))
        assertEquals(VENUS, MaliGraphics.resolve(AUTO, false, true))
        assertEquals(BRIDGE, MaliGraphics.resolve(AUTO, false, false))
    }

    @Test fun legacyEnabledChoiceRespectsAutomaticOwnership() {
        assertEquals(VENUS, MaliGraphics.migrate(false, true, null))
        assertEquals(BRIDGE, MaliGraphics.migrate(true, false, null))
        assertEquals(VENUS, MaliGraphics.migrate(true, true, null))
        assertEquals(AUTO, MaliGraphics.migrate(false, true, "venus"))
        assertEquals(AUTO, MaliGraphics.migrate(true, false, "bridge"))
        for (bridge in listOf(null, false)) for (venus in listOf(null, false)) {
            assertEquals(AUTO, MaliGraphics.migrate(bridge, venus, null))
        }
    }

    @Test fun fixedRoutesOwnRoutingButLeaveDiagnosticOverrides() {
        val extras = listOf("DD_MALI_GRAPHICS_MODE=auto", "DD_INSIDE=1", "DD_VENUS=0", "DD_VK_DRIVER=other",
            "DROIDDECK_GAME_GALLIUM_DRIVER=llvmpipe", "VK_DRIVER_FILES=other", "VTEST_SOCKET_NAME=other",
            "VK_LOADER_DEBUG=all", "MESA_DEBUG=1")
        for (choice in listOf(BRIDGE, VENUS)) {
            assertEquals(listOf("VK_LOADER_DEBUG=all", "MESA_DEBUG=1"), MaliGraphics.diagnosticOverrides(choice, extras))
        }
        assertEquals(extras, MaliGraphics.diagnosticOverrides(AUTO, extras))
    }
}
