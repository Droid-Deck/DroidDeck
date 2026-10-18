package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class JoyConFlowGateTest {
    @Test fun missingDetectionsCannotExtendTheSeedLifetime() {
        val seed = 1_000_000_000L
        assertTrue(JoyConFlowGate.fresh(seed, seed + 640_000_000, seed + 720_000_000))
        assertFalse(JoyConFlowGate.fresh(seed, seed + 720_000_000, seed + 800_000_000))
        assertFalse(JoyConFlowGate.fresh(seed, seed, seed + 200_000_001))
        assertFalse(JoyConFlowGate.fresh(seed, seed, seed))
    }

    @Test fun featureMustBeVisibleAndReturnToItsOriginalLocation() {
        assertTrue(JoyConFlowGate.point(110.0,105.0,100.5,100.5,100.0,100.0,5f,true,true,640,480))
        assertFalse(JoyConFlowGate.point(110.0,105.0,103.0,100.0,100.0,100.0,5f,true,true,640,480))
        assertFalse(JoyConFlowGate.point(110.0,105.0,100.0,100.0,100.0,100.0,30f,true,true,640,480))
        assertFalse(JoyConFlowGate.point(110.0,105.0,100.0,100.0,100.0,100.0,5f,false,true,640,480))
        assertFalse(JoyConFlowGate.point(Double.NaN,105.0,100.0,100.0,100.0,100.0,5f,true,true,640,480))
        assertFalse(JoyConFlowGate.point(640.0,105.0,100.0,100.0,100.0,100.0,5f,true,true,640,480))
    }
}
