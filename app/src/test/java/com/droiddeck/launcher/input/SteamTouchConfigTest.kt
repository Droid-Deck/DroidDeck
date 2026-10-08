package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamTouchConfigTest {
    // A layout DroidDeck saved for Geometry Wars on the Thor, as Steam keeps it.
    private val saved = "0aec0310012218080010011d0000703d255555d53d2d0000803f350000803f2218080110011d6666fe3e255555d53d2d0000803f350000803f2218080210011dba490c3e255c8f423f2d0000803f350000803f2218080410011d9a99593f255c8f423f2d0000803f350000803f2218080510011d8fc2353f25ae47613f2d0000803f350000803f2218080610011d2b87163e25d7a3f03e2d0000803f350000803f2218080710011dba49003f25f958053f2d293ab23f35293ab23f2218080810011d9a99683f25d227dd3e2d0000803f350000803f2218080910011d9a99483f25d227dd3e2d0000803f350000803f2218080a10011d9a99583f254444a43e2d0000803f350000803f2218080b10011d6666ce3e255555d53d2d0000803f350000803f2218080c10011d3333173f255555d53d2d0000803f350000803f2218080d10011d0ad7233e2585eb513e2d0000803f350000803f2218080e10011d3d0a573f2585eb513e2d0000803f350000803f2218080f10011d8fc2753d2585eb513e2d0000803f350000803f2218081010011dd7a3703f2585eb513e2d0000803f350000803f2218081c10011d0000713f255555d53d2d0000803f350000803f2218080310001de17a943e25ae47613f2d0000803f350000803f2a140d0000803f150000803f1d0000803f25cdcccc3e1002"

    @Test
    fun decodesSavedLayout() {
        val layouts = SteamTouchConfig.decodeLayouts(SteamTouchConfig.hexToBytes(saved))
        assertEquals(1, layouts.layouts.size)
        val layout = layouts.forActionSet(1)!!
        val a = layout.elements.first { it.type == SteamTouchConfig.A }
        assertEquals(0.501f, a.x, 0.002f)
        assertEquals(1.39f, a.xScale, 0.01f)
        assertFalse(layout.elements.first { it.type == SteamTouchConfig.JOYSTICK_LEFT_BUTTON }.visible)
    }

    @Test
    fun roundTrips() {
        val bytes = SteamTouchConfig.hexToBytes(saved)
        assertEquals(saved, SteamTouchConfig.bytesToHex(SteamTouchConfig.encodeLayouts(SteamTouchConfig.decodeLayouts(bytes))))
    }

    @Test
    fun appliesLayoutToElements() {
        val layouts = SteamTouchConfig.decodeLayouts(SteamTouchConfig.hexToBytes(saved))
        val all = (0..31).toSet()
        val config = SteamTouchConfig.Config(null, null, layouts, mapOf(0 to all))
        val shown = SteamTouchConfig.elementsFor(config, 0, emptyList())
        val a = shown.first { it.type == SteamTouchConfig.A }
        assertEquals(0.501f, a.x, 0.002f)
        assertTrue(shown.none { it.type == SteamTouchConfig.JOYSTICK_LEFT_BUTTON })
    }

    @Test
    fun optionsRoundTripAndKeepTheRest() {
        val layouts = SteamTouchConfig.decodeLayouts(SteamTouchConfig.hexToBytes(saved))
        assertEquals(SteamTouchConfig.INPUT_CONTROLLER, layouts.options.inputMode)
        val next = SteamTouchConfig.Options(SteamTouchConfig.INPUT_BOTH, SteamTouchConfig.MOUSE_RELATIVE, 1.75f)
        val rest = next.encodeInto(layouts.rest)
        assertEquals(next, SteamTouchConfig.Options.decode(rest))
        val again = SteamTouchConfig.decodeLayouts(SteamTouchConfig.encodeLayouts(SteamTouchConfig.Layouts(layouts.layouts, rest)))
        assertEquals(next, again.options)
        assertEquals(layouts.layouts.size, again.layouts.size)
    }

    @Test
    fun listsActionSets() {
        val kv = KeyValues.parse("""
"controller_mappings"
{
	"actions"
	{
		"Default" { "title" "Desktop" }
		"Preset_1000001" { "title" "Gamepad" }
	}
	"preset" { "id" "0" "name" "Default" }
	"preset" { "id" "1" "name" "Preset_1000001" }
}
""").child("controller_mappings")
        val config = SteamTouchConfig.Config(null, null, null, emptyMap(), kv)
        assertEquals(listOf(1 to "Desktop", 2 to "Gamepad"), SteamTouchConfig.actionSets(config))
    }
}
