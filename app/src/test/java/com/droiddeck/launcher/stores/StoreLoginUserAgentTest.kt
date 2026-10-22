package com.droiddeck.launcher.stores

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreLoginUserAgentTest {
    @Test fun theDevicesWebViewAgentIsSentAsChrome() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; NP06J) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Mobile Safari/537.36",
            StoreLoginActivity.chromeUserAgent(
                "Mozilla/5.0 (Linux; Android 16; NP06J Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36",
            ),
        )
    }

    @Test fun noWebViewAgentFallsBackToChromeWithoutAnotherDevicesModel() {
        for (agent in listOf(null, "", "Dalvik/2.1.0 (Linux; U; Android 16)")) {
            val ua = StoreLoginActivity.chromeUserAgent(agent)
            assertTrue(ua, "Chrome/" in ua)
            assertFalse(ua, "SM-S918B" in ua)
            assertTrue(ua, "Android ${Build.VERSION.RELEASE}; ${Build.MODEL})" in ua)
        }
    }
}
