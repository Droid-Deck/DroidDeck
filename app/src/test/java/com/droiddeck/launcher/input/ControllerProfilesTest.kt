package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-controller profiles: what they match, what a file may contain, and what a bad file does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ControllerProfilesTest {
    private val json = """
        {"profiles": [
          {"name": "By name", "nameContains": "mcon", "deadZone": 0.05},
          {"name": "By ids", "vendor": "0x045e", "product": 654, "deadZone": 0.2},
          {"name": "Both", "vendor": "0x045e", "product": "0x028e", "nameContains": "pad",
           "keys": {"BUTTON_MODE": "KEYCODE_BUTTON_SELECT", "96": "97"}}
        ]}
    """.trimIndent()

    @Test fun readsEveryFieldOfAProfile() {
        val both = ControllerProfiles.parse(json).first { it.name == "Both" }
        assertEquals(0x045e, both.vendorId)
        assertEquals(0x028e, both.productId)
        assertEquals("pad", both.nameContains)
        assertNull(both.deadZone)
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, both.keyMap[KeyEvent.KEYCODE_BUTTON_MODE])
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, both.keyMap[KeyEvent.KEYCODE_BUTTON_A])
    }

    @Test fun theMostSpecificProfileWins() {
        val list = ControllerProfiles.parse(json)
        assertEquals("Both", ControllerProfiles.find(list, 0x045e, 0x028e, "Xbox Pad")?.name)
        assertEquals("By ids", ControllerProfiles.find(list, 0x045e, 654, "Xbox Controller")?.name)
        assertEquals("By name", ControllerProfiles.find(list, 1, 2, "MCON Gamepad")?.name)
        assertNull(ControllerProfiles.find(list, 1, 2, "Some other pad"))
    }

    @Test fun aProfileThatNamesNothingMatchesNothing() {
        val list = ControllerProfiles.parse("""[{"name": "Everything", "deadZone": 0.0}]""")
        assertEquals(1, list.size)
        assertNull(ControllerProfiles.find(list, 0x045e, 0x028e, "Xbox Pad"))
    }

    @Test fun anIdNeedsBothHalvesToAgree() {
        val list = ControllerProfiles.parse("""[{"name": "P", "vendor": "0x045e", "product": "0x028e"}]""")
        assertNull(ControllerProfiles.find(list, 0x045e, 0x0001, "x"))
        assertNull(ControllerProfiles.find(list, 0x0001, 0x028e, "x"))
    }

    @Test fun deadZoneIsKeptInRange() {
        val list = ControllerProfiles.parse("""[{"name":"a","nameContains":"a","deadZone":5},{"name":"b","nameContains":"b","deadZone":-1}]""")
        assertEquals(ControllerProfiles.MAX_DEAD_ZONE, list[0].deadZone!!, 0f)
        assertEquals(0f, list[1].deadZone!!, 0f)
    }

    @Test fun badEntriesAreSkippedAndBadFilesAreEmpty() {
        val list = ControllerProfiles.parse("""{"profiles": [{"nameContains": "no name"}, 3, {"name": "ok", "nameContains": "x", "keys": {"NOT_A_KEY": "A"}}]}""")
        assertEquals(listOf("ok"), list.map { it.name })
        assertTrue(list[0].keyMap.isEmpty())
        assertTrue(ControllerProfiles.parse("not json").isEmpty())
        assertTrue(ControllerProfiles.parse("").isEmpty())
        assertTrue(ControllerProfiles.parse("""{"other": 1}""").isEmpty())
    }

    @Test fun resolvedControllerOnlyRemapsWhatItIsTold() {
        val resolved = ResolvedController(null, 0.1f, mapOf(KeyEvent.KEYCODE_BUTTON_MODE to KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, resolved.key(KeyEvent.KEYCODE_BUTTON_MODE))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, resolved.key(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun theDeadZoneIsACircleNotACross() {
        // Held well over to the right, a small push up still counts.
        assertEquals(false, ControllerProfiles.inDeadZone(0.5f, 0.05f, 0.12f))
        assertEquals(true, ControllerProfiles.inDeadZone(0.05f, 0.05f, 0.12f))
        assertEquals(true, ControllerProfiles.inDeadZone(0.11f, 0f, 0.12f))
        assertEquals(false, ControllerProfiles.inDeadZone(0.09f, 0.09f, 0.12f))
        assertEquals(false, ControllerProfiles.inDeadZone(0f, 0f, 0f))
    }

    @Test fun whatIsWrittenCanBeReadBack() {
        val original = ControllerProfile("MCON", 0x1234, 0x5678, "mcon", 0.06f, mapOf(KeyEvent.KEYCODE_HOME to KeyEvent.KEYCODE_BUTTON_MODE))
        val back = ControllerProfiles.parse(ControllerProfiles.toJson(listOf(original))).single()
        assertEquals("MCON", back.name)
        assertEquals(0x1234, back.vendorId)
        assertEquals(0x5678, back.productId)
        assertEquals("mcon", back.nameContains)
        assertEquals(0.06f, back.deadZone!!, 0.0001f)
        assertEquals(original.keyMap, back.keyMap)
    }
}
