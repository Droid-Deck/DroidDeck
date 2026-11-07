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

    private fun overlapping(elements: List<InputSurfaceElement>, width: Int, height: Int): List<Pair<String, String>> {
        fun round(e: InputSurfaceElement) = e.buttonShape == InputButtonShape.CIRCLE && e.kind != InputElementKind.STICK
        val result = mutableListOf<Pair<String, String>>()
        for ((i, a) in elements.withIndex()) for (b in elements.drop(i + 1)) {
            val ax = (a.x + a.width / 2) * width; val ay = (a.y + a.height / 2) * height
            val bx = (b.x + b.width / 2) * width; val by = (b.y + b.height / 2) * height
            val hit = if (round(a) && round(b)) {
                val ra = minOf(a.width * width, a.height * height) / 2; val rb = minOf(b.width * width, b.height * height) / 2
                kotlin.math.hypot(ax - bx, ay - by) < ra + rb - .5f
            } else kotlin.math.abs(ax - bx) < (a.width + b.width) * width / 2 - .5f && kotlin.math.abs(ay - by) < (a.height + b.height) * height / 2 - .5f
            if (hit) result += a.id to b.id
        }
        return result
    }

    @Test fun generatedGamepadSpacingFollowsButtonSize() {
        for ((w, h) in listOf(1920 to 1080, 1280 to 960)) {
            val normal = LegacyGamepadLayout(context, w, h).elements()
            for (scale in listOf(.8f, 1.25f)) {
                val elements = LegacyGamepadLayout(context, w, h, scale = scale).elements()
                assertEquals("$w x $h at $scale", emptyList<Pair<String, String>>(), overlapping(elements, w, h))
                val face = listOf("legacy:a", "legacy:y").map { id -> elements.first { it.id == id } }
                val faceNormal = listOf("legacy:a", "legacy:y").map { id -> normal.first { it.id == id } }
                val gap = (face[0].y - face[1].y) / (faceNormal[0].y - faceNormal[1].y)
                if (scale > 1f) assertTrue(gap > 1f) else assertTrue(gap < 1f)
            }
        }
    }

    @Test fun scalingACustomLayoutScalesTheGapsBetweenNeighbours() {
        val left = listOf(InputSurfaceElement(InputControl.A, .05f, .5f, .08f, .1f, "one", name = "One"),
            InputSurfaceElement(InputControl.A, .16f, .5f, .08f, .1f, "two", name = "Two"))
        val right = listOf(InputSurfaceElement(InputControl.A, .7f, .2f, .1f, .1f, "three", name = "Three"),
            InputSurfaceElement(InputControl.A, .85f, .2f, .1f, .1f, "four", name = "Four"))
        for (pair in listOf(left, right)) {
            val gap = pair[1].x - (pair[0].x + pair[0].width)
            val bigger = pair.scaled(1.25f)
            assertEquals(gap * 1.25f, bigger[1].x - (bigger[0].x + bigger[0].width), .0001f)
            val smaller = pair.scaled(.8f)
            assertEquals(gap * .8f, smaller[1].x - (smaller[0].x + smaller[0].width), .0001f)
            assertTrue((bigger + smaller).all { it.x >= 0f && it.x + it.width <= 1.0001f && it.y >= 0f && it.y + it.height <= 1.0001f })
        }
    }

    @Test fun aScaledGamepadStaysGeneratedAndRegeneratesOnRotation() {
        val (display, metrics) = InputLibraries.primaryDisplay(context)
        val long = maxOf(metrics.widthPixels, metrics.heightPixels)
        val short = minOf(metrics.widthPixels, metrics.heightPixels)
        val gamepad = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }
        val scaled = gamepad.copy(scale = 1.25f, elements = LegacyGamepadLayout(display, long, short, scale = 1.25f).regenerate(gamepad.elements))
        assertEquals(long to short, OnScreenLayout.generatedSize(context, scaled, long, short))
        val rotated = OnScreenLayout.elements(context, scaled, short, long, android.graphics.Rect())
        assertEquals(LegacyGamepadLayout(context, short, long, scale = 1.25f).regenerate(scaled.elements), rotated)
        assertNull(OnScreenLayout.generatedSize(context, scaled.copy(scale = 1f), long, short))
        InputLibraries.update(context) { it.withLayout(scaled) }
        assertEquals(1.25f, InputLibraries.read(context).layouts.first { it.id == gamepad.id }.scale)
    }
}
