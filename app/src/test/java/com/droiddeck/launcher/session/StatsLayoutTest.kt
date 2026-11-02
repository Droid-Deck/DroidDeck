package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsLayoutTest {
    @Test fun unknownIdsFallBackToTheDefaults() {
        assertEquals(StatsStrip.PANEL, StatsStrip.from(null))
        assertEquals(StatsStrip.RINGS, StatsStrip.from("rings"))
        assertEquals(StatsDash.AUTO, StatsDash.from("gone"))
        assertEquals(StatsDash.TRACE, StatsDash.from("trace"))
    }

    @Test fun tappingTheStripCyclesThroughEveryStyle() {
        assertEquals(StatsStrip.TILES, StatsStrip.PANEL.next())
        assertEquals(StatsStrip.RINGS, StatsStrip.TILES.next())
        assertEquals(StatsStrip.PANEL, StatsStrip.RINGS.next())
    }

    @Test fun autoPicksTheDashByTheGamesFormat() {
        assertEquals(StatsDash.SPEEDOMETER, StatsDash.AUTO.resolve("Codemasters"))
        assertEquals(StatsDash.CIRCUIT, StatsDash.AUTO.resolve("Forza"))
        assertEquals(StatsDash.DIALS, StatsDash.AUTO.resolve("OutGauge"))
        assertEquals(StatsDash.TRACE, StatsDash.TRACE.resolve("Forza"))
    }

    @Test fun tappingTheDashMovesOnFromTheOneOnScreen() {
        assertEquals(StatsDash.CIRCUIT, StatsDash.tapped(StatsDash.SPEEDOMETER))
        assertEquals(StatsDash.SPEEDOMETER, StatsDash.tapped(StatsDash.GLANCE))
        assertEquals(StatsDash.SPEEDOMETER, StatsDash.tapped(StatsDash.AUTO))
    }

    @Test fun automaticSpeedUnitsFollowTheRegion() {
        assertEquals(SpeedUnit.MPH, SpeedUnit.AUTO.resolve("US"))
        assertEquals(SpeedUnit.MPH, SpeedUnit.AUTO.resolve("gb"))
        assertEquals(SpeedUnit.KMH, SpeedUnit.AUTO.resolve("DE"))
        assertEquals(SpeedUnit.KMH, SpeedUnit.AUTO.resolve(""))
        assertEquals(SpeedUnit.KMH, SpeedUnit.KMH.resolve("US"))
        assertEquals(SpeedUnit.MPH, SpeedUnit.MPH.resolve("DE"))
    }

    @Test fun speedUnitsCycleAndConvert() {
        assertEquals(SpeedUnit.AUTO, SpeedUnit.from(null))
        assertEquals(SpeedUnit.MPH, SpeedUnit.from("mph"))
        assertEquals(SpeedUnit.KMH, SpeedUnit.AUTO.next())
        assertEquals(SpeedUnit.MPH, SpeedUnit.KMH.next())
        assertEquals(SpeedUnit.AUTO, SpeedUnit.MPH.next())
        assertEquals(62.1f, 100f * SpeedUnit.MPH.perKmh, 0.05f)
        assertEquals(100f, 100f * SpeedUnit.KMH.perKmh, 0f)
    }
}
