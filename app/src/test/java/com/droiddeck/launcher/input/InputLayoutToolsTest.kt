package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InputLayoutToolsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun scaleAllResizesEveryElementAroundItsCentreInsideTheScreen() {
        val elements = listOf(InputSurfaceElement(InputControl.A, .4f, .4f, .2f, .2f, "a", name = "A"),
            InputSurfaceElement(InputControl.A, 0f, .9f, .1f, .1f, "b", name = "B"))
        val bigger = elements.scaled(1.25f)
        assertEquals(.25f, bigger[0].width, .0001f)
        assertEquals(.5f, bigger[0].x + bigger[0].width / 2, .0001f)
        assertEquals(.5f, bigger[0].y + bigger[0].height / 2, .0001f)
        assertEquals(0f, bigger[1].x, .0001f)
        assertEquals(1f, bigger[1].y + bigger[1].height, .0001f)
        assertTrue(bigger.all { it == it.bounded() })
        assertEquals(.16f, elements.scaled(.8f)[0].height, .0001f)
        assertEquals(elements.map { it.name }, bigger.map { it.name })
    }

    @Test fun resetPositionsOnlyRestoresGeometryOfElementsTheDefaultsKnow() {
        val defaults = listOf(InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, "legacy:a"))
        val moved = listOf(defaults[0].copy(x = .5f, y = .6f, width = .3f, name = "Jump", opacity = .5f),
            InputSurfaceElement(InputControl.A, .3f, .3f, .1f, .1f, "custom", name = "Custom"))
        val reset = moved.withPositionsOf(defaults)
        assertEquals(defaults[0].copy(name = "Jump", opacity = .5f), reset[0])
        assertEquals(moved[1], reset[1])
    }

    @Test fun copiesRememberTheirOriginSoStockPositionsCanBeRestored() {
        val library = InputLibraries.read(context)
        val copied = library.copyLayout(InputLibraries.DECK, "Mine", "mine")
        val mine = copied.layouts.first { it.id == "mine" }
        assertEquals(InputLibraries.DECK, mine.origin)
        assertEquals(InputLibraries.DECK, copied.copyLayout("mine", "Again", "again").layouts.first { it.id == "again" }.origin)
        val independent = library.useLayout(InputProfile.GAME, InputRole.SECONDARY, InputLibraries.DECK, shared = false)
        assertEquals(InputLibraries.DECK, independent.layout(InputProfile.GAME, InputRole.SECONDARY)!!.origin)
        val stock = InputStockLayouts.layouts().first { it.id == InputLibraries.DECK }.elements
        val dragged = mine.copy(elements = mine.elements.map { it.copy(x = 0f, y = 0f) })
        assertEquals(stock, InputLibraries.defaultPositions(context, copied, dragged))
        assertEquals(mine.elements, dragged.elements.withPositionsOf(stock))
        assertEquals(copied, InputLibraries.decode(InputLibraries.encode(copied)))
    }

    @Test fun builtInGamepadDefaultsComeFromTheGamepadGenerator() {
        val library = InputLibraries.read(context)
        val gamepad = library.layouts.first { it.id == InputLibraries.OVERLAY }
        val defaults = InputLibraries.defaultPositions(context, library, gamepad)!!
        assertEquals(gamepad.elements.map { it.id }.toSet(), defaults.map { it.id }.toSet())
        assertNull(InputLibraries.defaultPositions(context, library, SavedInputLayout("new", "New")))
    }

    @Test fun quickButtonsHideWithoutRemovingElementsAndPersist() {
        val library = InputLibraries.read(context)
        val gamepad = library.layouts.first { it.id == InputLibraries.OVERLAY }
        assertTrue(InputCommand.KEYBOARD in gamepad.quickButtons)
        assertTrue(InputLibraries.setQuickButton(context, gamepad.id, InputCommand.KEYBOARD, false))
        val hidden = InputLibraries.read(context).layouts.first { it.id == gamepad.id }
        assertEquals(gamepad.elements, hidden.elements)
        assertEquals(setOf(InputCommand.KEYBOARD), hidden.style.hidden)
        val shown = hidden.style.shown(hidden.elements)
        assertEquals(gamepad.elements.filterNot { it.id == "builtin:keyboard" }, shown)
        assertTrue(InputLibraries.setQuickButton(context, gamepad.id, InputCommand.KEYBOARD, true))
        assertEquals(gamepad, InputLibraries.read(context).layouts.first { it.id == gamepad.id })
    }

    @Test fun onlySteamQuickAccessAndKeyboardCanBeHidden() {
        val library = InputLibraries.read(context)
        val gamepad = library.layouts.first { it.id == InputLibraries.OVERLAY }
        assertThrows(IllegalArgumentException::class.java) {
            library.withLayout(gamepad.copy(style = gamepad.style.copy(hidden = setOf(InputCommand.MENU)))).validate()
        }
        val custom = InputSurfaceElement(InputControl.A, .1f, .1f, .1f, .1f, "custom", name = "Steam")
        assertFalse(InputSurfaceStyle(hidden = QUICK_BUTTONS.toSet()).hides(custom))
    }
}
