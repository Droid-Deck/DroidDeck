package com.droiddeck.launcher.runtime

import android.app.Application
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RuntimeCatalogFailureTest {
    private fun serve(body: String): ServerSocket {
        val server = ServerSocket(0)
        val bytes = body.toByteArray()
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun fetch(body: String): LinuxRuntimeInstaller.Release? =
        serve(body).use { LinuxRuntimeInstaller.fetchRelease("http://127.0.0.1:${it.localPort}/linuxfs.json") }

    @Test fun anEmptyCatalogIsReported() {
        assertNull(fetch(""))
        assertEquals("empty catalog", LinuxRuntimeInstaller.catalogError())
    }

    @Test fun aMalformedCatalogIsReported() {
        assertNull(fetch("<html>captive portal</html>"))
        val error = LinuxRuntimeInstaller.catalogError().orEmpty()
        assertTrue(error, error.startsWith("invalid catalog: "))
    }

    @Test fun aCatalogMissingFieldsIsReported() {
        assertNull(fetch("""{"version":"1","url":"http://127.0.0.1/rootfs.tar.zst"}"""))
        assertEquals("catalog lacks version, url or sha256", LinuxRuntimeInstaller.catalogError())
    }

    @Test fun anUnreachableCatalogKeepsTheDownloadReason() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(LinuxRuntimeInstaller.fetchRelease("http://127.0.0.1:$port/linuxfs.json"))
        val error = LinuxRuntimeInstaller.catalogError().orEmpty()
        assertTrue(error, error.startsWith("127.0.0.1: ConnectException"))
    }

    @Test fun aValidCatalogClearsTheEarlierError() {
        assertNull(fetch(""))
        val release = fetch("""{"version":"2","url":"http://127.0.0.1/rootfs.tar.zst","sha256":"${"a".repeat(64)}","size":5}""")
        assertNotNull(release)
        assertEquals("2", release!!.version)
        assertNull(LinuxRuntimeInstaller.catalogError())
    }
}
