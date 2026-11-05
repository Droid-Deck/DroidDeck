package com.droiddeck.launcher.core

import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BackgroundAppLimitTest {
    @Test fun theLimitIsActivityManagersCachedProcessCap() {
        assertEquals("device_config get activity_manager max_cached_processes", BackgroundAppLimit.readCommand())
        assertEquals("device_config put activity_manager max_cached_processes 4", BackgroundAppLimit.setCommand(4))
        assertEquals("device_config put activity_manager max_cached_processes 0", BackgroundAppLimit.setCommand(0))
        assertThrows(IllegalArgumentException::class.java) { BackgroundAppLimit.setCommand(-1) }
    }

    @Test fun whatWasThereIsWhatGoesBack() {
        assertNull(BackgroundAppLimit.parse("null\n"))
        assertNull(BackgroundAppLimit.parse(""))
        assertEquals("16", BackgroundAppLimit.parse("16\n"))
        assertEquals("device_config delete activity_manager max_cached_processes", BackgroundAppLimit.restoreCommand(null))
        assertEquals("device_config put activity_manager max_cached_processes 16", BackgroundAppLimit.restoreCommand("16"))
    }

    @Test fun setupOffersOffAndDeveloperOptionsOwnLimits() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(BackgroundAppLimit.OFF, SessionPrefs.backgroundAppLimit(context))
        for (limit in listOf(4, 1, 0, BackgroundAppLimit.OFF)) {
            SessionPrefs.setBackgroundAppLimit(context, limit)
            assertEquals(limit, SessionPrefs.backgroundAppLimit(context))
        }
        assertThrows(IllegalArgumentException::class.java) { SessionPrefs.setBackgroundAppLimit(context, 7) }
    }
}
