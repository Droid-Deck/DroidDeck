package com.droiddeck.launcher.session

import com.droiddeck.launcher.ui.QamFit
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
        assertTrue(SteamQam.script(true, owner = 7).contains(SteamQam.leaseScript(true, 7)))
        assertTrue(SteamQam.script(false, owner = 7).contains(SteamQam.leaseScript(false, 7)))
        assertTrue(SteamQam.newOwner() < SteamQam.newOwner())
    }

    /** Runs the lease steps in Node, in order, and returns which owner holds a live lease afterwards (0 for none). */
    private fun leaseAfter(vararg steps: Pair<Boolean, Long>): Long? {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        org.junit.Assume.assumeTrue("node is not installed", node)
        val program = "const g = {};\n" + steps.joinToString("\n") { (on, owner) -> if (owner == EXPIRE) "g.__ddQamUntil = Date.now() - 1;" else SteamQam.leaseScript(on, owner) } +
            "\nprocess.stdout.write(String(g.__ddQamUntil > Date.now() ? g.__ddQamOwner : 0));"
        val p = ProcessBuilder("node", "-e", program).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals(out, 0, p.waitFor())
        return out.toLongOrNull()
    }

    @Test fun aLateRequestFromAnOldPanelLeavesItsReplacementsLease() {
        // The replacement takes the lease, then the old panel's in-flight hold lands, then its release.
        assertEquals(2L, leaseAfter(true to 2L, true to 1L, false to 1L))
        // The old panel's release alone does not end the replacement's lease either.
        assertEquals(2L, leaseAfter(true to 1L, true to 2L, false to 1L))
        // A panel releases its own lease.
        assertEquals(0L, leaseAfter(true to 2L, false to 2L))
        // With no live lease, any panel can take it: after a release, or once a newer panel's lease has run out.
        assertEquals(1L, leaseAfter(true to 2L, false to 2L, true to 1L))
        assertEquals(1L, leaseAfter(true to 2L, false to EXPIRE, true to 1L))
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

    @Test fun aSteamDialogOverTheGameGetsTheInput() {
        val script = SteamQam.script(true)
        assertTrue(script.contains("t.m_ID?.startsWith(\"ModalDialogOverlay\") && t.m_Root?.m_element?.isConnected"))
        assertTrue(script.contains("if (dialog && !g.__ddQamTop) {"))
        assertTrue(script.indexOf("if (dialog && !g.__ddQamTop)") < script.indexOf("g.__ddQamDecky = decky;"))
    }

    @Test fun theRelayAnswersWithItsPortAndSecret() {
        assertEquals(41234 to "0123456789abcdef0123456789abcdef", SteamQam.parseRelay("41234 0123456789abcdef0123456789abcdef\n"))
        assertNull(SteamQam.parseRelay("41234"))
        assertNull(SteamQam.parseRelay("41234 not-a-secret"))
        assertNull(SteamQam.parseRelay(null))
    }

    @Test fun whileHeldTheMenuStaysOffTheTopScreenAndOutOfTheGamesWay() {
        val script = SteamQam.script(true)
        assertTrue(script.contains("return without >= 2 ? v : 1;"))
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

    @Test fun theLayoutButtonCyclesFullLeftCenterRight() {
        assertEquals(listOf(QamFit.LEFT, QamFit.CENTER, QamFit.RIGHT, QamFit.FULL), QamFit.entries.map { it.next })
        assertEquals(QamFit.FULL, QamFit.from(null))
        assertEquals(QamFit.RIGHT, QamFit.from("right"))
        assertEquals(QamFit.FULL, QamFit.from("sideways"))
    }

    @Test fun aWideMenuFitsTheWidth() {
        val (scale, left) = QamLayout.fit(1280f, 832f, 1280f, 640f)
        assertEquals(1f, scale, 0.001f)
        assertEquals(0f, left, 0.001f)
    }

    private companion object {
        /** A lease step that lets the current lease run out. */
        const val EXPIRE = -1L
    }
}
