package com.droiddeck.launcher.session

import com.droiddeck.launcher.App
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UncaughtBackgroundErrorTest {
    @Test fun anUncaughtErrorOnABackgroundThreadDoesNotEndTheTestRun() {
        assertTrue(RuntimeEnvironment.getApplication() is App)
        Thread({ throw IllegalStateException("expected by the test") }, "uncaught-background-error").apply {
            start()
            join()
        }
    }
}
