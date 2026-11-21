package com.droiddeck.launcher.ui

import com.droiddeck.launcher.input.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SurfaceDraftHandleTest {
    @get:Rule val looper = MainLooperDrain()

    private val buried = InputSurfaceElement(InputControl.A, .48f, .01f, .03f, .04f, id = "buried")
    private val beside = InputSurfaceElement(InputControl.B, .46f, .01f, .1f, .04f, id = "beside")
    private fun draft() = SurfaceDraft(SavedInputLayout("l", "L", listOf(buried, beside), template = InputLayoutTemplate.CUSTOM),
        SavedInputBindings("b", "B", false, "l"))

    @Test fun aControlWhollyUnderTheHandleMovesBelowItAndOnePartlyUnderStays() {
        val draft = draft()
        draft.handleReach = SurfaceBounds(.47f, 0f, .53f, .08f)
        draft.clearHandle()
        assertEquals(.08f, draft.elements.first { it.id == "buried" }.y, 1e-4f)
        assertEquals(beside, draft.elements.first { it.id == "beside" })
    }

    @Test fun aRemovedControlUnderTheHandleStaysUntilItIsRestored() {
        val draft = draft()
        draft.removed = draft.removed + "buried"
        draft.handleReach = SurfaceBounds(.47f, 0f, .53f, .08f)
        draft.clearHandle()
        assertEquals(.01f, draft.elements.first { it.id == "buried" }.y, 1e-4f)
        draft.removed = draft.removed - "buried"
        draft.clearHandle()
        assertEquals(.08f, draft.elements.first { it.id == "buried" }.y, 1e-4f)
    }

    @Test fun undoAndRedoEachCountAsARestore() {
        val draft = draft()
        draft.checkpoint()
        draft.elements = draft.elements.map { it.copy(x = it.x + .1f) }
        val before = draft.restored
        draft.undo()
        draft.redo()
        assertEquals(before + 2, draft.restored)
        assertTrue(draft.elements.all { it.x > .5f })
    }

    @Test fun growingAControlThatCannotGrowKeepsRedo() {
        val tall = InputSurfaceElement(InputControl.A, .37f, 0f, .13f, 1f, id = "tall")
        val draft = SurfaceDraft(SavedInputLayout("t", "T", listOf(tall), template = InputLayoutTemplate.CUSTOM), SavedInputBindings("b", "B", false, "t"))
        draft.select(listOf("tall"))
        draft.resizeSelection(95)
        draft.endSlide()
        draft.undo()
        assertEquals(1, draft.redoable.size)
        draft.resizeSelection(105)
        assertEquals(1, draft.redoable.size)
        assertEquals(listOf(tall), draft.elements)
        draft.redo()
        assertEquals(.95f, draft.elements.single().height, 1e-4f)
    }
}
