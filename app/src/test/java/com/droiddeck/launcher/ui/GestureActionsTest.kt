package com.droiddeck.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class GestureActionsTest {
    @Test fun categoryActionsAreAlphabeticalIncludingFilteredResults() {
        val actions = listOf("Z", "C", "A", "B").map { GestureAction("key_$it", it, GestureActionGroup.LETTERS) } +
            listOf("Right click", "Middle click", "Left click").map { GestureAction(it, it, GestureActionGroup.MOUSE) }
        assertEquals(listOf("A", "B", "C", "Z"), filterGestureActions(actions, "", GestureActionGroup.LETTERS).map { it.title })
        assertEquals(listOf("Left click", "Middle click", "Right click"), filterGestureActions(actions, "click", null).map { it.title })
    }

    @Test fun numberedFunctionKeysStayInNumericOrder() {
        val actions = listOf(12, 2, 10, 1).map { GestureAction("key_F$it", "F$it", GestureActionGroup.FUNCTION) }
        assertEquals(listOf("F1", "F2", "F10", "F12"), filterGestureActions(actions, "", null).map { it.title })
    }

    @Test fun actionGroupsKeepKeysAndMouseBindingsInTheirOwnCategories() {
        assertEquals(GestureActionGroup.MOUSE, gestureActionGroup("middle_mouse_pan"))
        assertEquals(GestureActionGroup.LETTERS, gestureActionGroup("key_F"))
        assertEquals(GestureActionGroup.FUNCTION, gestureActionGroup("key_F12"))
        assertEquals(GestureActionGroup.NUMPAD, gestureActionGroup("key_KP_ENTER"))
        assertEquals(GestureActionGroup.MODIFIERS, gestureActionGroup("key_CTRL_R"))
    }

    @Test fun searchCombinesWordsAndCategoryWithoutChangingTheSelection() {
        val actions = listOf(
            GestureAction("key_ENTER", "Enter", GestureActionGroup.NAVIGATION),
            GestureAction("key_KP_ENTER", "Numpad Enter", GestureActionGroup.NUMPAD),
            GestureAction("key_KP_1", "Numpad 1", GestureActionGroup.NUMPAD),
        )
        assertEquals(listOf(actions[1]), filterGestureActions(actions, " ENTER numpad ", null))
        assertEquals(listOf(actions[1]), filterGestureActions(actions, "enter", GestureActionGroup.NUMPAD))
        assertTrue(filterGestureActions(actions, "not a key", null).isEmpty())
    }
}
