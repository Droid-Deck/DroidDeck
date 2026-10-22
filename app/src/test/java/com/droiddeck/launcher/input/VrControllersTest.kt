package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class VrControllersTest {
    @Test fun encodesTheKeysTheSteamVrDriverReads() {
        assertEquals("emulate=index\nsticks=game\n", VrControllers.encode(VrControllers.Settings()))
        assertEquals("emulate=touch\nsticks=hands\n",
            VrControllers.encode(VrControllers.Settings(VrControllers.Emulate.TOUCH, sticksMoveHands = true)))
        assertEquals(listOf("off", "index", "touch", "vive"), VrControllers.Emulate.entries.map { it.key })
    }
}
