package com.droiddeck.launcher.input

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OnScreenControlPrefsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val session get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val library get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)

    @Before fun reset() {
        for (name in listOf("session", "game-controls", "input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun oldShowModesMapOntoAutoAlwaysAndNever() {
        assertEquals(SessionPrefs.OSC_AUTO, SessionPrefs.oscMode(context))
        for ((saved, mode) in listOf(SessionPrefs.OSC_AUTO to SessionPrefs.OSC_AUTO, SessionPrefs.OSC_ALWAYS to SessionPrefs.OSC_ALWAYS,
            SessionPrefs.OSC_NEVER to SessionPrefs.OSC_NEVER, SessionPrefs.OSC_STEAM_QAM to SessionPrefs.OSC_ALWAYS,
            SessionPrefs.OSC_STEAM_TOUCH to SessionPrefs.OSC_AUTO, "buttons" to SessionPrefs.OSC_AUTO)) {
            session.edit().putString("osc", saved).commit()
            assertEquals(saved, mode, SessionPrefs.oscMode(context))
        }
    }

    @Test fun theOldSteamTouchModeBecomesTheSteamLayoutUntilChanged() {
        assertFalse(SessionPrefs.steamTouchLayout(context))
        session.edit().putString("osc", SessionPrefs.OSC_STEAM_TOUCH).commit()
        assertTrue(SessionPrefs.steamTouchLayout(context))
        SessionPrefs.setSteamTouchLayout(context, false)
        assertFalse(SessionPrefs.steamTouchLayout(context))
        session.edit().putString("osc", SessionPrefs.OSC_ALWAYS).putBoolean("steamTouchLayout", true).commit()
        assertTrue(SessionPrefs.steamTouchLayout(context))
    }

    @Test fun eachGameKeepsItsOwnLayoutChoice() {
        assertNull(GameControls.bindings(context, null))
        assertNull(GameControls.bindings(context, 7))
        GameControls.choose(context, 7, "mine.bindings", steam = false)
        assertEquals("mine.bindings", GameControls.bindings(context, 7))
        assertNull(GameControls.bindings(context, 8))
        assertFalse(GameControls.steamLayout(context, 7))
        GameControls.choose(context, 7, null, steam = true)
        assertNull(GameControls.bindings(context, 7))
        assertTrue(GameControls.steamLayout(context, 7))
    }

    @Test fun gamesWithoutAChoiceFollowTheDefaultSteamLayout() {
        SessionPrefs.setSteamTouchLayout(context, true)
        assertTrue(GameControls.steamLayout(context, null))
        assertTrue(GameControls.steamLayout(context, 9))
        GameControls.choose(context, 9, null, steam = false)
        assertFalse(GameControls.steamLayout(context, 9))
        assertTrue(GameControls.steamLayout(context, 10))
    }

    @Test fun kitElementsWithoutSavedBindingsGetTheirDefaultActions() {
        val profile = InputProfile("empty")
        assertEquals(InputAction.Native(), profile.elementAction(InputRole.PRIMARY, KitLayout.DPAD_ID, InputControl.LS))
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), profile.elementAction(InputRole.PRIMARY, KitLayout.KEYBOARD_ID, InputControl.A))
        assertEquals(InputAction.Native(), profile.elementAction(InputRole.PRIMARY, "legacy:a", InputControl.A))
        assertEquals(InputAction.Disabled, profile.elementAction(InputRole.PRIMARY, "custom:1", InputControl.A))
        val mapped = profile.copy(bindings = mapOf(InputRole.PRIMARY to mapOf(InputControl.A to InputAction.Keys(listOf(57)))),
            elements = mapOf(InputRole.PRIMARY to mapOf(KitLayout.KEYBOARD_ID to InputAction.Disabled)))
        assertEquals(InputAction.Keys(listOf(57)), mapped.elementAction(InputRole.PRIMARY, "legacy:a", InputControl.A))
        assertEquals(InputAction.Disabled, mapped.elementAction(InputRole.PRIMARY, KitLayout.KEYBOARD_ID, InputControl.A))

        val bindings = SavedInputBindings("b", "B", false, "layout")
        assertEquals(InputAction.Native(), bindings.action(InputSurfaceElement(InputControl.LS, 0f, 0f, .1f, .1f, KitLayout.DPAD_ID, InputElementKind.DPAD)))
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), bindings.action(InputSurfaceElement(InputControl.A, 0f, 0f, .1f, .1f, KitLayout.KEYBOARD_ID)))
        assertEquals(InputAction.Disabled, bindings.action(InputSurfaceElement(InputControl.A, 0f, 0f, .1f, .1f, "custom:1")))
    }

    private fun storeAt(version: Int, change: (InputLibrary) -> InputLibrary) {
        val stored = change(InputLibraries.read(context))
        library.edit().putString("library", JSONObject(InputLibraries.encode(stored)).put("version", version).toString()).commit()
    }

    @Test fun gamepadsSavedByTheOldEditorFollowTheKitAgainOnce() {
        val placed = KitLayout.elements(context, PadKit.STEAM_DECK, 1920, 1080)
        val custom = SavedInputLayout("custom", "Custom", listOf(InputSurfaceElement(InputControl.B, .1f, .1f, .1f, .1f)), aspect = 2f)
        storeAt(14) { lib ->
            val overlay = lib.layouts.single { it.id == InputLibraries.OVERLAY }
            lib.withLayout(overlay.copy(elements = placed, aspect = 16f / 9, scale = 1.25f))
                .withLayout(SavedInputLayout("copy", "Copy", placed, aspect = 16f / 9, origin = InputLibraries.OVERLAY))
                .withLayout(SavedInputLayout("migrated.layout.1600x900", "Gamepad", placed, aspect = 16f / 9))
                .withLayout(custom)
        }
        val before = InputLibraries.decode(library.getString("library", null)!!)!!
        val upgraded = InputLibraries.read(context)
        for (id in listOf(InputLibraries.OVERLAY, "copy", "migrated.layout.1600x900")) {
            val layout = upgraded.layouts.single { it.id == id }
            assertTrue(id, layout.elements.isEmpty())
            assertEquals(id, 0f, layout.aspect)
            assertEquals(id, 1f, layout.scale)
            assertTrue(id, OnScreenLayout.generated(layout))
        }
        assertNull(upgraded.layouts.single { it.id == InputLibraries.OVERLAY }.origin)
        assertEquals(InputLibraries.OVERLAY, upgraded.layouts.single { it.id == "migrated.layout.1600x900" }.origin)
        assertEquals(custom, upgraded.layouts.single { it.id == "custom" })
        for (id in listOf(InputLibraries.DECK, InputLibraries.TRACKPAD)) assertEquals(before.layouts.single { it.id == id }, upgraded.layouts.single { it.id == id })
        assertEquals(upgraded, InputLibraries.read(context))
        val edited = upgraded.withLayout(upgraded.layouts.single { it.id == InputLibraries.OVERLAY }.copy(elements = placed, aspect = 16f / 9))
        InputLibraries.update(context) { edited }
        assertEquals(placed, InputLibraries.read(context).layouts.single { it.id == InputLibraries.OVERLAY }.elements)
    }

    @Test fun theKitUpgradeKeepsScreenKeysMergedIntoTheGamepad() {
        val key = InputSurfaceElement(InputControl.A, .45f, .1f, .1f, .1f, "imported:0", name = "Find", alwaysShown = true)
        storeAt(14) { lib ->
            val overlay = lib.layouts.single { it.id == InputLibraries.OVERLAY }
            val bindings = lib.preset(lib.activePart(false, InputRole.PRIMARY).bindings)
            lib.withLayout(overlay.copy(elements = KitLayout.elements(context, PadKit.STEAM_DECK, 1920, 1080) + key, aspect = 16f / 9))
                .withPreset(bindings.copy(elements = bindings.elements + (key.id to InputAction.Keys(listOf(29, 33)))))
        }
        val upgraded = InputLibraries.read(context)
        assertEquals(listOf(key.id), upgraded.layouts.single { it.id == InputLibraries.OVERLAY }.elements.map { it.id }.filter { it.startsWith("imported:") })
        assertEquals(InputAction.Keys(listOf(29, 33)), upgraded.compileActive(false).elementAction(InputRole.PRIMARY, key.id, InputControl.A))
    }

    @Test fun bindingsSavedForKitControlsSurviveTheKitUpgrade() {
        storeAt(14) { lib ->
            val overlay = lib.layouts.single { it.id == InputLibraries.OVERLAY }
            val bindings = lib.preset(lib.activePart(false, InputRole.PRIMARY).bindings)
            lib.withLayout(overlay.copy(elements = KitLayout.elements(context, PadKit.STEAM_DECK, 1920, 1080), aspect = 16f / 9))
                .withPreset(bindings.copy(elements = bindings.elements + ("legacy:a" to InputAction.Keys(listOf(57))),
                    clicks = bindings.clicks + ("legacy:ls" to InputAction.Disabled)))
        }
        val upgraded = InputLibraries.read(context)
        assertTrue(OnScreenLayout.generated(upgraded.layouts.single { it.id == InputLibraries.OVERLAY }))
        val profile = upgraded.compileActive(false)
        assertEquals(InputAction.Keys(listOf(57)), profile.elementAction(InputRole.PRIMARY, "legacy:a", InputControl.A))
        assertEquals(InputAction.Disabled, profile.clicks[InputRole.PRIMARY]?.get("legacy:ls"))
    }

    @Test fun changingShowKeepsTheSteamLayoutAnOlderBuildStoredThere() {
        val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("osc", SessionPrefs.OSC_STEAM_TOUCH).commit()
        SessionPrefs.setOscMode(context, SessionPrefs.OSC_ALWAYS)
        assertEquals(SessionPrefs.OSC_ALWAYS, SessionPrefs.oscMode(context))
        assertTrue(SessionPrefs.steamTouchLayout(context))
    }

    @Test fun aGamepadWithEveryKitControlRemovedStaysEmptyAndKeepsKitBindingsOnOtherEdits() {
        val library = InputLibraries.read(context)
        val overlay = library.layouts.single { it.id == InputLibraries.OVERLAY }
        val key = InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, "imported:9", name = "Key")
        val emptied = overlay.copy(elements = listOf(key), aspect = 16f / 9, kitless = true)
        InputLibraries.update(context) { it.withLayout(emptied) }
        val stored = InputLibraries.read(context).layouts.single { it.id == InputLibraries.OVERLAY }
        assertFalse(OnScreenLayout.generated(stored))
        assertEquals(listOf("imported:9"), OnScreenLayout.elements(context, stored, 1920, 1080, android.graphics.Rect()).map { it.id })
        val bindings = library.preset(library.activePart(false, InputRole.PRIMARY).bindings)
        val withKey = InputLibraries.update(context) { lib ->
            lib.withLayout(overlay).withPreset(bindings.copy(elements = bindings.elements + ("legacy:a" to InputAction.Keys(listOf(30)))))
        }
        val dimmed = withKey.withLayout(overlay.copy(style = overlay.style.copy(opacity = 50)))
        assertEquals(InputAction.Keys(listOf(30)), dimmed.preset(bindings.id).elements["legacy:a"])
    }

    @Test fun aSavedKitDpadKeepsItsDirectionOverrides() {
        val library = InputLibraries.read(context)
        val overlay = library.layouts.single { it.id == InputLibraries.OVERLAY }
        val saved = overlay.copy(elements = KitLayout.elements(context, PadKit.XBOX, 1920, 1080), aspect = 16f / 9)
        val bindings = library.preset(library.activePart(false, InputRole.PRIMARY).bindings)
        val withUp = library.withLayout(saved).withPreset(bindings.copy(elements = bindings.elements + ("legacy:up" to InputAction.Keys(listOf(103)))))
        val edited = withUp.withLayout(saved.copy(style = saved.style.copy(opacity = 60))).validate()
        assertEquals(InputAction.Keys(listOf(103)), edited.preset(bindings.id).elements["legacy:up"])
        val noDpad = edited.withLayout(saved.copy(elements = saved.elements.filterNot { it.id == KitLayout.DPAD_ID }))
        assertNull(noDpad.preset(bindings.id).elements["legacy:up"])
    }

    @Test fun aFailedRecoverySaveLeavesTheStoredLibraryAsItWas() {
        val stored = JSONObject().put("version", 1).put("profiles", "broken").toString()
        val real = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
        real.edit().clear().putString("library", stored).commit()
        val failing = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by prefs {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
                            override fun remove(key: String?) = apply { editor.remove(key) }
                            override fun commit(): Boolean { editor.apply(); return false }
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { InputLibraries.update(failing) { it } }.isFailure)
        assertEquals(stored, real.getString("library", null))
        assertTrue(real.all.keys.none { it.startsWith("library-recovery.") })
    }
}
