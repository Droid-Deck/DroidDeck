package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrModelTestConfigTest {
    @Test fun localServerAndRequestAreAccepted() {
        val config = XrModelTestConfig.parse("""{"baseUrl":"http://127.0.0.1:18765/","request":"round-2"}""")
        assertEquals("http://127.0.0.1:18765", config.baseUrl)
        assertEquals("round-2", config.request)
    }

    @Test fun externalHostsAndAmbiguousUrlsAreRejected() {
        for (url in listOf("http://example.com:18765", "http://127.0.0.1:18765/other",
            "http://user@127.0.0.1:18765", "http://127.0.0.1:18765?next=remote",
            "http://127.0.0.1:18765#other", "http://127.0.0.1", "http://127.0.0.1:65536")) {
            assertThrows(IllegalArgumentException::class.java) {
                XrModelTestConfig.parse("""{"baseUrl":"$url","request":"round-2"}""")
            }
        }
    }

    @Test fun requestCannotEscapeTheTestCache() {
        for (request in listOf("", "../pico-4", "round/2", "x".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) {
                XrModelTestConfig.parse("""{"baseUrl":"http://127.0.0.1:18765","request":"$request"}""")
            }
        }
    }
}
