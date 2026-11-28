package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
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
class OnScreenLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
    private val density get() = context.resources.displayMetrics.density

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller", "session")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun gamepad() = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }

    private fun px(e: InputSurfaceElement, w: Int, h: Int) = floatArrayOf(e.x * w, e.y * h, e.width * w, e.height * h)

    @Test fun dockActionsFitBetweenTheShoulderRowControls() {
        val elements = listOf(
            InputSurfaceElement(InputControl.LB, .18f, .10f, .10f, .10f),
            InputSurfaceElement(InputControl.SELECT, .30f, .13f, .04f, .04f),
            InputSurfaceElement(InputControl.START, .66f, .13f, .04f, .04f),
        )
        assertEquals(452 to 66, OnScreenLayout.dockActionsPosition(elements, 1000, 600, Rect(), 96, 48, 8))
        val blocked = elements + InputSurfaceElement(InputControl.A, .43f, .08f, .14f, .15f)
        val (x, y) = OnScreenLayout.dockActionsPosition(blocked, 1000, 600, Rect(), 96, 48, 8)
        assertTrue(x + 96 <= 430 || x >= 570 || y + 48 <= 48 || y >= 138)
    }

    private val shortcuts = setOf(InputControl.SELECT, InputControl.START, InputControl.GUIDE, InputControl.QAM)

    private fun assertOnlyShortcutPositionsChanged(before: List<InputSurfaceElement>, after: List<InputSurfaceElement>) {
        assertEquals(before.map { it.id }, after.map { it.id })
        for ((original, placed) in before.zip(after)) {
            val expected = if (original.control in shortcuts) original.copy(x = placed.x, y = placed.y) else original
            assertEquals(expected, placed)
        }
    }

    private fun assertSquare(e: InputSurfaceElement, w: Int, h: Int) = assertEquals(e.width * w, e.height * h, 1.5f)

    private fun assertInside(elements: List<InputSurfaceElement>, w: Int, h: Int, safe: Rect = Rect()) {
        for (e in elements) {
            val (x, y, ew, eh) = px(e, w, h).toList()
            assertTrue("${e.id} left", x >= safe.left - .5f)
            assertTrue("${e.id} top", y >= safe.top - .5f)
            assertTrue("${e.id} right", x + ew <= w - safe.right + .5f)
            assertTrue("${e.id} bottom", y + eh <= h - safe.bottom + .5f)
        }
    }

    @Test fun theFloatingSteamPadOffersIndependentVisibilityAndLowersOnlyTheDefaultShortcuts() {
        val w = 1280
        val h = 960
        val safe = Rect(24, 8, 12, 16)
        val layout = gamepad()
        val original = LegacyGamepadLayout(context, w, h, safe).regenerate(layout.elements)
        val extraRear = setOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)
        val extraPads = setOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD)
        fun placed(rear: Boolean, pads: Boolean) =
            OnScreenLayout.elements(context, layout, w, h, safe, rearButtons = rear, trackpads = pads)
        for (type in listOf(SessionPrefs.CONTROLLER_DECK, SessionPrefs.CONTROLLER_STEAM)) {
            SessionPrefs.setSteamController(context, type)
            val base = placed(false, false)
            for (control in listOf(InputControl.SELECT, InputControl.START)) {
                val old = original.single { it.control == control }
                val moved = base.single { it.control == control }
                assertTrue(control.id, moved.y > old.y)
            }
            val standard = base.map { it.control }.toSet()
            assertEquals(extraRear, placed(true, false).map { it.control }.toSet() - standard)
            assertEquals(extraPads, placed(false, true).map { it.control }.toSet() - standard)
            val both = placed(true, true)
            assertEquals(extraRear + extraPads, both.map { it.control }.toSet() - standard)
            assertInside(both, w, h, safe)
            assertEquals(base, both.filter { it.control in standard })
        }
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        assertEquals(original.map { it.id }, placed(true, true).map { it.id })
        assertEquals(placed(false, false), placed(true, true))
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val edited = layout.copy(elements = layout.elements.map { e -> if (e.control == InputControl.START) e.copy(y = .3f) else e }, aspect = w.toFloat() / h)
        val fitted = OnScreenLayout.elements(context, edited, w, h, safe)
        assertTrue(fitted.single { it.control == InputControl.START }.y < .5f)
    }

    @Test fun theBuiltInGamepadIsRegeneratedForEverySizeWithoutStretching() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        val layout = gamepad()
        for ((w, h) in listOf(1200 to 540, 540 to 1200, 900 to 850)) {
            val placed = OnScreenLayout.elements(context, layout, w, h, Rect())
            assertEquals(layout.elements.map { it.id }, placed.map { it.id })
            assertEquals(LegacyGamepadLayout(context, w, h).regenerate(layout.elements), placed)
            for (id in listOf("legacy:a", "legacy:up", "legacy:guide")) assertSquare(placed.first { it.id == id }, w, h)
            assertInside(placed, w, h)
        }
    }

    @Test fun changingControllerSizeRegeneratesAnUneditedBuiltInLandscapeOverlay() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val layout = gamepad()
        val w = 1200
        val h = 540
        ControllerPrefs.setSize(context, 100)
        val original = OnScreenLayout.elements(context, layout, w, h, Rect())
        ControllerPrefs.setSize(context, 115)
        val resized = OnScreenLayout.elements(context, layout, w, h, Rect())
        assertNotEquals(original.single { it.id == "legacy:a" }.width, resized.single { it.id == "legacy:a" }.width, .0001f)
        assertNotEquals(original.single { it.id == "legacy:ls" }.height, resized.single { it.id == "legacy:ls" }.height, .0001f)
    }

    @Test fun unchangedSizeDoesNotCollapseCoincidentEditedPortraitControls() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val stock = gamepad()
        val coincident = stock.elements.map { element ->
            if (element.id == "legacy:a" || element.id == "legacy:b") {
                element.copy(x = .2f, y = .2f, width = .1f, height = .1f)
            } else element
        }
        val edited = stock.copy(elements = coincident, aspect = .5f)
        ControllerPrefs.setSize(context, 100)
        val placed = OnScreenLayout.elements(
            context, edited, 540, 1080, Rect(),
            dock = OnScreenLayout.Dock(true, false),
        )
        assertTrue(placed.at(InputControl.A).width > 0f)
        assertTrue(placed.at(InputControl.B).width > 0f)
    }

    @Test fun changingControllerSizeResizesDefaultDockedControlsWithoutASavedLayout() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val layout = gamepad()
        val w = 1200
        val h = 540
        val dock = OnScreenLayout.Dock(false, false)
        ControllerPrefs.setSize(context, 100)
        val original = OnScreenLayout.elements(context, layout, w, h, Rect(), dock = dock)
        ControllerPrefs.setSize(context, 125)
        val resized = OnScreenLayout.elements(context, layout, w, h, Rect(), dock = dock)
        assertNotEquals(original.at(InputControl.LS).width, resized.at(InputControl.LS).width, .0001f)
        assertNotEquals(original.at(InputControl.A).height, resized.at(InputControl.A).height, .0001f)
        assertInside(resized, w, h)
    }

    @Test fun changingControllerSizeRegeneratesSavedLandscapeBuiltInOverlay() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val layout = gamepad().copy(aspect = 2f)
        val w = 1200
        val h = 540
        ControllerPrefs.setSize(context, 100)
        val original = OnScreenLayout.elements(context, layout, w, h, Rect())
        ControllerPrefs.setSize(context, 125)
        val resized = OnScreenLayout.elements(context, layout, w, h, Rect())
        assertNotEquals(original.at(InputControl.A).width, resized.at(InputControl.A).width, .0001f)
    }

    @Test fun enlargedDefaultDockedControlsDoNotOverlapTheActionButtonAndRightStick() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        ControllerPrefs.setSize(context, 125)
        val placed = OnScreenLayout.elements(
            context, gamepad(), 1200, 540, Rect(),
            dock = OnScreenLayout.Dock(false, false),
        )
        val stick = placed.at(InputControl.RS)
        val button = placed.at(InputControl.A)
        val overlapX = minOf(stick.x + stick.width, button.x + button.width) - maxOf(stick.x, button.x)
        val overlapY = minOf(stick.y + stick.height, button.y + button.height) - maxOf(stick.y, button.y)
        assertFalse("RS and A hit rectangles must not overlap", overlapX > 0f && overlapY > 0f)
    }

    @Test fun changingControllerSizeResizesDockedControlsWithASavedLayout() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val layout = gamepad()
        val w = 1200
        val h = 540
        ControllerPrefs.setLayout(context, w, h, mapOf("ls" to (.12f to .7f)))
        val dock = OnScreenLayout.Dock(false, false)
        ControllerPrefs.setSize(context, 100)
        val original = OnScreenLayout.elements(context, layout, w, h, Rect(), dock = dock)
        ControllerPrefs.setSize(context, 120)
        val resized = OnScreenLayout.elements(context, layout, w, h, Rect(), dock = dock)
        val before = original.single { it.id == "legacy:a" }
        val after = resized.single { it.id == "legacy:a" }
        assertTrue(after.width > before.width)
        assertTrue(after.height > before.height)
        assertEquals(before.x + before.width / 2f, after.x + after.width / 2f, .001f)
        assertEquals(before.y + before.height / 2f, after.y + after.height / 2f, .001f)
        assertEquals(mapOf("ls" to (.12f to .7f)), ControllerPrefs.layout(context, w, h))
    }

    @Test fun enlargedDockedControlsStayInsideSafeAreaAndKeepShoulderGaps() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val w = 1080
        val h = 200
        val safe = Rect(20, 0, 20, 24)
        ControllerPrefs.setLayout(context, w, h, mapOf("ls" to (.12f to .7f)))
        ControllerPrefs.setSize(context, 125)
        val placed = OnScreenLayout.elements(context, gamepad(), w, h, safe, dock = OnScreenLayout.Dock(false, false))
        assertInside(placed, w, h, safe)
        for ((left, right) in listOf(InputControl.LT to InputControl.LB, InputControl.RB to InputControl.RT)) {
            val a = placed.single { it.control == left }
            val b = placed.single { it.control == right }
            assertTrue("$left should remain left of $right", a.x + a.width <= b.x)
            assertTrue("$left and $right should retain a gap", (b.x - a.x - a.width) * w >= 3.5f)
        }
    }

    @Test @Config(qualifiers = "w745dp-h343dp-land-420dpi")
    fun aBuiltInGamepadSavedAtTheDisplaySizeRegeneratesForTheMeasuredSurface() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        val saved = gamepad().copy(elements = LegacyGamepadLayout(context, 2608, 1200).elements(), aspect = 2608f / 1200)
        val placed = OnScreenLayout.elements(context, saved, 2048, 1200, Rect())
        assertEquals(LegacyGamepadLayout(context, 2048, 1200).regenerate(saved.elements), placed)
    }

    @Test @Config(qualifiers = "w745dp-h343dp-land-420dpi")
    fun previouslyGeneratedEdgeSticksRegenerateWithoutOverridingCustomGeometry() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_STEAM)
        val w = 2608
        val h = 1200
        ControllerPrefs.setLayout(context, w, h, mapOf("ls" to (0f to .5f), "rs" to (1f to .5f)))
        val generator = LegacyGamepadLayout(context, w, h)
        val old = generator.elements(previousStickGeometry = true)
        val current = generator.elements()
        assertTrue(old.filter { it.control == InputControl.LS || it.control == InputControl.RS }.zip(current.filter { it.control == InputControl.LS || it.control == InputControl.RS }).any { (a, b) -> kotlin.math.abs(a.width - b.width) > .01f })
        val saved = gamepad().copy(elements = old, aspect = w.toFloat() / h)
        assertOnlyShortcutPositionsChanged(LegacyGamepadLayout(context, 2048, h).regenerate(old), OnScreenLayout.elements(context, saved, 2048, h, Rect()))
        val custom = saved.copy(elements = old.map { if (it.id == "legacy:a") it.copy(x = .4f) else it })
        assertEquals(OnScreenLayout.fit(custom.elements, custom.aspect, 1f, 2048, h, Rect()), OnScreenLayout.elements(context, custom, 2048, h, Rect()))
    }

    @Test fun savingTheEditorFrameKeepsEveryControlWhereTheEditorShowsIt() {
        val w = 2608
        val h = 1200
        val layout = SavedInputLayout("custom", "Custom", listOf(
            InputSurfaceElement(InputControl.A, .1f, .2f, .1f, .2f, "custom:a"),
            InputSurfaceElement(InputControl.B, .7f, .6f, .1f, .2f, "custom:b")), aspect = 16f / 9f)
        val displayed = OnScreenLayout.editorElements(context, layout, w, h, edited = true)
        val moved = displayed.map { if (it.id == "custom:a") it.copy(x = .4f) else it }
        val frame = OnScreenLayout.editorFrame(layout, moved + InputSurfaceElement(InputControl.X, .5f, .5f, .1f, .1f, "builtin:keyboard"))
        assertEquals(layout.elements.map { it.id }, frame.map { it.id })
        val saved = layout.copy(elements = frame, aspect = w.toFloat() / h)
        val game = OnScreenLayout.elements(context, saved, w, h, Rect())
        val editor = OnScreenLayout.editorElements(context, saved, w, h, edited = true)
        for (shown in moved) {
            assertArrayEquals(px(shown, w, h), px(game.first { it.id == shown.id }, w, h), .001f)
            assertArrayEquals(px(shown, w, h), px(editor.first { it.id == shown.id }, w, h), .001f)
        }
    }

    @Test fun editorAndGameUseTheSamePixelRectsOnWideAndStandardScreens() {
        val layout = gamepad()
        for ((w, h) in listOf(2000 to 900, 1600 to 900)) {
            val game = OnScreenLayout.elements(context, layout, w, h, Rect())
            val editor = OnScreenLayout.editorElements(context, layout, w, h)
            assertEquals(game.map { it.id }, editor.map { it.id })
            game.zip(editor).forEach { (gameElement, editorElement) ->
                assertArrayEquals(px(gameElement, w, h), px(editorElement, w, h), .001f)
            }
        }
    }

    @Test fun editedGeneratedAndCustomLayoutsKeepTheirPixelRectsAcrossRefreshes() {
        for ((w, h) in listOf(2000 to 900, 1600 to 900)) {
            val generated = gamepad()
            val generatedEdit = generated.elements.map { if (it.id == "legacy:a") it.copy(x = .3f, y = .4f, width = .12f, height = .12f) else it }
            val generatedSaved = generated.copy(elements = generatedEdit, aspect = w.toFloat() / h)
            val generatedPixels = px(OnScreenLayout.elements(context, generatedSaved, w, h, Rect()).first { it.id == "legacy:a" }, w, h)
            val generatedRefresh = px(OnScreenLayout.editorElements(context, generatedSaved, w, h, edited = true).first { it.id == "legacy:a" }, w, h)
            assertArrayEquals(generatedPixels, generatedRefresh, .001f)

            val custom = SavedInputLayout("custom", "Custom", listOf(InputSurfaceElement(InputControl.A, .3f, .4f, .12f, .12f)), aspect = w.toFloat() / h)
            val customRefresh = OnScreenLayout.editorElements(context, custom, w, h, edited = true)
            assertArrayEquals(px(customRefresh.single(), w, h), px(OnScreenLayout.editorElements(context, custom.copy(elements = customRefresh), w, h, edited = true).single(), w, h), .001f)
        }
    }

    @Test fun resetPositionsRestoresGeneratedGeometryAfterAnEdit() {
        for ((w, h) in listOf(2000 to 900, 1600 to 900)) {
            val original = gamepad()
            val moved = original.copy(elements = original.elements.map { if (it.id == "legacy:a") it.copy(x = .3f) else it }, aspect = w.toFloat() / h)
            val reset = LegacyGamepadLayout(context, w, h).regenerate(moved.elements)
            assertEquals(OnScreenLayout.elements(context, original, w, h, Rect()), OnScreenLayout.elements(context, moved.copy(elements = reset), w, h, Rect()))
        }
    }

    @Test fun regenerationKeepsTheUsersButtonStylesAndRemovedButtons() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        val layout = gamepad().let { g -> g.copy(elements = g.elements.filterNot { it.id == "legacy:y" }.map { if (it.id == "legacy:a") it.copy(opacity = .4f, icon = 0, name = "Jump") else it }) }
        val placed = OnScreenLayout.elements(context, layout, 2000, 1000, Rect())
        assertNull(placed.firstOrNull { it.id == "legacy:y" })
        val a = placed.first { it.id == "legacy:a" }
        assertEquals(.4f, a.opacity)
        assertEquals("Jump", a.name)
        assertEquals(LegacyGamepadLayout(context, 2000, 1000).elements().first { it.id == "legacy:a" }.x, a.x, .0001f)
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun theDefaultDeckUsesTheRedesignReferenceGeometry() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val placed = OnScreenLayout.elements(context, gamepad(), 1600, 900, Rect(), rearButtons = true, trackpads = true)
        fun center(control: InputControl): Pair<Float, Float> = placed.single { it.id == "legacy:${control.id}" }
            .let { (it.x + it.width / 2f) * 1600f to (it.y + it.height / 2f) * 900f }
        fun assertCenter(control: InputControl, x: Float, y: Float) {
            val (cx, cy) = center(control)
            assertEquals(control.id, x, cx, .01f)
            assertEquals(control.id, y, cy, .01f)
        }
        assertCenter(InputControl.LT, 120f, 96f)
        assertCenter(InputControl.LB, 310f, 96f)
        assertCenter(InputControl.RB, 1290f, 96f)
        assertCenter(InputControl.RT, 1480f, 96f)
        assertCenter(InputControl.UP, 220f, 242.5f)
        assertCenter(InputControl.RIGHT, 322.5f, 345f)
        assertCenter(InputControl.LS, 290f, 640f)
        assertCenter(InputControl.RS, 1310f, 640f)
        assertCenter(InputControl.LEFT_PAD, 470f, 360f)
        assertCenter(InputControl.RIGHT_PAD, 1130f, 360f)
        assertCenter(InputControl.SELECT, 660f, 850f)
        assertCenter(InputControl.START, 940f, 850f)
        assertInside(placed, 1600, 900)
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-xxxhdpi")
    fun theDeckShortcutRowStaysApartOnSmallDenseSurfaces() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val row = listOf("builtin:keyboard", "legacy:guide", "legacy:select", "legacy:start", "legacy:qam")
        for ((size, safe) in listOf(800 to 450 to Rect(), 600 to 450 to Rect(), 600 to 450 to Rect(0, 0, 120, 0),
            600 to 450 to Rect(120, 0, 0, 0), 1600 to 900 to Rect())) {
            val (w, h) = size
            val placed = OnScreenLayout.elements(context, gamepad(), w, h, safe)
            for ((left, right) in row.map { id -> placed.single { it.id == id } }.zipWithNext()) {
                assertTrue("${left.id} reaches ${right.id} at ${w}x$h $safe", (left.x + left.width) * w <= right.x * w + .5f)
            }
            assertInside(placed, w, h, safe)
        }
    }

    @Test fun dockedControlsStayInsideTheSafeAreaAtTheDefaultSizeWithoutAControllerKit() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        ControllerPrefs.setSize(context, 100)
        val safe = Rect(40, 0, 60, 24)
        for ((w, h) in listOf(1080 to 200, 1200 to 540, 2208 to 800)) {
            val placed = OnScreenLayout.elements(context, gamepad(), w, h, safe, dock = OnScreenLayout.Dock(false, false))
            assertInside(placed.filter { it.id.startsWith("legacy:") }, w, h, safe)
        }
    }

    @Test fun anEditedLayoutKeepsItsAspectAndCornersWhenTheScreenRotates() {
        val corner = InputSurfaceElement(InputControl.A, .85f, .7f, .1f, .2f)
        val left = InputSurfaceElement(InputControl.LS, .05f, .5f, .2f, .4f)
        val middle = InputSurfaceElement(InputControl.GUIDE, .45f, .05f, .1f, .2f)
        val layout = SavedInputLayout("edited", "Edited", listOf(left, corner, middle), aspect = 2f)
        assertEquals(layout.elements, OnScreenLayout.elements(context, layout, 2000, 1000, Rect()).map { it.copy(x = round(it.x), y = round(it.y), width = round(it.width), height = round(it.height)) })
        val portrait = OnScreenLayout.elements(context, layout, 1000, 2000, Rect())
        val (lx, ly, lw, lh) = px(portrait[0], 1000, 2000).toList()
        val (cx, cy, cw, ch) = px(portrait[1], 1000, 2000).toList()
        assertEquals(lw, lh, 1f)
        assertEquals(cw, ch, 1f)
        assertEquals(200f, lw, 1f)
        assertEquals(50f, lx, 1f)
        assertEquals(2000f, ly + lh + 50f, 1f)
        assertEquals(1000f - 50f, cx + cw, 1f)
        assertEquals(2000f - 50f, cy + ch, 1f)
        assertEquals(500f, px(portrait[2], 1000, 2000).let { it[0] + it[2] / 2f }, 1f)
        assertInside(portrait, 1000, 2000)
    }

    private fun round(v: Float) = Math.round(v * 10000f) / 10000f

    @Test fun controlsStayOutOfTheDisplayCutout() {
        val safe = Rect(120, 0, 0, 40)
        val generated = OnScreenLayout.elements(context, gamepad(), 2400, 1080, safe)
        assertInside(generated, 2400, 1080, safe)
        assertNotEquals(OnScreenLayout.elements(context, gamepad(), 2400, 1080, Rect()), generated)
        val edge = SavedInputLayout("edited", "Edited", listOf(InputSurfaceElement(InputControl.B, 0f, .9f, .05f, .1f)), aspect = 2400f / 1080)
        assertInside(OnScreenLayout.elements(context, edge, 2400, 1080, safe), 2400, 1080, safe)
    }

    @Test fun steamAndQamOnlyModePutsLargeButtonsInTheBottomCornersAndTheKeyboardBetween() {
        val safe = Rect(80, 0, 30, 20)
        val placed = OnScreenLayout.elements(context, gamepad(), 1000, 500, safe, buttonsOnly = true)
        assertEquals(setOf(InputControl.GUIDE, InputControl.QAM, InputControl.A), placed.map { it.control }.toSet())
        val radius = 30 * density
        val inset = 44 * density
        fun centre(e: InputSurfaceElement) = (e.x + e.width / 2f) * 1000 to (e.y + e.height / 2f) * 500
        val guide = placed.first { it.control == InputControl.GUIDE }
        val qam = placed.first { it.control == InputControl.QAM }
        val keyboard = placed.first { it.id == "builtin:keyboard" }
        assertEquals(2 * radius, guide.width * 1000, .5f)
        assertEquals(80 + inset, centre(guide).first, .5f)
        assertEquals(500 - 20 - inset, centre(guide).second, .5f)
        assertEquals(1000 - 30 - inset, centre(qam).first, .5f)
        assertEquals(500f, centre(keyboard).first, .5f)
        assertEquals(500 - 20 - inset, centre(keyboard).second, .5f)
        val user = SavedInputLayout("edited", "Edited", listOf(InputSurfaceElement(InputControl.A, .5f, .5f, .1f, .1f)))
        assertEquals(setOf(InputControl.GUIDE, InputControl.QAM), OnScreenLayout.elements(context, user, 2000, 1000, Rect(), buttonsOnly = true).map { it.control }.toSet())
    }

    @Test fun theUnfoldedStripUsesTheDockGeometryInLandscapeAndPortrait() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        for ((size, portrait) in listOf((2208 to 800) to false, (1080 to 1300) to true)) {
            val (w, h) = size
            val placed = OnScreenLayout.elements(context, gamepad(), w, h, Rect(), dock = OnScreenLayout.Dock(portrait, grips = true))
            val boxes = GamepadGeometry.layout(w.toFloat(), h.toFloat(), density, portrait)
            val ls = placed.first { it.id == "legacy:ls" }
            val box = boxes.getValue(GamepadGeometry.Slot.LEFT_MAIN)
            assertEquals(box.left, ls.x * w, 1f)
            assertEquals(box.width, ls.width * w, 1f)
            assertEquals(box.height, ls.height * h, 1f)
            assertSquare(placed.first { it.id == "legacy:a" }, w, h)
            assertNull(placed.firstOrNull { it.control == InputControl.GUIDE || it.control == InputControl.QAM })
            assertEquals(portrait, placed.any { it.control == InputControl.L4 })
            assertInside(placed, w, h)
            val solid = placed.filter { it.kind != InputElementKind.STICK }
            for (a in solid) for (b in solid) if (a !== b) {
                val overlap = if (a.buttonShape == InputButtonShape.CIRCLE && b.buttonShape == InputButtonShape.CIRCLE)
                    Math.hypot(((a.x + a.width / 2f) - (b.x + b.width / 2f)) * w.toDouble(), ((a.y + a.height / 2f) - (b.y + b.height / 2f)) * h.toDouble()) <
                        (a.width * w + b.width * w) / 2f - .5f
                else a.x < b.x + b.width - .0001f && b.x < a.x + a.width - .0001f && a.y < b.y + b.height - .0001f && b.y < a.y + a.height - .0001f
                assertFalse("${a.id} overlaps ${b.id}", overlap)
            }
        }
        val user = SavedInputLayout("edited", "Edited", listOf(InputSurfaceElement(InputControl.A, .8f, .6f, .1f, .2f)), aspect = 2208f / 1768)
        val fitted = OnScreenLayout.elements(context, user, 2208, 800, Rect(), dock = OnScreenLayout.Dock(false, false)).single()
        assertEquals(.1f * 2208f / 1768 / .2f, fitted.width * 2208 / (fitted.height * 800), .01f)
    }

    @Test fun controlsEditedInPortraitKeepTheirPlacesInThePanelBelowTheGame() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        val (w, h) = 1080 to 2400
        val stock = gamepad()
        val shown = OnScreenLayout.editorElements(context, stock, w, h)
        val moved = shown.map { if (it.id == "legacy:a") it.copy(x = .05f) else if (it.id == "legacy:ls") it.copy(x = .6f) else it }
        val edited = OnScreenLayout.withFrame(context, stock, OnScreenLayout.editorFrame(stock, moved), w.toFloat() / h)
        val shownA = shown.first { it.id == "legacy:a" }
        for (panelHeight in listOf(1100, 800)) {
            val placed = OnScreenLayout.elements(context, edited, w, panelHeight, Rect(), dock = OnScreenLayout.Dock(true, false))
            val a = placed.first { it.id == "legacy:a" }
            val ls = placed.first { it.id == "legacy:ls" }
            val b = placed.first { it.id == "legacy:b" }
            assertTrue(a.x < .2f)
            assertTrue(ls.x > .5f)
            assertTrue(b.x > a.x)
            assertNull(placed.firstOrNull { it.id == "builtin:keyboard" || it.control == InputControl.GUIDE || it.control == InputControl.QAM })
            assertInside(placed, w, panelHeight)
            assertEquals(shownA.width * w / (shownA.height * h), a.width * w / (a.height * panelHeight), .01f)
            val untouched = OnScreenLayout.elements(context, stock, w, panelHeight, Rect(), dock = OnScreenLayout.Dock(true, false))
            assertTrue(untouched.first { it.id == "legacy:a" }.x > .5f)
            assertTrue(untouched.first { it.id == "legacy:ls" }.x < .5f)
        }
        val landscape = OnScreenLayout.elements(context, edited, 2400, 1080, Rect())
        assertTrue(landscape.first { it.id == "legacy:a" }.x > .5f)
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val deck = OnScreenLayout.elements(context, edited, w, 1100, Rect(), dock = OnScreenLayout.Dock(true, false), trackpads = true)
        assertTrue(deck.at(InputControl.A).x < .2f)
        assertTrue(deck.at(InputControl.LS).x > .5f)
        assertFalse(deck.at(InputControl.RS).hideWhenIdle)
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        val tiny = stock.copy(aspect = .45f, elements = stock.elements.map {
            when (it.id) {
                "legacy:a" -> it.copy(x = .05f, y = .05f, width = .1f, height = .045f)
                "legacy:b" -> it.copy(x = .16f, y = .05f, width = .1f, height = .045f)
                "legacy:ls" -> it.copy(x = .8f, y = .85f, width = .15f, height = .1f)
                else -> it
            }
        }, rotated = emptyList())
        val short = OnScreenLayout.elements(context, tiny, w, 385, Rect(), dock = OnScreenLayout.Dock(true, false))
        val sa = short.first { it.id == "legacy:a" }
        val sb = short.first { it.id == "legacy:b" }
        assertTrue(sa.x + sa.width <= sb.x)
        assertEquals(1f, sa.width * w / (sa.height * 385), .01f)
    }

    @Test fun thePortraitAndBelowGameKitsKeepDirectionalArtClearOfTheSticksAndFaceButtons() {
        val directions = setOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)
        val face = setOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
        val types = listOf(SessionPrefs.CONTROLLER_DECK, SessionPrefs.CONTROLLER_STEAM, SessionPrefs.CONTROLLER_XBOX360,
            SessionPrefs.CONTROLLER_DS4, SessionPrefs.CONTROLLER_DUALSENSE)
        val layout = gamepad()
        val w = 1080
        for (type in types) {
            SessionPrefs.setSteamController(context, type)
            val steam = type == SessionPrefs.CONTROLLER_DECK || type == SessionPrefs.CONTROLLER_STEAM
            val docked = OnScreenLayout.Dock(true, false)
            for ((h, dock) in listOf(1920 to null, 1300 to docked, 385 to docked)) {
                val placed = OnScreenLayout.elements(context, layout, w, h, Rect(), dock = dock,
                    rearButtons = true, trackpads = true)
                assertInside(placed, w, h)
                val dpad = placed.filter { it.control in directions }
                assertEquals(4, dpad.size)
                val rightEdge = dpad.maxOf { it.x + it.width }
                val bottomEdge = dpad.maxOf { it.y + it.height }
                assertTrue(type, placed.filter { it.control in face && it.id != "builtin:keyboard" }.all { it.x > rightEdge })
                assertTrue(type, placed.filter { it.kind == InputElementKind.STICK || it.kind == InputElementKind.TRACKPAD }.all { it.y > bottomEdge })
                if (dock == null) {
                    val row = placed.filter { it.control in shortcuts || it.id == "builtin:keyboard" }
                    assertEquals(5, row.size)
                    assertTrue(row.all { it.y > bottomEdge })
                    assertEquals(steam, placed.any { it.kind == InputElementKind.TRACKPAD })
                } else {
                    assertFalse(placed.any { it.control == InputControl.GUIDE || it.control == InputControl.QAM })
                    assertTrue(placed.any { it.control == InputControl.RS })
                }
            }
        }
    }

    private val legacyKeys = "29+33:0.3,0.7,44::Find::0.6:1:1:1"

    private fun importedOn(library: InputLibrary) = library.activeLayout(false, InputRole.PRIMARY)!!.elements.filter { it.id.startsWith("imported:") }

    @Test fun shownScreenKeysAreDrawnWithTheGamepadAfterMigration() {
        val before = gamepad()
        prefs.edit().clear().commit()
        ControllerPrefs.prefs(context).edit().putString("screenKeys", legacyKeys).commit()
        val migrated = InputLibraries.read(context)
        val layout = migrated.activeLayout(false, InputRole.PRIMARY)!!
        assertEquals(InputLibraries.OVERLAY, layout.id)
        assertEquals(before.elements, layout.elements.filterNot { it.id.startsWith("imported:") })
        assertEquals(1, importedOn(migrated).size)
        assertEquals(InputAction.Keys(listOf(29, 33)), migrated.compileActive(false).elementAction(InputRole.PRIMARY, "imported:0", InputControl.A))
        assertEquals(InputAction.Native(), migrated.compileActive(false).elementAction(InputRole.PRIMARY, "legacy:a", InputControl.A))
        assertTrue(migrated.profiles.any { it.id == "migrated.screenkeys.0" })
        assertTrue(prefs.getBoolean(LegacyScreenKeyMigration.MERGED, false))
        assertEquals(migrated, InputLibraries.read(context))
        val placed = OnScreenLayout.elements(context, layout, 2400, 1080, Rect())
        assertEquals(layout.elements.map { it.id }, placed.map { it.id })
    }

    @Test fun librariesMigratedBeforeTheMergeGetTheirScreenKeysBackOnceWithoutLosingEdits() {
        val fresh = InputLibraries.read(context)
        val backup = JSONObject().put("screenKeys", legacyKeys).toString()
        val old = LegacyScreenKeyMigration.migrate(context, fresh, backup)
        val bindings = old.activePart(false, InputRole.PRIMARY).bindings
        val edited = old.withPreset(old.preset(bindings).let { it.copy(controls = it.controls + (InputControl.A to InputAction.Keys(listOf(57)))) })
        prefs.edit().putString("library", InputLibraries.encode(edited)).putString(LegacyScreenKeyMigration.BACKUP, backup).commit()
        val merged = InputLibraries.read(context)
        assertEquals(1, importedOn(merged).size)
        assertEquals(InputAction.Keys(listOf(57)), merged.compileActive(false).action(InputRole.PRIMARY, InputControl.A))
        assertEquals(edited.profiles, merged.profiles)
        assertEquals(edited.layouts.map { it.id }.toSet(), merged.layouts.map { it.id }.toSet())
        assertEquals(edited.layouts.first { it.id == "migrated.screenkeys.0.layout" }, merged.layouts.first { it.id == "migrated.screenkeys.0.layout" })
        assertEquals(backup, prefs.getString(LegacyScreenKeyMigration.BACKUP, null))
        assertEquals(merged, InputLibraries.read(context))
        prefs.edit().remove(LegacyScreenKeyMigration.MERGED).commit()
        assertEquals(1, importedOn(InputLibraries.read(context)).size)
    }

    @Test fun hiddenScreenKeysOrAProfileThatAlreadyShowsThemAreLeftAlone() {
        val fresh = InputLibraries.read(context)
        val hidden = JSONObject().put("screenKeys", legacyKeys).put("screenKeysShown", false).toString()
        val old = LegacyScreenKeyMigration.migrate(context, fresh, hidden)
        assertEquals(old, LegacyScreenKeyMigration.merge(old, hidden))
        val shown = JSONObject().put("screenKeys", legacyKeys).toString()
        val selected = old.selectProfile(false, "migrated.screenkeys.0").selectProfile(true, "migrated.screenkeys.0")
        assertEquals(selected, LegacyScreenKeyMigration.merge(selected, shown))
    }

    @Test fun alwaysShownKeysStayInButtonsOnlyModeAndLeaveTheUnfoldedStrip() {
        val key = InputSurfaceElement(InputControl.A, .45f, .1f, .1f, .1f, "imported:0", name = "Find", alwaysShown = true)
        val plain = InputSurfaceElement(InputControl.A, .45f, .4f, .1f, .1f, "imported:1", name = "Jump")
        val layout = gamepad().let { it.copy(elements = it.elements + key + plain) }
        val buttons = OnScreenLayout.elements(context, layout, 1000, 500, Rect(), buttonsOnly = true)
        assertTrue(buttons.any { it.id == key.id })
        assertFalse(buttons.any { it.id == plain.id })
        assertEquals(OnScreenLayout.elements(context, gamepad(), 1000, 500, Rect(), buttonsOnly = true), buttons.filterNot { it.id == key.id })
        val strip = OnScreenLayout.elements(context, layout, 2208, 800, Rect(), dock = OnScreenLayout.Dock(false, grips = false))
        assertFalse(strip.any { it.id == key.id })
        assertTrue(strip.any { it.id == plain.id })
        assertTrue(OnScreenLayout.elements(context, layout, 1000, 500, Rect()).map { it.id }.containsAll(listOf(key.id, plain.id)))
    }

    @Test fun migratedScreenKeysAreAlwaysShownButOtherElementsAreNot() {
        prefs.edit().clear().commit()
        ControllerPrefs.prefs(context).edit().putString("screenKeys", legacyKeys).commit()
        val migrated = InputLibraries.read(context)
        assertTrue(importedOn(migrated).all { it.alwaysShown })
        assertTrue(migrated.layouts.first { it.id == "migrated.screenkeys.0.layout" }.elements.all { it.alwaysShown })
        assertTrue(migrated.layouts.flatMap { it.elements }.filterNot { it.id.startsWith("imported:") }.none { it.alwaysShown })
        assertFalse(LegacyScreenKeyMigration.convert(context, legacyKeys, 1000, 500, 2f).any { it.first.alwaysShown })
    }

    @Test fun keysMergedByAnEarlierVersionBecomeAlwaysShownOnUpgrade() {
        val fresh = InputLibraries.read(context)
        val backup = JSONObject().put("screenKeys", legacyKeys).toString()
        val unpinned = LegacyScreenKeyMigration.merge(LegacyScreenKeyMigration.migrate(context, fresh, backup), backup).let { library ->
            library.copy(layouts = library.layouts.map { l -> l.copy(elements = l.elements.map { it.copy(alwaysShown = false) }) })
        }
        val other = SavedInputLayout("user.keys", "Mine", listOf(InputSurfaceElement(InputControl.A, .1f, .1f, .1f, .1f, "imported:0", name = "Other")))
        val stored = unpinned.withLayout(other)
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(stored)).put("version", 12).toString())
            .putString(LegacyScreenKeyMigration.BACKUP, backup).putBoolean(LegacyScreenKeyMigration.MERGED, true).commit()
        val upgraded = InputLibraries.read(context)
        assertEquals(1, importedOn(upgraded).size)
        assertTrue(importedOn(upgraded).single().alwaysShown)
        assertTrue(upgraded.layouts.first { it.id == "migrated.screenkeys.0.layout" }.elements.all { it.alwaysShown })
        assertFalse(upgraded.layouts.first { it.id == "user.keys" }.elements.single().alwaysShown)
        assertEquals(stored.layouts.map { l -> l.elements.map { it.copy(alwaysShown = false) } }, upgraded.layouts.filter { l -> stored.layouts.any { it.id == l.id } }
            .map { l -> l.elements.map { it.copy(alwaysShown = false) } })
    }

    @Test fun anEditedLandscapeGamepadKeepsItsPositionsBelowTheGame() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val stock = gamepad()
        val edited = stock.copy(aspect = 2f, elements = stock.elements.map {
            if (it.control == InputControl.A && it.id == "legacy:a") it.copy(x = .08f) else it
        })
        val placed = OnScreenLayout.elements(context, edited, 2208, 720, Rect(), dock = OnScreenLayout.Dock(false, false))
        assertTrue(placed.at(InputControl.A).x < .35f)
        assertInside(placed, 2208, 720)
    }

    @Test fun theMainScreenGamepadKeepsBothSticksVisibleBelowTheGame() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val library = InputLibraries.read(context)
        val stock = library.activeLayout(false, InputRole.PRIMARY)!!
        assertTrue(DeckPanel.GAMEPAD.layout != stock.id)
        for ((w, h) in listOf(2180 to 660, 1080 to 385)) {
            val placed = OnScreenLayout.elements(context, stock, w, h, Rect(), dock = OnScreenLayout.Dock(true, false))
            for (control in listOf(InputControl.LS, InputControl.RS)) {
                val stick = placed.at(control)
                assertEquals(InputElementKind.STICK, stick.kind)
                assertFalse(stick.hideWhenIdle)
            }
            assertFalse(placed.any { it.kind == InputElementKind.TRACKPAD })
        }
    }

    private fun deckGamepad(library: InputLibrary) = library.layouts.first { it.id == DeckPanel.GAMEPAD.layout }

    private fun upgradeWithSurfaces(left: String?, right: String?, change: (InputLibrary) -> InputLibrary = { it }): InputLibrary {
        val stored = change(InputLibraries.read(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("gamepadLeft", left).putString("gamepadRight", right).commit()
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(stored)).put("version", 13).toString()).commit()
        return InputLibraries.read(context)
    }

    @Test fun theSavedDeckGamepadStickOrTrackpadChoiceMovesIntoTheUntouchedDeckGamepadOnce() {
        val upgraded = upgradeWithSurfaces("trackpad", "stick")
        val elements = deckGamepad(upgraded).elements
        assertEquals(InputStockLayouts.gamepad(leftPad = true, rightStick = true), elements)
        assertEquals(InputElementKind.TRACKPAD, elements.single { it.control == InputControl.LEFT_PAD }.kind)
        assertEquals(InputElementKind.STICK, elements.single { it.control == InputControl.RS }.kind)
        assertNull(elements.firstOrNull { it.control == InputControl.LS || it.control == InputControl.RIGHT_PAD })
        assertEquals(upgraded, InputLibraries.read(context))
    }

    @Test fun theDefaultDeckGamepadChoiceOrAnEditedDeckGamepadIsLeftAlone() {
        val stock = InputStockLayouts.layouts().first { it.id == DeckPanel.GAMEPAD.layout }
        assertEquals(stock.elements, deckGamepad(upgradeWithSurfaces(null, null)).elements)
        assertEquals(stock.elements, deckGamepad(upgradeWithSurfaces("stick", "trackpad")).elements)
        val moved = stock.elements.map { if (it.control == InputControl.A) it.copy(x = .7f) else it }
        val edited = upgradeWithSurfaces("trackpad", "stick") { it.withLayout(deckGamepad(it).copy(elements = moved)) }
        assertEquals(moved, deckGamepad(edited).elements)
        val others = InputLibraries.read(context).layouts.filter { it.id != DeckPanel.GAMEPAD.layout }
        assertEquals(InputStockLayouts.layouts().filter { it.id != DeckPanel.GAMEPAD.layout }.map { it.elements },
            others.filter { l -> InputStockLayouts.layouts().any { it.id == l.id } }.map { it.elements })
    }

    private val kits = listOf(SessionPrefs.CONTROLLER_DECK, SessionPrefs.CONTROLLER_STEAM, SessionPrefs.CONTROLLER_XBOX360,
        SessionPrefs.CONTROLLER_DS4, SessionPrefs.CONTROLLER_DUALSENSE)

    private fun List<InputSurfaceElement>.at(control: InputControl) = single { it.id == "legacy:${control.id}" }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun everyKitPutsTriggersBesideTheirBumpers() {
        for (kit in kits) {
            SessionPrefs.setSteamController(context, kit)
            val placed = OnScreenLayout.elements(context, gamepad(), 1600, 900, Rect(), rearButtons = true, trackpads = true)
            val lt = placed.at(InputControl.LT); val lb = placed.at(InputControl.LB)
            val rb = placed.at(InputControl.RB); val rt = placed.at(InputControl.RT)
            for ((outer, inner) in listOf(lt to lb, rt to rb)) assertEquals(kit, outer.y + outer.height / 2f, inner.y + inner.height / 2f, .001f)
            assertTrue(kit, lt.x + lt.width <= lb.x)
            assertTrue(kit, rb.x + rb.width <= rt.x)
            assertInside(placed, 1600, 900)
        }
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun buttonSizeGrowsAndShrinksControlsOnAScreenWhoseHeightAlreadyLimitsTheLayout() {
        val (display, _) = InputLibraries.primaryDisplay(context)
        val stock = gamepad()
        fun at(scale: Float) = OnScreenLayout.elements(context, stock.copy(scale = scale,
            elements = LegacyGamepadLayout(display, 1600, 900, scale = scale).regenerate(stock.elements)), 1600, 900, Rect())
        fun List<InputSurfaceElement>.centre(controls: List<InputControl>) = controls.map { at(it) }.let { c ->
            c.sumOf { (it.x + it.width / 2).toDouble() } / c.size to c.sumOf { (it.y + it.height / 2).toDouble() } / c.size }
        val face = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
        val dpad = listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)
        for (kit in kits) {
            SessionPrefs.setSteamController(context, kit)
            val normal = at(1f)
            for (scale in listOf(.8f, 1.25f)) {
                val placed = at(scale)
                for (control in listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y, InputControl.LS, InputControl.RS)) {
                    val before = normal.at(control)
                    val after = placed.at(control)
                    assertEquals("$kit $scale ${control.id} aspect ratio", before.width / before.height,
                        after.width / after.height, .02f)
                }
                assertEquals("$kit $scale", normal.at(InputControl.A).width * scale, placed.at(InputControl.A).width, .002f)
                assertEquals("$kit $scale", normal.at(InputControl.LS).height * scale, placed.at(InputControl.LS).height, .002f)
                if (scale > 1f) for (cluster in listOf(face, dpad)) {
                    val (x0, y0) = normal.centre(cluster); val (x1, y1) = placed.centre(cluster)
                    assertEquals("$kit", x0, x1, .01); assertEquals("$kit", y0, y1, .01)
                }
                assertInside(placed, 1600, 900)
            }
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletOverlaysStayInTheLowerHalfOfTheScreen() {
        for (kit in kits) {
            SessionPrefs.setSteamController(context, kit)
            val placed = OnScreenLayout.elements(context, gamepad(), 1280, 800, Rect(), rearButtons = true, trackpads = true)
            for (e in placed) assertTrue("$kit ${e.id}", e.y >= .45f)
            assertInside(placed, 1280, 800)
        }
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun phoneOverlaysStillUseTheFullHeight() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val placed = OnScreenLayout.elements(context, gamepad(), 1600, 900, Rect())
        assertTrue(placed.at(InputControl.LT).y < .15f)
        assertTrue(placed.at(InputControl.START).let { it.y + it.height } > .9f)
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun aLayoutEditedInOneOrientationKeepsTheKitInTheOther() {
        for (kit in kits) {
            SessionPrefs.setSteamController(context, kit)
            val layout = gamepad()
            val landscape = OnScreenLayout.elements(context, layout, 1600, 900, Rect())
            val moved = landscape.map { if (it.control == InputControl.A) it.copy(x = .5f, y = .5f) else it }
            val edited = layout.copy(elements = moved, aspect = 1600f / 900f)
            assertEquals(kit, .5f, OnScreenLayout.elements(context, edited, 1600, 900, Rect()).at(InputControl.A).x, .001f)
            val kitPortrait = OnScreenLayout.elements(context, layout, 900, 1600, Rect())
            val editedPortrait = OnScreenLayout.elements(context, edited, 900, 1600, Rect())
            for (control in listOf(InputControl.A, InputControl.LT, InputControl.RB, InputControl.LS, InputControl.UP))
                assertEquals("$kit ${control.id}", kitPortrait.at(control), editedPortrait.at(control))
        }
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun eachOrientationKeepsItsOwnEdits() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val layout = gamepad()
        val landscape = OnScreenLayout.elements(context, layout, 1600, 900, Rect()).map { if (it.control == InputControl.A) it.copy(x = .5f, y = .5f) else it }
        val edited = OnScreenLayout.withFrame(context, layout, landscape, 1600f / 900f)
        val portrait = OnScreenLayout.elements(context, edited, 900, 1600, Rect()).map { if (it.control == InputControl.B) it.copy(x = .1f, y = .2f) else it }
        val both = OnScreenLayout.withFrame(context, edited, portrait, 900f / 1600f)
        assertEquals(landscape.map { it.id to floatArrayOf(it.x, it.y).toList() }, both.elements.map { it.id to floatArrayOf(it.x, it.y).toList() })
        val wide = OnScreenLayout.elements(context, both, 1600, 900, Rect())
        val tall = OnScreenLayout.elements(context, both, 900, 1600, Rect())
        assertEquals(.5f, wide.at(InputControl.A).x, .001f)
        assertEquals(.1f, tall.at(InputControl.B).x, .001f)
        assertEquals(.2f, tall.at(InputControl.B).y, .001f)
        val renamed = OnScreenLayout.withFrame(context, both, both.elements.map { if (it.control == InputControl.A) it.copy(name = "Jump") else it }, both.aspect)
        assertEquals("Jump", OnScreenLayout.elements(context, renamed, 900, 1600, Rect()).at(InputControl.A).name)
        assertEquals(.1f, OnScreenLayout.elements(context, renamed, 900, 1600, Rect()).at(InputControl.B).x, .001f)
        val library = InputLibraries.read(context).withLayout(both)
        assertEquals(both, InputLibraries.decode(InputLibraries.encode(library))!!.layouts.first { it.id == both.id })
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun aRemovedRearButtonStaysRemovedWhileTheOthersRemain() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val layout = gamepad()
        val displayed = OnScreenLayout.elements(context, layout, 1600, 900, Rect(), rearButtons = true, trackpads = true)
        val frozen = OnScreenLayout.editorFrame(layout, displayed).filterNot { it.control == InputControl.L4 || it.control == InputControl.LEFT_PAD }
        val edited = layout.copy(elements = frozen, aspect = 1600f / 900f)
        for ((w, h) in listOf(1600 to 900, 900 to 1600)) {
            val placed = OnScreenLayout.elements(context, edited, w, h, Rect(), rearButtons = true, trackpads = true).map { it.control }
            assertFalse(InputControl.L4 in placed)
            assertFalse(InputControl.LEFT_PAD in placed)
            assertTrue(InputControl.R5 in placed)
            assertTrue(InputControl.RIGHT_PAD in placed)
        }
        val hidden = OnScreenLayout.elements(context, edited, 1600, 900, Rect(), rearButtons = false, trackpads = false).map { it.control }
        assertFalse(InputControl.R5 in hidden)
        assertFalse(InputControl.RIGHT_PAD in hidden)
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun aPortraitMainLayoutUsesTheStockControlsInLandscape() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val layout = gamepad()
        val portrait = OnScreenLayout.elements(context, layout, 900, 1600, Rect()).map { if (it.control == InputControl.A) it.copy(x = .1f, y = .1f) else it }
        val edited = layout.copy(elements = portrait, aspect = 900f / 1600f)
        assertEquals(OnScreenLayout.elements(context, layout, 1600, 900, Rect()), OnScreenLayout.elements(context, edited, 1600, 900, Rect()))
        assertEquals(.1f, OnScreenLayout.elements(context, edited, 900, 1600, Rect()).at(InputControl.A).x, .001f)
    }

    @Test @Config(qualifiers = "w800dp-h450dp-land-320dpi")
    fun aPortraitSaveKeepsOptionalControlsInThePortraitFrameOnly() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val layout = gamepad()
        val landscape = OnScreenLayout.elements(context, layout, 1600, 900, Rect())
        val edited = OnScreenLayout.withFrame(context, layout, landscape, 1600f / 900f)
        val portrait = OnScreenLayout.elements(context, edited, 900, 1600, Rect(), rearButtons = true, trackpads = true)
            .map { if (it.control == InputControl.B) it.copy(x = .1f, y = .2f) else it }
        assertTrue(portrait.any { it.control == InputControl.L4 })
        val both = OnScreenLayout.withFrame(context, edited, portrait, 900f / 1600f)
        assertTrue(both.elements.none { it.control == InputControl.L4 || it.control == InputControl.LEFT_PAD })
        assertTrue(both.rotated.any { it.control == InputControl.L4 })
        assertEquals(OnScreenLayout.elements(context, edited, 1600, 900, Rect(), rearButtons = true, trackpads = true),
            OnScreenLayout.elements(context, both, 1600, 900, Rect(), rearButtons = true, trackpads = true))
        val tall = OnScreenLayout.elements(context, both, 900, 1600, Rect(), rearButtons = true, trackpads = true)
        for (control in listOf(InputControl.L4, InputControl.LEFT_PAD, InputControl.B)) assertEquals(control.id, portrait.at(control), tall.at(control))
        assertTrue(OnScreenLayout.elements(context, both, 900, 1600, Rect(), rearButtons = false, trackpads = false)
            .none { it.control == InputControl.L4 || it.control == InputControl.LEFT_PAD })
    }

    @Test fun stockControlsUseTheKitArtworkWhileAddedAndTrackpadControlsStillUseTheTint() {
        for (kit in listOf(PadKit.XBOX, PadKit.DUALSENSE)) {
            SessionPrefs.setSteamController(context, if (kit == PadKit.XBOX) SessionPrefs.CONTROLLER_XBOX360 else SessionPrefs.CONTROLLER_DUALSENSE)
            val layout = gamepad()
            assertTrue(kit.name, layout.elements.all { kit.artwork(it) })
            val added = InputSurfaceElement(InputControl.A, 0f, 0f, .1f, .1f, "custom", name = "Fire")
            assertFalse(kit.artwork(added))
            assertFalse(kit.artwork(layout.elements.first { it.control == InputControl.A }.copy(image = "picture")))
        }
    }

    @Test
    fun dockedSizeAppliesWithoutAKitAndToThePortraitCluster() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
        val stock = gamepad()
        val dock = OnScreenLayout.Dock(false, false)

        ControllerPrefs.setSize(context, 100)
        val defaultNormal = OnScreenLayout.elements(context, stock, 1200, 540, Rect(), dock = dock)
        ControllerPrefs.setSize(context, 125)
        val defaultLarge = OnScreenLayout.elements(context, stock, 1200, 540, Rect(), dock = dock)
        assertTrue(defaultLarge.at(InputControl.A).width > defaultNormal.at(InputControl.A).width)

        val portraitEdited = stock.copy(
            elements = stock.elements.map {
                if (it.control == InputControl.A) it.copy(x = it.x + .07f) else it
            },
            aspect = .5f,
        )
        val portraitDock = OnScreenLayout.Dock(true, false)
        ControllerPrefs.setSize(context, 100)
        val clusterNormal = OnScreenLayout.elements(
            context, portraitEdited, 540, 1200, Rect(), dock = portraitDock,
        )
        ControllerPrefs.setSize(context, 125)
        val clusterLarge = OnScreenLayout.elements(
            context, portraitEdited, 540, 1200, Rect(), dock = portraitDock,
        )
        assertTrue(clusterLarge.at(InputControl.A).width > clusterNormal.at(InputControl.A).width)
    }
}
