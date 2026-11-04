package com.droiddeck.launcher.core

import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSourceProxyTest {
    @Test fun hostAndPortBecomeAnHttpProxy() {
        val proxy = DownloadSource.proxyOf("127.0.0.1:9910")
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        val address = proxy?.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(9910, address.port)
    }

    @Test fun missingOrInvalidPortIsIgnored() {
        assertNull(DownloadSource.proxyOf(""))
        assertNull(DownloadSource.proxyOf("127.0.0.1"))
        assertNull(DownloadSource.proxyOf("127.0.0.1:"))
        assertNull(DownloadSource.proxyOf("127.0.0.1:notaport"))
    }

    @Test fun normalizationAcceptsSchemeAndTrailingSlash() {
        assertEquals("127.0.0.1:9910", DownloadSource.normalizeProxy(" http://127.0.0.1:9910/ "))
        assertEquals("192.168.1.2:8080", DownloadSource.normalizeProxy("192.168.1.2:8080"))
    }

    @Test fun invalidInputDisablesTheProxy() {
        assertEquals("", DownloadSource.normalizeProxy(""))
        assertEquals("", DownloadSource.normalizeProxy("127.0.0.1"))
        assertEquals("", DownloadSource.normalizeProxy("127.0.0.1:70000"))
        assertEquals("", DownloadSource.normalizeProxy("http://127.0.0.1:notaport"))
    }
}
