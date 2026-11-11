package com.droiddeck.launcher.input

import android.content.Context
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
class InputLayoutFileTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun import(raw: String) = InputLayoutFile.import(context, raw, "Fallback", InputLayoutScreen.MAIN, InputSurfaceStyle(), 1000, 500, 2f)

    @Test fun exportThenImportKeepsKeysShapesCentresSizesAndToggles() {
        val keys = LegacyScreenKeyMigration.convert(context,
            "28:0.25,0.6,50::::0.8:2:0:1;29+33:0.7,0.8,40::Find::0.6:3:1:1;103+0+106+0+108+0+105+0:0.5,0.5,80::::1:6:0:1;17+32+31+30:0.2,0.3,70::::1:5:0:1",
            1000, 500, 2f)
        val layout = SavedInputLayout("source", "My layout", keys.map { it.first })
        val bindings = SavedInputBindings("source.bindings", "My layout", false, layout.id, elements = keys.associate { it.first.id to it.second })
        val raw = InputLayoutFile.export(layout, bindings, 1000, 500)!!
        assertEquals("My layout", JSONObject(raw).getString("name"))
        val (imported, actions) = import(raw)!!
        assertEquals("My layout", imported.name)
        assertEquals(InputLayoutScreen.MAIN, imported.screen)
        assertEquals(layout.elements.map { it.kind }, imported.elements.map { it.kind })
        for ((before, after) in layout.elements.zip(imported.elements)) {
            assertEquals(before.x + before.width / 2, after.x + after.width / 2, .002f)
            assertEquals(before.y + before.height / 2, after.y + after.height / 2, .002f)
            assertEquals(before.height, after.height, .005f)
            assertEquals(before.toggle, after.toggle)
            assertEquals(before.opacity, after.opacity, .001f)
        }
        assertEquals(InputButtonShape.CIRCLE, imported.elements[0].buttonShape)
        assertEquals(InputButtonShape.RECTANGLE, imported.elements[1].buttonShape)
        assertEquals("Find", imported.elements[1].name)
        val codes = imported.elements.map { actions.action(it) }
        assertEquals(InputAction.Keys(listOf(28)), codes[0])
        assertEquals(InputAction.Keys(listOf(29, 33)), codes[1])
        assertEquals(listOf(103, 106, 108, 105), (codes[2] as InputAction.Directions).let { listOf(it.up, it.right, it.down, it.left) })
        assertEquals(InputAction.Directions(17, 32, 31, 30), codes[3])
        InputLibraries.update(context) { it.withLayout(imported).withPreset(actions).chooseLayout(InputProfile.GAME, InputRole.PRIMARY, imported.id) }
        assertEquals(imported.id, InputLibraries.read(context).layout(InputProfile.GAME, InputRole.PRIMARY)!!.id)
    }

    @Test fun importsOlderDroidDeckAndWinlatorFiles() {
        val raw = """{"id":0,"name":"DroidDeck","cursorSpeed":1.5,"elements":[
            {"type":"BUTTON","shape":"CIRCLE","bindings":["KEY_SPACE"],"scale":1.2,"x":0.8,"y":0.7,"toggleSwitch":false,"text":"Jump","opacity":0.5},
            {"type":"BUTTON","shape":"ROUND_RECT","bindings":["KEY_CTRL_L","KEY_C"],"scale":1.0,"x":0.3,"y":0.2,"toggleSwitch":true,"text":""},
            {"type":"STICK","shape":"ROUND_RECT","bindings":["KEY_W","","KEY_D","","KEY_S","","KEY_A",""],"scale":1.0,"x":0.2,"y":0.7},
            {"type":"STICK","bindings":["KEY_UP","KEY_RIGHT","KEY_DOWN","KEY_LEFT"],"scale":1.0,"x":0.6,"y":0.5},
            {"type":"D_PAD","bindings":["KEY_I","KEY_L","KEY_K","KEY_J"],"scale":1.0,"x":0.4,"y":0.5},
            {"type":"TRACKPAD","bindings":["MOUSE_MOVE_LEFT"]},
            {"type":"BUTTON","bindings":["GAMEPAD_BUTTON_A"],"x":0.1,"y":0.1}
        ]}"""
        val (layout, actions) = import(raw)!!
        assertEquals("DroidDeck", layout.name)
        assertEquals(listOf(InputElementKind.BUTTON, InputElementKind.BUTTON, InputElementKind.STICK, InputElementKind.STICK, InputElementKind.DPAD), layout.elements.map { it.kind })
        val jump = layout.elements[0]
        assertEquals("Jump", jump.name)
        assertEquals(.5f, jump.opacity, .001f)
        assertEquals(InputButtonShape.CIRCLE, jump.buttonShape)
        assertEquals(.8f, jump.x + jump.width / 2, .001f)
        assertEquals(6f * 10f * 1.2f / 500f, jump.height, .002f)
        assertTrue(layout.elements[1].toggle)
        assertEquals(InputAction.Keys(listOf(29, 46)), actions.action(layout.elements[1]))
        assertEquals(InputAction.Directions(17, 32, 31, 30, listOf(0, 0, 0, 0)), actions.action(layout.elements[2]))
        assertEquals(InputAction.Directions(103, 106, 108, 105, listOf(0, 0, 0, 0)), actions.action(layout.elements[3]))
        assertEquals(InputAction.Directions(23, 38, 37, 36), actions.action(layout.elements[4]))
    }

    @Test fun rejectsFilesWithoutUsableControls() {
        assertNull(import("not json"))
        assertNull(import("""{"name":"x"}"""))
        assertNull(import("""{"elements":[{"type":"BUTTON","bindings":["GAMEPAD_BUTTON_A"]}]}"""))
        val (layout, _) = import("""{"elements":[{"type":"BUTTON","bindings":["KEY_E"],"x":"bad","scale":-1}]}""")!!
        assertEquals("Fallback", layout.name)
        assertEquals(.5f, layout.elements.single().x + layout.elements.single().width / 2, .001f)
    }

    @Test fun layoutsWithoutKeyBindingsHaveNothingToExport() {
        val library = InputLibraries.read(context)
        val gamepad = library.layouts.first { it.id == InputLibraries.OVERLAY }
        assertNull(InputLayoutFile.export(gamepad, library.bindings.firstOrNull { it.layoutId == gamepad.id }, 1000, 500))
        assertEquals("A_B_.icp", InputLayoutFile.fileName(gamepad.copy(name = "A/B?")))
    }
}
