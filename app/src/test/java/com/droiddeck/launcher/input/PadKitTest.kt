package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PadKitTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @After fun reset() { context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun eachVirtualControllerGetsItsOwnKit() {
        assertEquals(PadKit.STEAM_DECK, PadKit.of(SessionPrefs.CONTROLLER_DECK))
        assertEquals(PadKit.STEAM_CONTROLLER, PadKit.of(SessionPrefs.CONTROLLER_STEAM))
        assertEquals(PadKit.XBOX, PadKit.of(SessionPrefs.CONTROLLER_XBOX360))
        assertEquals(PadKit.DUALSHOCK, PadKit.of(SessionPrefs.CONTROLLER_DS4))
        assertEquals(PadKit.DUALSENSE, PadKit.of(SessionPrefs.CONTROLLER_DUALSENSE))
        assertEquals(PadKit.STEAM_DECK, PadKit.of(SessionPrefs.CONTROLLER_NONE))
        assertEquals(PadKit.STEAM_DECK, PadKit.of("switchpro"))
    }

    @Test fun theKitFollowsTheChosenVirtualController() {
        assertEquals(PadKit.STEAM_DECK, PadKit.current(context))
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DUALSENSE)
        assertEquals(PadKit.DUALSENSE, PadKit.current(context))
    }

    @Test fun steamAndPlayStationKitsAreGroupedForTheirLayouts() {
        assertEquals(setOf(PadKit.STEAM_DECK, PadKit.STEAM_CONTROLLER), PadKit.entries.filter { it.steam }.toSet())
        assertEquals(setOf(PadKit.DUALSHOCK, PadKit.DUALSENSE), PadKit.entries.filter { it.playStation }.toSet())
        assertFalse(PadKit.XBOX.steam || PadKit.XBOX.playStation)
    }

    @Test fun faceGlyphsMatchTheRealControllers() {
        assertEquals(FaceGlyphs.MONO_LETTERS, PadKit.STEAM_DECK.face)
        assertEquals(FaceGlyphs.MONO_LETTERS, PadKit.STEAM_CONTROLLER.face)
        assertEquals(FaceGlyphs.COLOR_LETTERS, PadKit.XBOX.face)
        assertEquals(FaceGlyphs.COLOR_SYMBOLS, PadKit.DUALSHOCK.face)
        assertEquals(FaceGlyphs.MONO_SYMBOLS, PadKit.DUALSENSE.face)
        assertEquals(DpadStyle.ARROWS, PadKit.DUALSHOCK.dpad)
        assertEquals(DpadStyle.ARROWS, PadKit.DUALSENSE.dpad)
    }

    @Test fun onlyXboxNamesItsShouldersBumpersAndTriggers() {
        val shoulders = listOf(InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT)
        assertEquals(listOf("LB", "RB", "LT", "RT"), shoulders.map(PadKit.XBOX::label))
        for (kit in PadKit.entries - PadKit.XBOX) assertEquals(kit.name, listOf("L1", "R1", "L2", "R2"), shoulders.map(kit::label))
        for (kit in PadKit.entries) assertEquals(InputControl.A.label, kit.label(InputControl.A))
    }

    @Test fun playStationFaceButtonsAreNamedByTheirSymbols() {
        val face = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
        for (kit in listOf(PadKit.DUALSHOCK, PadKit.DUALSENSE)) {
            assertEquals(kit.name, listOf("Cross", "Circle", "Square", "Triangle"), face.map(kit::name))
            assertEquals("L1", kit.name(InputControl.LB))
            val elements = KitLayout.elements(context, kit, 1920, 1080).associateBy { it.id }
            assertEquals("Triangle", elements.getValue("legacy:y").name)
            assertEquals("Cross", elements.getValue("legacy:a").name)
        }
        for (kit in listOf(PadKit.STEAM_DECK, PadKit.STEAM_CONTROLLER, PadKit.XBOX)) {
            assertEquals(kit.name, face.map { it.label }, face.map(kit::name))
            assertEquals(kit.label(InputControl.LT), kit.name(InputControl.LT))
            assertEquals(InputControl.Y.label, KitLayout.elements(context, kit, 1920, 1080).single { it.id == "legacy:y" }.name)
        }
    }
}
