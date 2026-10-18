package com.droiddeck.launcher.frontend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogoPositionTest {
    @Test fun steamsOwnJsonIsReadAndWrittenBack() {
        val steam = """{"nVersion":1,"logoPosition":{"pinnedPosition":"BottomLeft","nWidthPct":50,"nHeightPct":50}}"""
        assertEquals(LogoPosition.DEFAULT, LogoPosition.parse(steam))
        assertEquals(LogoPosition.DEFAULT, LogoPosition.parse(LogoPosition.toJson(LogoPosition.DEFAULT).toString()))
    }

    @Test fun everyPinKeepsItsSteamName() {
        for (pin in LogoPosition.Pin.values()) {
            val json = LogoPosition.toJson(LogoPosition.sized(pin, 40))
            assertEquals(pin.steam, json.getJSONObject("logoPosition").getString("pinnedPosition"))
            assertEquals(pin, LogoPosition.parse(json)?.pin)
        }
        assertEquals(setOf("BottomLeft", "UpperLeft", "CenterCenter", "UpperCenter", "BottomCenter"), LogoPosition.Pin.values().map { it.steam }.toSet())
    }

    @Test fun sizesAreKeptInsideTheRange() {
        assertEquals(LogoPosition.MAX_PCT, LogoPosition.sized(LogoPosition.Pin.CENTER, 400).widthPct)
        assertEquals(LogoPosition.MIN_PCT, LogoPosition.sized(LogoPosition.Pin.CENTER, 0).heightPct)
        val wild = """{"logoPosition":{"pinnedPosition":"CenterCenter","nWidthPct":900,"nHeightPct":-4}}"""
        assertEquals(LogoPosition.Value(LogoPosition.Pin.CENTER, LogoPosition.MAX_PCT, LogoPosition.MIN_PCT), LogoPosition.parse(wild))
    }

    @Test fun somethingThatIsNotAPositionIsNotOne() {
        assertNull(LogoPosition.parse("not json"))
        assertNull(LogoPosition.parse("{}"))
        assertNull(LogoPosition.parse("""{"logoPosition":{"pinnedPosition":"Nowhere","nWidthPct":5,"nHeightPct":5}}"""))
        assertNull(LogoPosition.parse("""{"logoPosition":{"pinnedPosition":"CenterCenter"}}"""))
    }

    @Test fun clearingKeepsForeignKeysAndEmptiesAJsonThatHeldNothingElse() {
        val mixed = LogoPosition.toJson(LogoPosition.DEFAULT, JSONObject().put("other", 1))
        val kept = LogoPosition.without(mixed)
        assertNotNull(kept)
        assertEquals(1, kept!!.getInt("other"))
        assertNull(LogoPosition.without(LogoPosition.toJson(LogoPosition.DEFAULT)))
    }
}
