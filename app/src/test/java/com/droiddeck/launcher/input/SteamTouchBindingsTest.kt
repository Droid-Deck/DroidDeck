package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SteamTouchBindingsTest {
    private val config = """
"controller_mappings"
{
	"version"		"3"
	"controller_type"		"controller_mobile_touch"
	"group"
	{
		"id"		"0"
		"mode"		"four_buttons"
		"inputs"
		{
			"button_a"
			{
				"activators"
				{
					"Full_Press"
					{
						"bindings"
						{
							"binding"		"xinput_button A, , "
						}
					}
				}
			}
			"button_b"
			{
				"activators"
				{
					"Full_Press"
					{
						"bindings"
						{
							"binding"		"key_press ESCAPE, Pause, ghost_050_menu_0020.png, #FFFFFF #AD0000"
						}
					}
				}
			}
		}
	}
	"group"
	{
		"id"		"9"
		"mode"		"dpad"
		"inputs"
		{
			"dpad_north"
			{
				"activators"
				{
					"Full_Press"
					{
						"bindings"
						{
							"binding"		"xinput_button DPAD_UP, , "
						}
					}
				}
			}
		}
	}
	"preset"
	{
		"id"		"0"
		"name"		"Default"
		"group_source_bindings"
		{
			"0"		"button_diamond active"
			"9"		"dpad active"
		}
	}
}
"""

    private val mappings = KeyValues.parse(config).child("controller_mappings")!!

    @Test
    fun parsesAndComposesSteamsFormat() {
        val b = SteamTouchBindings.parse("key_press ESCAPE, Pause, ghost_050_menu_0020.png, #FFFFFF #AD0000")
        assertEquals("key_press ESCAPE", b.action)
        assertEquals("Pause", b.label)
        assertEquals("ghost_050_menu_0020.png", b.icon)
        assertEquals("#FFFFFF", b.foreground)
        assertEquals("#AD0000", b.background)
        assertEquals("key_press ESCAPE, Pause, ghost_050_menu_0020.png, #FFFFFF #AD0000", b.compose())
        assertEquals("xinput_button A, , ", SteamTouchBindings.parse("xinput_button A, , ").compose())
    }

    @Test
    fun findsEachControlsBinding() {
        val b = SteamTouchBindings.refsFor(mappings, SteamTouchConfig.B, 0).single()!!
        assertEquals(SteamTouchBindings.Ref("0", "button_b"), b)
        assertEquals("ghost_050_menu_0020.png", SteamTouchBindings.bindingAt(mappings, b)!!.icon)
        val dpad = SteamTouchBindings.refsFor(mappings, SteamTouchConfig.DPAD, 0)
        assertEquals(4, dpad.size)
        assertEquals("xinput_button DPAD_UP", SteamTouchBindings.bindingAt(mappings, dpad[0]!!)!!.action)
        assertNull(SteamTouchBindings.refsFor(mappings, SteamTouchConfig.BUMPER_LEFT, 0).single())
        assertEquals("Esc", SteamTouchBindings.glyph(SteamTouchBindings.parse("key_press ESCAPE, , ")))
    }

    @Test
    fun rewritesOnlyThatBinding() {
        val ref = SteamTouchBindings.Ref("0", "button_a")
        val out = SteamTouchBindings.rewrite(config, ref) { it.copy(label = "Jump", icon = "ghost_045_move_0400.png", foreground = "#232323", background = "#E4E4E4") }
        assertNotNull(out)
        assertEquals(config.replace("\"xinput_button A, , \"", "\"xinput_button A, Jump, ghost_045_move_0400.png, #232323 #E4E4E4\""), out)
        val reparsed = KeyValues.parse(out!!).child("controller_mappings")!!
        assertEquals("Jump", SteamTouchBindings.bindingAt(reparsed, ref)!!.label)
        assertNull(SteamTouchBindings.rewrite(config, SteamTouchBindings.Ref("5", "button_a")) { it })
    }
}
