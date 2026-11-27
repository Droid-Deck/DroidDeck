package com.droiddeck.launcher.ui

import android.content.Context
import com.droiddeck.launcher.input.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import androidx.compose.ui.test.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1000dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputLayoutDataTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun choice(key: String) = inputAddChoices(context, trackpads = true).first { it.key == key }
    private fun group(key: String) = inputAddGroups(context).first { it.key == key }

    private fun draft(library: InputLibrary = InputLibraries.read(context), layoutId: String = InputLibraries.OVERLAY,
        bindingsId: String = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) =
        library.layouts.first { it.id == layoutId }.let { layout ->
            surfaceDraft(context, library, layout, library.preset(bindingsId), if (layout.screen == InputLayoutScreen.MAIN) InputRole.PRIMARY else InputRole.SECONDARY)
        }

    /** What the editor's Save button writes. */
    private fun save(draft: SurfaceDraft) = InputLibraries.update(context) { draft.saveTo(it, null) }

    /** Reopening re-places main-screen controls in pixels, so positions may differ in the last float bit. */
    private fun assertSamePlaces(expected: List<InputSurfaceElement>, actual: List<InputSurfaceElement>) {
        assertEquals(expected.map { it.id }, actual.map { it.id })
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.copy(x = a.x, y = a.y, width = a.width, height = a.height), a)
            for ((x, y) in listOf(e.x to a.x, e.y to a.y, e.width to a.width, e.height to a.height)) assertEquals(e.id, x, y, 1e-5f)
        }
    }

    private fun saved(id: String = InputLibraries.OVERLAY) = InputLibraries.read(context).layouts.first { it.id == id }

    @Test fun fillingToTheLimitThenSavingAndUndoingStaysValid() {
        val draft = draft()
        val stock = draft.elements.size
        while (draft.canAdd && draft.elements.size < 64) draft.add(choice("control-a"), 16f / 9f)
        assertEquals(64, draft.elements.size)
        assertFalse(draft.canAdd)
        assertTrue(inputAddGroups(context).none(draft::available))
        save(draft)
        val reopened = draft()
        assertSamePlaces(draft.elements, reopened.elements)
        assertFalse(reopened.canAdd)

        val added = reopened.elements.last { !it.id.startsWith("legacy:") && !it.id.startsWith("builtin:") }
        reopened.select(listOf(added.id)); reopened.deleteSelection()
        assertFalse(reopened.available(group("group-face")))
        reopened.add(group("group-face"), 16f / 9f)
        assertEquals(63, reopened.elements.size)
        reopened.add(choice("control-b"), 16f / 9f)
        assertEquals(64, reopened.elements.size)

        reopened.select(listOf("legacy:up")); reopened.deleteSelection()
        assertTrue(reopened.available(group("group-dpad")))
        reopened.add(group("group-dpad"), 16f / 9f)
        assertTrue(reopened.visible.any { it.id == "legacy:up" })
        assertEquals(64, reopened.elements.size)

        while (reopened.history.isNotEmpty()) { reopened.undo(); assertTrue(reopened.elements.size <= 64) }
        assertEquals(64, reopened.elements.size)
        while (reopened.redoable.isNotEmpty()) reopened.redo()
        save(reopened)
        assertEquals(reopened.elements, saved().elements)
        assertTrue(stock < 64)

        val fresh = draft()
        while (fresh.history.size < 40 && fresh.elements.size < 64) fresh.add(choice("control-x"), 16f / 9f)
        repeat(50) { fresh.undo() }
        assertTrue(fresh.elements.size <= 64)
    }

    @Test fun deletingEveryControlSavesReopensAndResetsBack() {
        val draft = draft()
        val stock = draft.elements
        draft.add(choice("control-ls"), 16f / 9f)
        draft.select(draft.visible.map { it.id }); draft.deleteSelection()
        assertTrue(draft.visible.isEmpty())
        save(draft)
        val layout = saved()
        assertTrue(layout.style.shown(layout.elements).isEmpty())
        assertEquals(stock.map { it.id }.toSet(), layout.style.removed)

        val reopened = draft()
        assertTrue(reopened.visible.isEmpty())
        assertTrue(reopened.resettable)
        reopened.resetLayout()
        assertEquals(stock.map { it.id }, reopened.visible.map { it.id })
        reopened.undo()
        assertTrue(reopened.visible.isEmpty())
        reopened.add(choice("control-a"), 16f / 9f)
        assertEquals(listOf("legacy:a"), reopened.visible.map { it.id })
        save(reopened)
        assertEquals(listOf("legacy:a"), saved().let { it.style.shown(it.elements) }.map { it.id })
    }

    @Test fun deletedControlsLeaveTheExportAndTheActionList() {
        val draft = draft()
        draft.select(listOf("legacy:a")); draft.deleteSelection()
        save(draft)
        val layout = saved()
        val bindings = InputLibraries.read(context).preset(InputLibraries.read(context).profile(InputProfile.GAME).binding(InputRole.PRIMARY))
        val keyed = bindings.copy(elements = bindings.elements + layout.elements.associate { it.id to InputAction.Keys(listOf(30)) })
        val exported = JSONObject(InputLayoutFile.export(layout, keyed, 2400, 1080)!!).getJSONArray("elements")
        val buttons = layout.elements.filter { it.kind != InputElementKind.DPAD && it.kind != InputElementKind.STICK }
        assertEquals(buttons.size - 1, exported.length())
    }

    @Test fun deletedControlsAreNotOfferedForActionsInSettings() {
        val draft = draft()
        draft.select(listOf("legacy:a")); draft.deleteSelection()
        save(draft)
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:b").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-element-legacy:a").assertDoesNotExist()
    }

    @Test fun tenCopiesOfOneButtonKeepUniqueIdsAndTheirAction() {
        val draft = draft()
        val before = draft.elements.map { it.id }.toSet()
        repeat(10) { draft.add(choice("control-y"), 16f / 9f) }
        save(draft)
        val library = InputLibraries.read(context)
        val copies = library.layouts.first { it.id == InputLibraries.OVERLAY }.elements.filter { it.id !in before }
        assertEquals(10, copies.size)
        assertEquals(10, copies.map { it.id }.distinct().size)
        val compiled = library.compile(InputProfile.GAME)
        val stock = library.layouts.first { it.id == InputLibraries.OVERLAY }.elements.first { it.id == "legacy:y" }
        val expected = compiled.elementAction(InputRole.PRIMARY, stock.id, stock.control).forCopy(InputControl.Y)
        for (e in copies) assertEquals(expected, compiled.elementAction(InputRole.PRIMARY, e.id, e.control))
        val reopened = draft(library)
        reopened.select(listOf(copies[3].id)); reopened.deleteSelection()
        save(reopened)
        val after = InputLibraries.read(context)
        assertNull(after.preset(after.profile(InputProfile.GAME).binding(InputRole.PRIMARY)).elements[copies[3].id])
        assertEquals(9, after.layouts.first { it.id == InputLibraries.OVERLAY }.elements.count { it.id !in before })
    }

    @Test fun everyAddChoiceAndGroupSavesOnEveryLayoutAndScreenShape() {
        val library = InputLibraries.read(context)
        for (layout in library.layouts.filter { it.editable }) {
            val bindings = library.bindings.firstOrNull { it.layoutId == layout.id } ?: continue
            for (aspect in listOf(.45f, 1.15f, 16f / 9f, 3f)) {
                InputLibraries.update(context) { library }
                val draft = draft(InputLibraries.read(context), layout.id, bindings.id)
                for (c in inputAddChoices(context, trackpads = true)) if (draft.available(c)) draft.add(c, aspect)
                for (g in inputAddGroups(context)) if (draft.available(g)) draft.add(g, aspect)
                assertTrue(draft.elements.size <= 64)
                for (e in draft.elements) assertEquals("${layout.id} $aspect ${e.name}", e.bounded(), e)
                val result = runCatching { save(draft) }
                assertTrue("${layout.id} at $aspect: ${result.exceptionOrNull()}", result.isSuccess)
            }
        }
    }

    @Test fun layoutSizeExtremesWithAddedControlsResetAndUndo() {
        val draft = draft()
        repeat(3) { draft.add(choice("control-rs"), 16f / 9f) }
        draft.add(group("group-face"), 16f / 9f)
        for (percent in listOf(80, 125, 80, 125, 100, 125)) {
            draft.scaleAll(percent)
            for (e in draft.elements) assertEquals(e.bounded(), e)
        }
        draft.checkpoint()
        val scaled = draft.elements
        assertEquals(125, draft.scale)
        save(draft)
        assertEquals(1.25f, saved().scale)
        draft.resetLayout()
        assertEquals(100, draft.scale)
        save(draft)
        assertEquals(1f, saved().scale)
        draft.undo()
        assertEquals(scaled, draft.elements)
        assertEquals(125, draft.scale)
        save(draft)
        val reopened = draft()
        assertEquals(125, reopened.scale)
        assertSamePlaces(scaled, reopened.elements)
    }

    @Test fun deckGamepadResetsToItsOwnStockLayout() {
        val library = InputLibraries.read(context)
        val deck = library.layouts.first { it.id == DeckPanel.GAMEPAD.layout }
        val draft = draft(library, deck.id, DeckPanel.GAMEPAD.bindings)
        val stock = draft.elements
        draft.select(listOf("legacy:a")); draft.resizeSelection(150)
        draft.scaleAll(80)
        draft.resetLayout()
        assertEquals(stock, draft.elements)
        draft.scaleAll(125)
        for (e in draft.elements) {
            val s = stock.first { it.id == e.id }
            assertEquals(e.id, s.width * 1.25f, e.width, .02f)
        }
    }

    @Test fun gameControlsAreACopyThatSurvivesOffAndOnAndSharedEdits() {
        val shared = saved()
        InputLibraries.update(context) { it.withGameProfile("1091500", "Cyberpunk").withGameProfile("413150", "Stardew") }
        fun gameLayout(game: String) = InputLibraries.read(context).activeLayout(false, InputRole.PRIMARY, game)!!
        fun gameDraft(game: String) = InputLibraries.read(context).let { l ->
            draft(l, gameLayout(game).id, l.activePart(false, InputRole.PRIMARY, game).bindings)
        }
        val cyber = gameDraft("1091500")
        cyber.select(listOf("legacy:b")); cyber.deleteSelection()
        repeat(2) { cyber.add(choice("control-l4"), 16f / 9f) }
        save(cyber)
        assertEquals(shared, saved())
        assertTrue("legacy:b" in gameLayout("1091500").style.removed)
        assertTrue(gameLayout("413150").style.removed.isEmpty())

        InputLibraries.update(context) { it.withoutGameProfile("1091500") }
        assertEquals(InputLibraries.OVERLAY, gameLayout("1091500").id)
        val sharedDraft = draft()
        sharedDraft.select(listOf("legacy:x")); sharedDraft.deleteSelection()
        save(sharedDraft)
        InputLibraries.update(context) { it.withGameProfile("1091500", "Cyberpunk") }
        val again = gameLayout("1091500")
        assertEquals(setOf("legacy:b"), again.style.removed)
        assertEquals(cyber.elements, again.elements)
        assertEquals(setOf("legacy:x"), saved().style.removed)
        assertEquals(emptySet<String>(), gameLayout("413150").style.removed)

        InputLibraries.update(context) { it.removeProfile(gameProfileId("413150")) }
        assertEquals(InputLibraries.OVERLAY, gameLayout("413150").id)
        assertEquals(again, gameLayout("1091500"))
    }

    private fun storedLayouts(edit: (JSONObject) -> Unit) {
        draft().let { save(it) }
        val doc = JSONObject(prefs.getString("library", null)!!)
        edit(doc.getJSONArray("layouts").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == InputLibraries.OVERLAY } })
        prefs.edit().putString("library", doc.toString()).commit()
    }

    @Test fun oddButHarmlessSavedDataLoadsAndIsRepaired() {
        storedLayouts { layout ->
            layout.put("removed", JSONArray(listOf("legacy:a", "nope", "legacy:a")))
            layout.put("hidden", JSONArray(listOf("STEAM", "NOT_A_COMMAND")))
        }
        val layout = saved()
        assertEquals(setOf("legacy:a", "legacy:guide"), layout.style.removed)
        val reopened = draft()
        assertEquals(setOf("legacy:a", "legacy:guide"), reopened.removed)
        reopened.add(choice("control-guide"), 16f / 9f)
        save(reopened)
        assertEquals(setOf("legacy:a"), saved().style.removed)
        val raw = JSONObject(prefs.getString("library", null)!!).getJSONArray("layouts")
        val stored = (0 until raw.length()).map { raw.getJSONObject(it) }.first { it.getString("id") == InputLibraries.OVERLAY }
        assertFalse(stored.has("hidden"))
    }

    @Test fun brokenSavedDataFallsBackWithoutCrashingAndKeepsARecoveryCopy() {
        val cases = mapOf<String, (JSONObject) -> Unit>(
            "unknown control" to { it.getJSONArray("elements").getJSONObject(0).put("control", "warp-drive") },
            "duplicate ids" to { l -> l.getJSONArray("elements").let { a -> a.put(JSONObject(a.getJSONObject(0).toString())) } },
            "negative x" to { it.getJSONArray("elements").getJSONObject(0).put("x", -0.5) },
            "huge width" to { it.getJSONArray("elements").getJSONObject(0).put("width", 7.0) },
            "NaN as text" to { it.getJSONArray("elements").getJSONObject(0).put("y", "NaN") },
            "missing name" to { it.getJSONArray("elements").getJSONObject(0).remove("name") },
            "65 elements" to { l -> l.getJSONArray("elements").let { a -> repeat(65 - a.length()) { i -> a.put(JSONObject(a.getJSONObject(0).toString()).put("id", "extra-$i")) } } },
            "scale 9" to { it.put("scale", 9.0) },
        )
        for ((name, edit) in cases) {
            reset()
            storedLayouts(edit)
            val broken = prefs.getString("library", null)
            val library = runCatching { InputLibraries.read(context) }
            assertTrue("$name: ${library.exceptionOrNull()}", library.isSuccess)
            assertEquals(name, broken, prefs.getString("library", null))
            val editor = runCatching { draft() }
            assertTrue("$name: ${editor.exceptionOrNull()}", editor.isSuccess)
            editor.getOrThrow().add(choice("control-a"), 16f / 9f)
            save(editor.getOrThrow())
            assertTrue(name, InputLibraries.hasLibraryRecovery(context))
        }
        reset()
        prefs.edit().putString("library", "x".repeat(2_100_000)).commit()
        assertTrue(runCatching { InputLibraries.read(context) }.isSuccess)
    }

    @Test fun aSaveThatFailsKeepsTheStoredLibraryAndTheDraft() {
        val draft = draft()
        draft.add(choice("control-a"), 16f / 9f)
        draft.select(listOf("legacy:b")); draft.deleteSelection()
        val newer = JSONObject(InputLibraries.encode(InputLibraries.read(context))).put("version", 99).toString()
        prefs.edit().putString("library", newer).commit()
        val failure = runCatching { save(draft) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(newer, prefs.getString("library", null))
        assertTrue(draft.dirty)
        assertTrue("legacy:b" in draft.removed)
    }
}
