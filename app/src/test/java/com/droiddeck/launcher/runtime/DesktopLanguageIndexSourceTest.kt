package com.droiddeck.launcher.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.Downloader
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DesktopLanguageIndexSourceTest {
    private val base = "https://github.com/Droid-Deck/desktop/releases/download/t/"
    private val tunnels = CopyOnWriteArrayList<String>()
    private lateinit var context: Context
    private lateinit var proxy: ServerSocket

    @Before fun setUp() {
        proxy = ServerSocket(0)
        Thread {
            try {
                while (!proxy.isClosed) {
                    proxy.accept().use { socket ->
                        val line = socket.getInputStream().bufferedReader().readLine() ?: return@use
                        tunnels += line.removePrefix("CONNECT ").substringBefore(':')
                        socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
        context = ApplicationProvider.getApplicationContext()
        DownloadSource.init(context)
        DownloadSource.setMode(context, DownloadSource.MODE_MIRROR)
        DownloadSource.setProxy(context, "127.0.0.1:${proxy.localPort}")
    }

    @After fun tearDown() {
        DownloadSource.setMode(context, DownloadSource.MODE_AUTO)
        DownloadSource.setProxy(context, "")
        proxy.close()
    }

    @Test fun mirrorModeWouldOtherwiseSendTheIndexThroughAMirror() {
        Downloader.downloadString(base + "desktop-kde.i18n.txt")
        assertTrue(tunnels.toString(), tunnels.any { host -> DownloadSource.MIRRORS.any { it.host == host } })
    }

    @Test fun indexIsFetchedOnlyFromGithubInMirrorMode() {
        assertNull(DesktopLanguage.fetchIndex(base))
        assertEquals(listOf("github.com"), tunnels.toList())
    }
}
