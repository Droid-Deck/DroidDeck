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

    @Test fun duplicateKeepsEverythingButIdNameAndPlacement() {
        val source = InputSurfaceElement(InputControl.A, .4f, .4f, .2f, .2f, "a", name = "Jump", opacity = .5f, toggle = true, buttonShape = InputButtonShape.CIRCLE, image = "x.png")
        val copy = source.duplicate("b", listOf(source))
        assertEquals(source.copy(id = "b", name = "Jump 2", x = .44f, y = .44f), copy)
    }

    @Test fun duplicateNamesAreUniqueAndIgnoreExistingNumbers() {
        val a = InputSurfaceElement(InputControl.A, .1f, .1f, .1f, .1f, "a", name = "Jump 2")
        val b = a.copy(id = "b", name = "Jump")
        val copy = a.duplicate("c", listOf(a, b, a.copy(id = "d", name = "Jump 3")))
        assertEquals("Jump 4", copy.name)
        assertEquals("Jump 2", b.duplicate("e", listOf(b)).name)
    }

    @Test fun duplicateStaysInsideTheScreenForEveryKind() {
        for (kind in InputElementKind.entries) {
            val edge = InputSurfaceElement(kind.control, .85f, .85f, .15f, .15f, "e", kind, "Edge")
            val copy = edge.duplicate("f", listOf(edge))
            assertEquals(kind, copy.kind)
            assertEquals(copy, copy.bounded())
            assertTrue(copy.x < edge.x && copy.y < edge.y)
            assertNotEquals(edge.id, copy.id)
        }
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

    private fun assertCross(elements: List<InputSurfaceElement>, width: Int, height: Int, ids: List<String>) {
        val (up, right, down, left) = ids.map { id -> elements.first { it.id == id }.let { (it.x + it.width / 2) * width to (it.y + it.height / 2) * height } }
        val cx = (up.first + right.first + down.first + left.first) / 4
        val cy = (up.second + right.second + down.second + left.second) / 4
        assertEquals(up.first, down.first, 1f)
        assertEquals(left.second, right.second, 1f)
        assertTrue(up.second < down.second && left.first < right.first)
        val reach = listOf(up, right, down, left).map { kotlin.math.hypot(it.first - cx, it.second - cy) }
        reach.forEach { assertEquals(reach[0], it, 1f) }
    }

    private fun assertGamepadShape(elements: List<InputSurfaceElement>, width: Int, height: Int, label: String) {
        assertCross(elements, width, height, listOf("legacy:y", "legacy:b", "legacy:a", "legacy:x"))
        assertCross(elements, width, height, listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left"))
        assertEquals(label, emptyList<Pair<String, String>>(), overlapping(elements, width, height))
    }

    @Test @Config(qualifiers = "w833dp-h468dp-land-369dpi")
    fun buttonSizeKeepsTheGamepadShapeAtHandheldDensity() {
        for ((w, h) in listOf(1920 to 1080, 1920 to 1200)) {
            val normal = LegacyGamepadLayout(context, w, h).elements()
            assertGamepadShape(normal, w, h, "$w x $h")
            val right = setOf("legacy:a", "legacy:b", "legacy:x", "legacy:y", "legacy:rs", "legacy:rb", "legacy:rt", "legacy:start")
            val left = setOf("legacy:up", "legacy:right", "legacy:down", "legacy:left", "legacy:ls", "legacy:lb", "legacy:lt", "legacy:select")
            val moved = normal.map { e ->
                when (e.id) {
                    in right -> e.copy(x = e.x + 21f / w, y = e.y + 18f / h)
                    in left -> e.copy(x = e.x - 20f / w, y = e.y + 15f / h)
                    else -> e
                }
            }
            for (scale in listOf(.8f, 1.25f)) {
                val generator = LegacyGamepadLayout(context, w, h, scale = scale)
                assertGamepadShape(generator.elements(), w, h, "$w x $h generated at $scale")
                val rescaled = generator.rescale(moved, LegacyGamepadLayout(context, w, h))
                assertGamepadShape(rescaled, w, h, "$w x $h moved at $scale")
                val a = rescaled.first { it.id == "legacy:a" }
                val generatedA = generator.elements().first { it.id == "legacy:a" }
                assertEquals(generatedA.x + 21f / w, a.x, .001f)
                assertEquals(generatedA.y + 18f / h, a.y, .001f)
                assertEquals(moved, LegacyGamepadLayout(context, w, h).rescale(rescaled, generator).let { back ->
                    back.zip(moved).map { (b, m) -> if (kotlin.math.abs(b.x - m.x) < .001f && kotlin.math.abs(b.y - m.y) < .001f && kotlin.math.abs(b.width - m.width) < .001f) m else b }
                })
            }
        }
    }

    @Test fun duplicatingLegacyElementsKeepsTheirBehaviourAndSavesValidly() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!.let { it.copy(elements = InputSurfaceLayout.defaults().filter { e -> e.control in listOf(InputControl.B, InputControl.RIGHT_PAD) }) }
        val base = library.preset(library.profile("game").binding(InputRole.SECONDARY))
        for (source in layout.elements) {
            val copy = source.duplicate("copy", layout.elements)
            assertEquals(copy.kind.control, copy.control)
            val actions = mapOf(copy.id to base.action(source).carriedTo(source))
            val clicks = if (source.hasClick) mapOf(copy.id to base.clickAction(source)) else emptyMap()
            val preset = base.copy(elements = actions, clicks = clicks)
            library.withLayout(layout.copy(elements = layout.elements + copy)).withPreset(preset).validate()
            assertEquals(InputAction.Native(source.control), preset.action(copy))
            if (source.hasClick) assertEquals(base.clickAction(source), preset.clickAction(copy))
        }
    }

    @Test fun duplicatingElementsWithExplicitBindingsCopiesThemAndLeavesOthersUntouched() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!.let { it.copy(elements = InputSurfaceLayout.defaults().filter { e -> e.control in listOf(InputControl.B, InputControl.RIGHT_PAD) }) }
        val base = library.preset(library.profile("game").binding(InputRole.SECONDARY))
        val button = layout.elements.first { it.control == InputControl.B }
        val pad = layout.elements.first { it.control == InputControl.RIGHT_PAD }
        val custom = base.copy(
            elements = mapOf(button.id to InputAction.Keys(listOf(30)), pad.id to InputAction.Pointer(500f)),
            clicks = mapOf(pad.id to InputAction.MouseButton(273)),
        )
        val other = base.copy(elements = mapOf(button.id to InputAction.Keys(listOf(31))))
        var elements = layout.elements
        var actions = custom.elements
        var clicks = custom.clicks
        for (source in listOf(button, pad)) {
            val copy = source.duplicate("copy-${source.id}", elements)
            actions = actions + (copy.id to custom.copy(elements = actions).action(source).carriedTo(source))
            if (source.hasClick) clicks = clicks + (copy.id to custom.copy(elements = actions, clicks = clicks).clickAction(source))
            elements = elements + copy
        }
        val result = custom.copy(elements = actions, clicks = clicks)
        library.withLayout(layout.copy(elements = elements)).withPreset(result).validate()
        assertEquals(InputAction.Keys(listOf(30)), result.action(elements.first { it.id == "copy-${button.id}" }))
        assertEquals(InputAction.Pointer(500f), result.action(elements.first { it.id == "copy-${pad.id}" }))
        assertEquals(InputAction.MouseButton(273), result.clickAction(elements.first { it.id == "copy-${pad.id}" }))
        assertEquals(custom.action(button), result.action(button))
        assertEquals(custom.action(pad), result.action(pad))
        assertEquals(custom.clickAction(pad), result.clickAction(pad))
        assertEquals(InputAction.Keys(listOf(31)), other.action(button))
        assertEquals(base.action(pad), other.action(pad))
    }

    @Test fun duplicateNamesNeverExceedTheNameLimit() {
        for (length in 70..80) {
            val source = InputSurfaceElement(InputControl.A, .1f, .1f, .1f, .1f, "a", name = "x".repeat(length))
            var siblings = listOf(source)
            repeat(12) {
                val copy = source.duplicate("c$it", siblings)
                assertTrue("$length: ${copy.name.length}", copy.name.length <= 80)
                assertTrue(copy.name.trim() == copy.name)
                assertTrue(siblings.none { s -> s.name == copy.name })
                siblings = siblings + copy
            }
        }
    }

    @Test fun repeatedDuplicationAtTheCornerNeverStacksElements() {
        val source = InputSurfaceElement(InputControl.A, .75f, .75f, .25f, .25f, "a", name = "Jump")
        var all = listOf(source)
        repeat(10) {
            val copy = all.last().duplicate("c$it", all)
            assertEquals(copy, copy.bounded())
            assertTrue(all.none { e -> e.x == copy.x && e.y == copy.y })
            all = all + copy
        }
    }
}
