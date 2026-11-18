package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.MaliGraphicsMode.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaliGraphicsTest {
    @Test fun autoPrefersPanvkThenVenusThenBridgeAndCanBeUnavailable() {
        assertEquals(PANVK, MaliGraphics.resolve(AUTO, true, true, true, true))
        assertEquals(VENUS, MaliGraphics.resolve(AUTO, true, false, true, true))
        assertEquals(BRIDGE, MaliGraphics.resolve(AUTO, true, false, false, true))
        assertNull(MaliGraphics.resolve(AUTO, true, false, false, false))
        assertNull(MaliGraphics.resolve(AUTO, false, true, true, true))
    }

    @Test fun aFixedPathIsNeverReplacedEvenWhenItIsUnavailable() {
        for (choice in listOf(PANVK, BRIDGE, VENUS)) {
            assertEquals(choice, MaliGraphics.resolve(choice, true, false, false, false))
            assertEquals(choice, MaliGraphics.resolve(choice, true, true, true, true))
        }
    }

    @Test fun freshPreferencesAndRecognizableAutoOwnedValuesMigrateToAuto() {
        assertEquals(AUTO, MaliGraphics.migrate(true, null, null, null))
        assertEquals(AUTO, MaliGraphics.migrate(true, true, false, "bridge"))
        assertEquals(AUTO, MaliGraphics.migrate(true, null, false, "bridge"))
        assertEquals(AUTO, MaliGraphics.migrate(true, false, true, "venus"))
        assertEquals(AUTO, MaliGraphics.migrate(true, null, true, "venus"))
    }

    @Test fun ownerlessDataPreservesTheLegacyEffectiveChoiceIncludingDefaults() {
        assertEquals(VENUS, MaliGraphics.migrate(true, true, true, ""))
        assertEquals(VENUS, MaliGraphics.migrate(true, false, true, null))
        assertEquals(BRIDGE, MaliGraphics.migrate(true, true, false, ""))
        assertEquals(BRIDGE, MaliGraphics.migrate(true, null, false, null))
        assertEquals(PANVK, MaliGraphics.migrate(true, false, false, null))
        assertEquals(PANVK, MaliGraphics.migrate(true, false, null, ""))
    }

    @Test fun ownershipOfOneSwitchDoesNotClaimTheOtherSwitch() {
        assertEquals(VENUS, MaliGraphics.migrate(true, true, true, "bridge"))
        assertEquals(BRIDGE, MaliGraphics.migrate(true, true, false, "venus"))
        assertEquals(PANVK, MaliGraphics.migrate(true, false, false, "bridge"))
    }
}
