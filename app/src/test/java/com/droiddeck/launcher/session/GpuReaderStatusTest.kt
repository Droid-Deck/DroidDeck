package com.droiddeck.launcher.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuReaderStatusTest {
    @After fun reset() {
        KgslBusy.status = "not tried"
        KgslClock.status = "not tried"
    }

    @Test fun emptyWhereTheReadersWereNotNeeded() {
        assertEquals("", GpuStatsComponent.readerStatus())
    }

    @Test fun namesBothReaders() {
        KgslBusy.status = "per-context stamps (hardware-scheduled kernel)"
        KgslClock.status = "working (680 MHz at start)"
        assertEquals(
            "kgsl memstore per-context stamps (hardware-scheduled kernel); GPU cycle counter working (680 MHz at start)",
            GpuStatsComponent.readerStatus(),
        )
    }

    @Test fun rangeCheckFailureIsNamed() {
        assertEquals("sample out of range", KgslErrors.describe(KgslClock.RANGE_CHECK_FAILED))
        assertEquals("no error code", KgslErrors.describe(0))
    }
}
