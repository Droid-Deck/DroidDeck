package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionBackgroundPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearSettings() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun steamDefaultsToAutoWithoutChangingOtherModesOrSavedPolicies() {
        assertEquals(SessionPrefs.SUSPEND_AUTO, SessionPrefs.suspendPolicy(context, SessionService.MODE_STEAM))
        assertEquals(SessionPrefs.SUSPEND_MANUAL, SessionPrefs.suspendPolicy(context, SessionService.MODE_DESKTOP))
        assertEquals(SessionPrefs.SUSPEND_MANUAL, SessionPrefs.suspendPolicy(context, SessionService.MODE_RUN))
        for (policy in listOf(SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_MANUAL, SessionPrefs.SUSPEND_NEVER,
                SessionPrefs.SUSPEND_NATIVE)) {
            SessionPrefs.setSuspendPolicy(context, SessionService.MODE_STEAM, policy)
            assertEquals(policy, SessionPrefs.suspendPolicy(context, SessionService.MODE_STEAM))
            assertEquals(SessionPrefs.SUSPEND_MANUAL,
                SessionPrefs.suspendPolicy(context, SessionService.MODE_DESKTOP))
        }
    }

    @Test fun directGamesUseAutoWithoutOverwritingSteamsNativeChoice() {
        SessionPrefs.setSuspendPolicy(context, SessionService.MODE_STEAM, SessionPrefs.SUSPEND_NATIVE)
        assertEquals(SessionPrefs.SUSPEND_AUTO, SessionPrefs.suspendPolicy(context, SessionService.MODE_RUN))
        assertEquals(SessionPrefs.SUSPEND_NATIVE, SessionPrefs.suspendPolicy(context, SessionService.MODE_STEAM))
    }
}
