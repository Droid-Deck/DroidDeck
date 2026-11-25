package com.droiddeck.launcher.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VkBridgeComponentTest {
    @Test fun theServerSignalsAcquiresWithoutARoundTripByDefault() {
        assertArrayEquals(arrayOf("HOME=/data/files", "VKBRIDGE_ASYNC_ACQUIRE=1"),
            VkBridgeComponent.serverEnv("/data/files") { null })
    }

    @Test fun aValueTheAppWasStartedWithIsKept() {
        val env = mapOf("VKBRIDGE_ASYNC_ACQUIRE" to "0")
        assertArrayEquals(arrayOf("HOME=/data/files", "VKBRIDGE_ASYNC_ACQUIRE=0"),
            VkBridgeComponent.serverEnv("/data/files") { env[it] })
    }

    @Test fun theServerGetsNoOtherSwitch() {
        assertArrayEquals(arrayOf("VKBRIDGE_ASYNC_ACQUIRE"), VkBridgeComponent.SERVER_DEFAULTS.keys.toTypedArray())
    }
}
