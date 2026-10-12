package com.droiddeck.launcher.core

import org.junit.Assert.*
import org.junit.Test

class CpuAllocationDefaultsTest {
    private val thor = (0..7).associateWith { when (it) { in 0..2 -> 2.016; in 3..6 -> 2.8032; else -> 3.1872 } }

    @Test fun gen2UsesTheFourMiddleCoresAndOnePrime() {
        for (model in listOf("SM8550", "QCS8550", "sm8550-ac")) {
            assertEquals(CpuAllocationDefaults.Allocation("7", "3,4,5,6"), CpuAllocationDefaults.forDevice(model, "", thor))
        }
        assertEquals(CpuAllocationDefaults.Allocation("7", "3,4,5,6"), CpuAllocationDefaults.forDevice("", "kalama", thor))
    }

    @Test fun coreNumbersAndClockVariantsAreDetectedInsteadOfHardcoded() {
        val reordered = mapOf(0 to 3.36, 1 to 2.0, 2 to 2.0, 3 to 2.0, 4 to 2.8, 5 to 2.8, 6 to 2.8, 7 to 2.8)
        assertEquals(CpuAllocationDefaults.Allocation("0", "4,5,6,7"), CpuAllocationDefaults.forDevice("SM8550-AC", "kalama", reordered))
    }

    @Test fun anotherOrUnknownChipDoesNotAcquireThisDefault() {
        assertNull(CpuAllocationDefaults.forDevice("SM8650", "pineapple", thor))
        assertNull(CpuAllocationDefaults.forDevice("SM8650", "kalama", thor))
        assertNull(CpuAllocationDefaults.forDevice("", "", thor))
    }

    @Test fun missingOrUnexpectedTopologyKeepsTheSchedulerDefault() {
        assertNull(CpuAllocationDefaults.forDevice("SM8550", "kalama", thor - 7))
        assertNull(CpuAllocationDefaults.forDevice("SM8550", "kalama", thor + (7 to null)))
        assertNull(CpuAllocationDefaults.forDevice("SM8550", "kalama", thor + (7 to 0.0)))
        assertNull(CpuAllocationDefaults.forDevice("SM8550", "kalama", thor + (7 to Double.NaN)))
        assertNull(CpuAllocationDefaults.forDevice("SM8550", "kalama", (0..7).associateWith { 2.8 }))
    }

    @Test fun allCoreGamesUndoTheClientsInheritedRestriction() {
        val all = CpuCores.listOrAll("")
        assertEquals(all, CpuCores.gameLaunchList("", clientPinned = true))
        assertEquals(all, CpuCores.gameLaunchList(all, clientPinned = true))
        assertEquals("", CpuCores.gameLaunchList("", clientPinned = false))
        assertEquals("0", CpuCores.gameLaunchList("0", clientPinned = true))
    }
}
