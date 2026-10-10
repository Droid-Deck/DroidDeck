package com.droiddeck.launcher.stores.gog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every CDN GOG's secure link offers is used, not only the first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogCdnTest {
    private val answer = """{"urls":[
        {"endpoint_name":"fastly","url_format":"https:\/\/gog-cdn-fastly.gog.com\/token=nva={expires_at}~token={token}/{path}","parameters":{"expires_at":1,"token":"t"}},
        {"endpoint_name":"akamai","url_format":"{base_url}/{path}","parameters":{"base_url":"https://gog-cdn-akamai.gog.com/s"}},
        {"endpoint_name":"lumen","url_format":"https://cdn.gog.com/{path}","parameters":{}}
    ]}"""

    @Test fun allHostsAreParsedInOrderAndTakenInTurn() {
        val all = GogDownloadManager.parseCdnUrls(answer)
        assertEquals(3, all.size)
        assertEquals("https://gog-cdn-fastly.gog.com/token=nva=1~token=t", all[0])
        assertEquals("https://gog-cdn-akamai.gog.com/s", all[1])
        assertEquals("https://cdn.gog.com", all[2])
        val set = all.joinToString("\n")
        assertEquals(all[0], GogDownloadManager.firstCdn(set))
        val picks = (0 until 6).map { GogDownloadManager.pickCdn(set) }.toSet()
        assertEquals(all.toSet(), picks)
        assertEquals(0, GogDownloadManager.parseCdnUrls("not json").size)
    }
}
