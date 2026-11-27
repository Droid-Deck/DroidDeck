package com.droiddeck.launcher.session

import com.droiddeck.launcher.ui.QamLayout
import org.junit.Assert.assertEquals
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

    @Test fun ignoresMissingOrForeignDebuggerUrls() {
        assertNull(SteamQam.parseTarget(1, """[{"title":"SP BPM_uid0","webSocketDebuggerUrl":"ws://127.0.0.1:1/devtools/page/A"}]"""))
        assertNull(SteamQam.parseTarget(1, """[{"title":"QuickAccess_uid2"}]"""))
        assertNull(SteamQam.parseTarget(1, """[{"title":"QuickAccess_uid2","webSocketDebuggerUrl":"ws://x/devtools/page/A?b=1"}]"""))
    }

    @Test fun holdingCapsCompositionOnlyWhileTheMenuIsOursAndReleasingClosesIt() {
        val on = SteamQam.script(true)
        val off = SteamQam.script(false)
        assertTrue(on.contains("if (true)"))
        assertTrue(off.contains("if (false)"))
        assertTrue(on.contains("globalThis.__ddQam && v === 2 && m.m_eOpenSideMenu === QAM ? 1 : v"))
        assertTrue(on.contains("startsWith(\"QuickAccess\")"))
        assertTrue(off.contains("m.CloseSideMenus()"))
    }

    @Test fun theMenuFillsTheHeightAndCentresAndTouchesMapBack() {
        val (scale, left) = QamLayout.fit(1280f, 832f, 400f, 640f)
        assertEquals(1.3f, scale, 0.001f)
        assertEquals((1280f - 520f) / 2f, left, 0.001f)
        val (x, y) = QamLayout.toCss(left + 130f, 260f, scale, left)
        assertEquals(100f, x, 0.001f)
        assertEquals(200f, y, 0.001f)
    }

    @Test fun aWideMenuFitsTheWidth() {
        val (scale, left) = QamLayout.fit(1280f, 832f, 1280f, 640f)
        assertEquals(1f, scale, 0.001f)
        assertEquals(0f, left, 0.001f)
    }
}
