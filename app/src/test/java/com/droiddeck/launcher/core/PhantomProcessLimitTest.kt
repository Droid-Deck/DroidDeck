package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhantomProcessLimitTest {
    @Test
    fun developerOptionsToggleAloneCountsAsOff() {
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status(null, "false"))
    }

    @Test
    fun globalSettingWinsOverTheProperty() {
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status("false", "true"))
        assertEquals(PhantomProcessStatus.ENABLED, PhantomProcessLimit.status("true", "false"))
    }

    @Test
    fun emptyGlobalFallsThroughToTheProperty() {
        assertEquals(PhantomProcessStatus.ENABLED, PhantomProcessLimit.status("", "true"))
        assertEquals(PhantomProcessStatus.DISABLED, PhantomProcessLimit.status(" ", "0"))
    }

    @Test
    fun neitherSetIsUnset() {
        assertEquals(PhantomProcessStatus.UNSET, PhantomProcessLimit.status(null, null))
        assertEquals(PhantomProcessStatus.UNSET, PhantomProcessLimit.status("", ""))
    }
}
