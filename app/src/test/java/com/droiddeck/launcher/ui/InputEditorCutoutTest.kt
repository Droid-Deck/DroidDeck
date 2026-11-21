package com.droiddeck.launcher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import com.droiddeck.launcher.input.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.droiddeck.launcher.session.SessionPrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w833dp-h468dp-land-369dpi")
class InputEditorCutoutTest {
    @get:Rule val looper = MainLooperDrain()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val size get() = InputLibraries.primaryDisplay(context).second.let { it.widthPixels to it.heightPixels }
    private val noCutout = Rect()
    private val left get() = Rect((size.first * .05f).toInt(), 0, 0, 0)
    private val right get() = Rect(0, 0, (size.first * .05f).toInt(), 0)

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller", "session")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        // The Steam Deck controller redraws the stock overlay in its own places; these tests check the editor on the plain one.
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
    }

    private fun gamepad() = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }
    private fun editor(layout: SavedInputLayout, safe: Rect) = InputLibraries.read(context).let { library ->
        surfaceDraft(context, library, layout, SavedInputBindings("test", "Test", false), InputRole.PRIMARY, safe)
    }
    private fun inGame(layout: SavedInputLayout, safe: Rect) = OnScreenLayout.elements(context, layout, size.first, size.second, safe)
    private fun saved(layout: SavedInputLayout, draft: SurfaceDraft) =
        layout.copy(elements = draft.elements, aspect = size.first.toFloat() / size.second, scale = draft.layoutScale)

    private fun assertSamePlaces(expected: List<InputSurfaceElement>, actual: List<InputSurfaceElement>) {
        assertEquals(expected.map { it.id }, actual.map { it.id })
        for ((e, a) in expected.zip(actual)) {
            val (w, h) = size
            assertEquals("${e.id} x", e.x * w, a.x * w, .5f)
            assertEquals("${e.id} y", e.y * h, a.y * h, .5f)
            assertEquals("${e.id} width", e.width * w, a.width * w, .5f)
            assertEquals("${e.id} height", e.height * h, a.height * h, .5f)
        }
    }

    private fun moved(elements: List<InputSurfaceElement>, id: String, dx: Float) = elements.map { if (it.id == id) it.copy(x = it.x + dx) else it }

    private fun editorMatchesTheGameBeforeAndAfterSaving(safe: Rect, movedId: String, dx: Float) {
        val stock = gamepad()
        val draft = editor(stock, safe)
        assertSamePlaces(inGame(stock, safe), draft.elements)
        assertFalse(draft.dirty)
        assertFalse(draft.resettable)

        val unchanged = saved(stock, draft)
        assertSamePlaces(inGame(stock, safe), inGame(unchanged, safe))
        assertNotNull(OnScreenLayout.generatedSize(context, unchanged, size.first, size.second, safe))

        draft.checkpoint()
        draft.elements = moved(draft.elements, movedId, dx)
        val edited = saved(stock, draft)
        val shown = inGame(edited, safe)
        assertSamePlaces(draft.elements, shown)
        assertSamePlaces(inGame(stock, safe).filterNot { it.id == movedId }, shown.filterNot { it.id == movedId })
        assertSamePlaces(shown, editor(edited, safe).elements)

        draft.resetLayout()
        assertSamePlaces(inGame(stock, safe), draft.elements)
    }

    @Test fun withoutACutoutTheEditorAndTheGameAgree() = editorMatchesTheGameBeforeAndAfterSaving(noCutout, "legacy:a", -.03f)

    @Test fun aLeftCutoutMovesNothingWhenTheEditorOpensOrSaves() {
        assertNotEquals(gamepad().elements.first { it.id == "legacy:ls" }.x, inGame(gamepad(), left).first { it.id == "legacy:ls" }.x)
        editorMatchesTheGameBeforeAndAfterSaving(left, "legacy:a", -.03f)
    }

    @Test fun aRightCutoutMovesNothingWhenTheEditorOpensOrSaves() {
        assertNotEquals(gamepad().elements.first { it.id == "legacy:a" }.x, inGame(gamepad(), right).first { it.id == "legacy:a" }.x)
        editorMatchesTheGameBeforeAndAfterSaving(right, "legacy:up", .03f)
    }

    @Test fun onTheSteamDeckControllerTheEditorShowsTheDeckPlacesAndSavesWhatItShows() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val stock = gamepad()
        val shown = inGame(stock, left)
        assertNotEquals(stock.elements.first { it.id == "legacy:start" }.y, shown.first { it.id == "legacy:start" }.y)
        val draft = editor(stock, left)
        assertSamePlaces(shown, draft.elements)
        assertFalse(draft.resettable)

        draft.checkpoint()
        draft.scaleAll(120)
        draft.checkpoint()
        draft.elements = moved(draft.elements, "legacy:a", -.03f)
        val edited = saved(stock, draft)
        assertSamePlaces(draft.elements, inGame(edited, left))
        assertSamePlaces(draft.elements, editor(edited, left).elements)

        draft.resetLayout()
        assertSamePlaces(shown, draft.elements)
    }

    @Test fun anAddedControlNearTheCutoutSitsWhereTheGameShowsIt() {
        val safe = left
        val inside = InputSurfaceElement(InputControl.L4, 0f, .4f, .06f, .12f, "added:inside")
        val beside = InputSurfaceElement(InputControl.R4, (safe.left + 4f) / size.first, .6f, .06f, .12f, "added:beside")
        val layout = gamepad().let { it.copy(elements = it.elements + inside + beside) }
        val draft = editor(layout, safe)
        assertSamePlaces(inGame(layout, safe), draft.elements)
        assertEquals(safe.left.toFloat(), draft.elements.first { it.id == inside.id }.x * size.first, .5f)
        assertEquals(beside.x, draft.elements.first { it.id == beside.id }.x, .0005f)
        val again = saved(layout, draft)
        assertSamePlaces(draft.elements, inGame(again, safe))
        assertSamePlaces(draft.elements, editor(again, safe).elements)
    }

    @Test fun growingControlsStopsAtTheCutout() {
        for (safe in listOf(left, right)) {
            val added = InputSurfaceElement(InputControl.L4, (safe.left + 2f) / size.first, .4f, .06f, .12f, "added:edge")
            val near = InputSurfaceElement(InputControl.R4, 1f - (safe.right + 2f) / size.first - .06f, .6f, .06f, .12f, "added:far")
            val draft = editor(gamepad().let { it.copy(elements = it.elements + added + near) }, safe)
            for (id in listOf(added.id, near.id)) {
                draft.select(listOf(id))
                draft.resizeSelection(300)
                assertInsideSafe(draft.elements, safe)
            }
            draft.select(emptyList())
            draft.scaleAll(150)
            assertInsideSafe(draft.elements, safe)
        }
    }

    @Test fun addedControlsArePlacedOutsideTheCutout() {
        val (w, h) = size
        for (safe in listOf(left, right)) {
            val wall = InputSurfaceElement(InputControl.A, if (safe.left > 0) .2f else 0f, 0f, .8f, 1f, "added:wall")
            val draft = editor(gamepad().copy(elements = listOf(wall)), safe)
            draft.add(inputAddChoices(context, false).first { it.control == InputControl.L4 }, w.toFloat() / h)
            draft.add(inputAddGroups(context).first(), w.toFloat() / h)
            assertEquals(1 + 1 + inputAddGroups(context).first().members.size, draft.elements.size)
            assertInsideSafe(draft.elements, safe)
        }
    }

    private fun assertInsideSafe(elements: List<InputSurfaceElement>, safe: Rect) {
        val (w, _) = size
        for (e in elements) {
            assertTrue("${e.id} enters the left cutout", e.x * w >= safe.left - .5f)
            assertTrue("${e.id} enters the right cutout", (e.x + e.width) * w <= w - safe.right + .5f)
        }
    }

    @Test fun theEditorWindowCoversTheCutoutLikeTheGame() {
        val window = Robolectric.buildActivity(Activity::class.java).setup().get().window
        window.prepareInputEditor()
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, window.attributes.layoutInDisplayCutoutMode)
    }
}
