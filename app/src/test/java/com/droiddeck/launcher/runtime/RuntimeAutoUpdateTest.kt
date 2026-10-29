package com.droiddeck.launcher.runtime

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class RuntimeAutoUpdateTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun anInstalledRuntimeBehindTheCatalogUpdatesByItself() {
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2"))
    }

    @Test fun noRuntimeOrTheSameOneIsLeftAlone() {
        assertFalse(RuntimeAutoUpdate.claim(context, null, "ubuntu-r1-rc2"))
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc2", "ubuntu-r1-rc2"))
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", null))
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", ""))
    }

    @Test fun aFailingUpdateStopsTryingAfterAFewLaunches() {
        repeat(RuntimeAutoUpdate.MAX_ATTEMPTS) { assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", it * RuntimeAutoUpdate.RETRY_MS)) }
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", 10 * RuntimeAutoUpdate.RETRY_MS))
    }

    @Test fun aRetryWaitsBeforeUsingTheNextTry() {
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", 1_000L))
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", 2_000L))
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", 1_000L + RuntimeAutoUpdate.RETRY_MS))
    }

    @Test fun aNewerCatalogReleaseStartsAgain() {
        repeat(RuntimeAutoUpdate.MAX_ATTEMPTS) { RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", it * RuntimeAutoUpdate.RETRY_MS) }
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc3", 5 * RuntimeAutoUpdate.RETRY_MS))
    }

    @Test fun goingBackToAnEarlierReleaseKeepsItsCount() {
        repeat(RuntimeAutoUpdate.MAX_ATTEMPTS) { RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", it * RuntimeAutoUpdate.RETRY_MS) }
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc3", 5 * RuntimeAutoUpdate.RETRY_MS))
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2", 10 * RuntimeAutoUpdate.RETRY_MS))
    }
}
