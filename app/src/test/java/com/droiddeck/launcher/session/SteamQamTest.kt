package com.droiddeck.launcher.session

import com.droiddeck.launcher.ui.QamLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamQamTest {
    @Test fun findsTheQuickAccessPageAndKeepsOnlyItsPath() {
        val json = """[
            {"title":"SP BPM_uid0","webSocketDebuggerUrl":"ws://127.0.0.1:8080/devtools/page/AAA"},
            {"title":"QuickAccess_uid2","webSocketDebuggerUrl":"ws://127.0.0.1:8080/devtools/page/0F3A-b9"}
        ]"""
        assertEquals(SteamQam.Target(4321, "/devtools/page/0F3A-b9"), SteamQam.parseTarget(4321, json))
    }

    @Test fun readsTheTargetListByContentLengthWithoutWaitingForClose() {
        val stream = object : java.io.InputStream() {
            val bytes = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4\r\n\r\n[{}]".toByteArray()
            var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xff else error("read past the response")
        }
        assertEquals("[{}]", SteamQam.httpBody(stream))
    }

    @Test fun ignoresMissingOrForeignDebuggerUrls() {
        assertNull(SteamQam.parseTarget(1, """[{"title":"SP BPM_uid0","webSocketDebuggerUrl":"ws://127.0.0.1:1/devtools/page/A"}]"""))
        assertNull(SteamQam.parseTarget(1, """[{"title":"QuickAccess_uid2"}]"""))
        assertNull(SteamQam.parseTarget(1, """[{"title":"QuickAccess_uid2","webSocketDebuggerUrl":"ws://x/devtools/page/A?b=1"}]"""))
    }

    @Test fun holdingIsALeaseAndReleasingEndsIt() {
        assertTrue(SteamQam.script(true).contains("g.__ddQamUntil = Date.now() + ${SteamQam.LEASE_MS};"))
        assertTrue(SteamQam.script(false).contains("g.__ddQamUntil = 0;"))
    }

    @Test fun readsWhetherTheMenuIsShownOnTheTopScreen() {
        assertEquals(SteamQam.Hold(2, false), SteamQam.parseHold("[2,0]"))
        assertEquals(SteamQam.Hold(2, true), SteamQam.parseHold("[2,1]"))
        assertNull(SteamQam.parseHold("2"))
    }

    @Test fun theQuickAccessButtonTogglesTheTopScreenAndTheWideLayoutFollows() {
        val script = SteamQam.script(true)
        assertTrue(script.contains("const ours = () => held() && m.m_eOpenSideMenu === QAM && !g.__ddQamTop;"))
        assertTrue(script.contains("if (press !== g.__ddQamPress) {"))
        assertTrue(QamLayout.contentScript(true).contains("max-width: none !important"))
        assertFalse(QamLayout.contentScript(false).contains("max-width: none"))
    }

    @Test fun theMenuIsLaidOutAtThePanelsShapeOnlyWhileHeld() {
        assertTrue(SteamQam.script(true, 1.5f).contains("g.__ddQamAspect = 1.5;"))
        assertFalse(SteamQam.script(true).contains("g.__ddQamAspect ="))
        assertFalse(SteamQam.script(false, 1.5f).contains("g.__ddQamAspect ="))
    }

    @Test fun whileHeldTheMenuStaysOffTheTopScreenAndOutOfTheGamesWay() {
        val script = SteamQam.script(true)
        assertTrue(script.contains("return v === 2 && ours() ? 1 : v;"))
        assertTrue(script.contains("if (w > 0 && ours()) { x = popup.m_popup.innerWidth + 64; w = width(h) || w; }"))
        assertTrue(script.contains("nav.m_LastActiveNavTree?.m_ID?.startsWith(\"QuickAccess\")"))
        assertTrue(script.contains("if (m.m_eOpenSideMenu === QAM) m.CloseSideMenus();"))
    }

    @Test fun theMenuFillsTheHeightAndCentresAndTouchesMapBack() {
        val (scale, left) = QamLayout.fit(1280f, 832f, 400f, 640f)
        assertEquals(1.3f, scale, 0.001f)
        assertEquals((1280f - 520f) / 2f, left, 0.001f)
        val (x, y) = QamLayout.toCss(left + 130f, 260f, scale, left)
        assertEquals(100f, x, 0.001f)
        assertEquals(200f, y, 0.001f)
    }

    @Test fun aNarrowMenuCanSitLeftOrRight() {
        assertEquals(0f, QamLayout.fit(1280f, 832f, 400f, 640f, -1).second, 0.001f)
        assertEquals(1280f - 520f, QamLayout.fit(1280f, 832f, 400f, 640f, 1).second, 0.001f)
        assertEquals(0f, QamLayout.fit(1280f, 832f, 1280f, 640f, 1).second, 0.001f)
    }

    @Test fun aWideMenuFitsTheWidth() {
        val (scale, left) = QamLayout.fit(1280f, 832f, 1280f, 640f)
        assertEquals(1f, scale, 0.001f)
        assertEquals(0f, left, 0.001f)
    }
}
