package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Store lines reach the Downloads log and logcat; nothing in them may carry a token. */
class StoreLogTest {
    @Test fun gogSecureLinkKeepsOnlyTheHost() {
        val line = "gog: window 32 in_flight 3 https://gog-cdn-fastly.gog.com/token=nva=1791500000~dirs=/content-system/v2/store/1207658924~token=0a1b2c3d4e5f/content-system/v2/store/1207658924/ab/cd/abcdef 12.1 MB/s"
        val out = StoreLog.redactLine(line)
        assertEquals("gog: window 32 in_flight 3 https://gog-cdn-fastly.gog.com/… 12.1 MB/s", out)
        assertFalse(out.contains("0a1b2c3d4e5f"))
    }

    @Test fun epicAkamaiUrlLosesItsQueryAndPath() {
        val line = "epic: CDN https://epicgames-download1.akamaized.net/Builds/Org/o-abc/xyz/default/ChunksV4/12/ABCD.chunk?__token__=exp=1791500000~acl=/*~hmac=deadbeef&f_token=xyz done"
        val out = StoreLog.redactLine(line)
        assertEquals("epic: CDN https://epicgames-download1.akamaized.net/Builds/… done", out)
    }

    @Test fun tokensOutsideUrlsAndHeadersAreBlanked() {
        val out = StoreLog.redactLine("refresh access_token=abc.def refresh_token: 'xyz' code=AUTH123 hdnts=exp~hmac __token__=q")
        assertEquals("refresh access_token=… refresh_token: '…' code=… hdnts=… __token__=…", out)
        assertEquals("Authorization: …", StoreLog.redactLine("Authorization: Bearer eyJhbGciOi"))
        assertEquals("""{"access_token":"…","expires_in":7200}""", StoreLog.redactLine("""{"access_token":"eyJ.abc","expires_in":7200}"""))
        // Ordinary numbers stay: "HTTP code 403" has no separator to read as a secret.
        assertEquals("HTTP code 403 from https://api.gog.com/products/…", StoreLog.redactLine("HTTP code 403 from https://user:pw@api.gog.com/products/1?expand=x"))
    }
}
