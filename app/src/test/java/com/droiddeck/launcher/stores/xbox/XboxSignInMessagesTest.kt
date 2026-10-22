package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class XboxSignInMessagesTest {
    @Test fun ownCheckMessagesAreShownAndOtherTextIsNot() {
        assertEquals("the Xbox helper stopped with exit code 3", XboxLoginActivity.failure(IllegalStateException("the Xbox helper stopped with exit code 3")))
        assertEquals("IllegalStateException", XboxLoginActivity.failure(IllegalStateException("Check failed.")))
        assertEquals("JSONException", XboxLoginActivity.failure(org.json.JSONException("token=secret-value")))
        assertEquals("IllegalArgumentException", XboxLoginActivity.failure(IllegalArgumentException("secret-value")))
    }

    @Test fun gamertagMustBeShortPrintableText() {
        assertTrue(XboxGamertag.valid("Some Gamertag"))
        assertFalse(XboxGamertag.valid(""))
        assertFalse(XboxGamertag.valid("a\nb"))
        assertFalse(XboxGamertag.valid("x".repeat(65)))
    }

    @Test fun gamertagRefreshIsOncePerAccountGeneration() {
        val first = "account-generation-one-${System.nanoTime()}"
        val second = "account-generation-two-${System.nanoTime()}"
        assertTrue(XboxGamertag.shouldTryRefresh(first))
        assertFalse(XboxGamertag.shouldTryRefresh(first))
        assertTrue(XboxGamertag.shouldTryRefresh(second))
        assertFalse(XboxGamertag.shouldTryRefresh(second))
    }
}
