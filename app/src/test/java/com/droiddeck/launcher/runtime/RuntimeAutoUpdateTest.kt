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
        repeat(RuntimeAutoUpdate.MAX_ATTEMPTS) { assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2")) }
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2"))
    }

    @Test fun aNewerCatalogReleaseStartsAgain() {
        repeat(RuntimeAutoUpdate.MAX_ATTEMPTS) { RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2") }
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc3"))
    }

    @Test fun goingBackToAnEarlierReleaseKeepsItsCount() {
        repeat(RuntimeAutoUpdate.MAX_ATTEMPTS) { RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2") }
        assertTrue(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc3"))
        assertFalse(RuntimeAutoUpdate.claim(context, "ubuntu-r1-rc1", "ubuntu-r1-rc2"))
    }
}
