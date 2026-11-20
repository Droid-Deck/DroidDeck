package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.hypot

private const val HANDHELD = "w833dp-h468dp-land-369dpi"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = HANDHELD)
class KitLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sizes = listOf(1080 to 1920, 1920 to 1080, 1240 to 1080, 2400 to 1080, 1080 to 2400, 1600 to 900, 1280 to 960)

    private fun px(e: InputSurfaceElement, w: Int, h: Int) = floatArrayOf(e.x * w, e.y * h, e.width * w, e.height * h)
    private fun centre(e: InputSurfaceElement, w: Int, h: Int) = (e.x + e.width / 2) * w to (e.y + e.height / 2) * h

    private fun overlapping(elements: List<InputSurfaceElement>, w: Int, h: Int): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for ((i, a) in elements.withIndex()) for (b in elements.drop(i + 1)) {
            val (ax, ay) = centre(a, w, h); val (bx, by) = centre(b, w, h)
            val round = a.buttonShape == InputButtonShape.CIRCLE && b.buttonShape == InputButtonShape.CIRCLE
            val hit = if (round) hypot(ax - bx, ay - by) < (minOf(a.width * w, a.height * h) + minOf(b.width * w, b.height * h)) / 2 - .5f
                else kotlin.math.abs(ax - bx) < (a.width + b.width) * w / 2 - .5f && kotlin.math.abs(ay - by) < (a.height + b.height) * h / 2 - .5f
            if (hit) result += a.id to b.id
        }
        return result
    }

    private fun ids(kit: PadKit) = KitLayout.elements(context, kit, 1920, 1080).map { it.id }.toSet()

    @Test fun everyKitHasTheCommonControlsOnceEach() {
        val common = setOf("legacy:lt", "legacy:lb", "legacy:rt", "legacy:rb", "legacy:a", "legacy:b", "legacy:x", "legacy:y", "legacy:ls", "legacy:rs",
            "legacy:select", "legacy:start", "legacy:guide", "legacy:qam", KitLayout.DPAD_ID, KitLayout.KEYBOARD_ID)
        for (kit in PadKit.entries) {
            val elements = KitLayout.elements(context, kit, 1920, 1080)
            assertEquals(kit.name, elements.size, elements.map { it.id }.distinct().size)
            assertTrue(kit.name, elements.map { it.id }.containsAll(common))
        }
    }

    @Test fun steamKitsAddGripsAndTrackpadsAndTheOthersAddStickClicks() {
        val grips = setOf("legacy:l4", "legacy:l5", "legacy:r4", "legacy:r5")
        val pads = setOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD)
        val clicks = setOf("legacy:l3", "legacy:r3")
        for (kit in PadKit.entries) {
            val elements = KitLayout.elements(context, kit, 1920, 1080)
            assertEquals(kit.name, kit.steam, elements.map { it.id }.containsAll(grips))
            assertEquals(kit.name, if (kit.steam) pads else emptySet<InputControl>(), elements.filter { it.kind == InputElementKind.TRACKPAD }.map { it.control }.toSet())
            assertEquals(kit.name, !kit.steam, elements.map { it.id }.containsAll(clicks))
            assertTrue(kit.name, elements.none { kit.steam && it.id in clicks || !kit.steam && it.id in grips })
        }
        assertEquals(KitLayout.elements(context, PadKit.STEAM_DECK, 1920, 1080), KitLayout.elements(context, PadKit.STEAM_CONTROLLER, 1920, 1080))
        assertEquals(KitLayout.elements(context, PadKit.DUALSHOCK, 1920, 1080), KitLayout.elements(context, PadKit.DUALSENSE, 1920, 1080))
        assertNotEquals(ids(PadKit.STEAM_DECK), ids(PadKit.XBOX))
    }

    @Test fun onlySticksFloatAndTheDpadAndKeyboardAreKitElements() {
        for (kit in PadKit.entries) {
            val elements = KitLayout.elements(context, kit, 1920, 1080)
            for (e in elements) assertEquals(e.id, e.kind == InputElementKind.STICK, e.floating)
            assertEquals(InputElementKind.DPAD, elements.single { it.id == KitLayout.DPAD_ID }.kind)
            assertEquals(InputButtonIcons.KEYBOARD, elements.single { it.id == KitLayout.KEYBOARD_ID }.icon)
            for (shoulder in listOf("legacy:lt", "legacy:lb", "legacy:rt", "legacy:rb")) assertEquals(InputButtonShape.PILL, elements.single { it.id == shoulder }.buttonShape)
        }
    }

    @Test fun controlsStayOnScreenAndApartAtEverySize() {
        for (kit in PadKit.entries) for ((w, h) in sizes) for (scale in listOf(.8f, 1f, 1.25f)) {
            val elements = KitLayout.elements(context, kit, w, h, scale = scale)
            for (e in elements) assertEquals("$kit $w x $h ${e.id}", e, e.bounded())
            assertEquals("$kit $w x $h at $scale", emptyList<Pair<String, String>>(), overlapping(elements, w, h))
        }
    }

    @Test fun buttonsAreRoundAndStayRoundOnEveryAspect() {
        for (kit in PadKit.entries) for ((w, h) in sizes) {
            for (e in KitLayout.elements(context, kit, w, h).filter { it.buttonShape == InputButtonShape.CIRCLE })
                assertEquals("$kit $w x $h ${e.id}", e.width * w, e.height * h, 1f)
        }
    }

    @Test fun theFaceButtonsFormACross() {
        for (kit in PadKit.entries) {
            val e = KitLayout.elements(context, kit, 2400, 1080).associateBy { it.id }
            val (yx, yy) = centre(e.getValue("legacy:y"), 2400, 1080); val (ax, ay) = centre(e.getValue("legacy:a"), 2400, 1080)
            val (xx, xy) = centre(e.getValue("legacy:x"), 2400, 1080); val (bx, by) = centre(e.getValue("legacy:b"), 2400, 1080)
            assertEquals(yx, ax, .5f); assertEquals(xy, by, .5f)
            assertTrue(yy < xy && xy < ay && xx < yx && yx < bx)
            assertEquals(ay - yy, bx - xx, .5f)
        }
    }

    @Test fun safeInsetsMoveEdgeControlsInByTheInsetOnly() {
        val safe = Rect(120, 0, 60, 0)
        for (kit in PadKit.entries) {
            val plain = KitLayout.elements(context, kit, 2400, 1080).associateBy { it.id }
            val inset = KitLayout.elements(context, kit, 2400, 1080, safe)
            for (e in inset) {
                val (x, y, ew, eh) = px(e, 2400, 1080).toList()
                assertTrue("$kit ${e.id}", x >= safe.left - .5f && y >= safe.top - .5f && x + ew <= 2400 - safe.right + .5f && y + eh <= 1080 - safe.bottom + .5f)
            }
            val a = plain.getValue("legacy:lt"); val b = inset.single { it.id == "legacy:lt" }
            assertEquals(safe.left.toFloat(), (b.x - a.x) * 2400, .5f)
            val r = plain.getValue("legacy:rt"); val s = inset.single { it.id == "legacy:rt" }
            assertEquals(-safe.right.toFloat(), (s.x - r.x) * 2400, .5f)
        }
    }

    @Test fun aSideCutoutOnlyMovesTheOverlappingCluster() {
        val w = 2400
        val h = 1080
        val plain = KitLayout.elements(context, PadKit.STEAM_DECK, w, h, rearButtons = false, trackpads = false)
        val left = Rect(10, 380, 120, 480)
        val right = Rect(w - 120, 380, w, 480)
        val adjusted = KitLayout.elements(context, PadKit.STEAM_DECK, w, h, Rect(120, 0, 120, 0),
            cutouts = listOf(left, right), rearButtons = false, trackpads = false)
        val face = setOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val xOffsets = plain.zip(adjusted).associate { (before, after) ->
            assertEquals(before.id, after.id)
            assertEquals(before.y, after.y, .00001f)
            assertEquals(before.width, after.width, .00001f)
            assertEquals(before.height, after.height, .00001f)
            assertTrue(after.x * w >= left.right || (after.x + after.width) * w <= left.left ||
                after.y * h >= left.bottom || (after.y + after.height) * h <= left.top)
            assertTrue(after.x * w >= right.right || (after.x + after.width) * w <= right.left ||
                after.y * h >= right.bottom || (after.y + after.height) * h <= right.top)
            before.id to (after.x - before.x) * w
        }
        assertTrue(xOffsets.getValue(KitLayout.DPAD_ID) in 1f..100f)
        assertTrue(xOffsets.getValue("legacy:b") in -100f..-1f)
        for ((id, offset) in xOffsets) if (id in face) assertEquals(xOffsets.getValue("legacy:b"), offset, .01f)
            else if (id != KitLayout.DPAD_ID) assertEquals(id, 0f, offset, .01f)
        assertTrue(overlapping(adjusted, w, h).isEmpty())
    }

    @Test fun aCutoutShiftDoesNotCoverAnAddedControl() {
        val w = 2400
        val h = 1080
        val safe = Rect(120, 0, 0, 0)
        val extra = InputSurfaceElement(InputControl.B, .05f, .4f, .025f, .06f, id = "added")
        val layout = SavedInputLayout(InputLibraries.OVERLAY, "Gamepad", listOf(extra), aspect = w.toFloat() / h)
        val fallback = OnScreenLayout.elements(context, layout, w, h, safe, rearButtons = false, trackpads = false)
        val placed = OnScreenLayout.elements(context, layout, w, h, safe, cutouts = listOf(Rect(0, 380, 120, 480)),
            rearButtons = false, trackpads = false)
        assertEquals(fallback, placed)
        assertTrue(overlapping(placed, w, h).isEmpty())
    }

    @Test fun startSelectAndHomeAreOnTheFloatingOverlayBottomRow() {
        for (kit in PadKit.entries) for ((w, h) in sizes) {
            val buttons = KitLayout.elements(context, kit, w, h)
            val keyboard = buttons.single { it.id == KitLayout.KEYBOARD_ID }
            for (button in buttons.filter { it.control == InputControl.SELECT || it.control == InputControl.START || it.control == InputControl.GUIDE }) {
                assertTrue("$kit $w x $h ${button.id}", button.y + button.height / 2 in keyboard.y..(keyboard.y + keyboard.height))
            }
        }
    }

    @Test fun theXboxAndPlayStationHomeButtonSitsBetweenSelectAndStart() {
        for (kit in PadKit.entries.filterNot { it.steam }) for ((w, h) in sizes.filter { it.first > it.second }) {
            val buttons = KitLayout.elements(context, kit, w, h).associateBy { it.control }
            val select = buttons.getValue(InputControl.SELECT); val start = buttons.getValue(InputControl.START); val home = buttons.getValue(InputControl.GUIDE)
            assertTrue("$kit $w x $h", select.x + select.width < home.x && home.x + home.width < start.x)
        }
    }

    @Test fun widerScreensSpreadTheControlsInsteadOfStretchingThem() {
        for (kit in PadKit.entries) {
            val narrow = KitLayout.elements(context, kit, 1920, 1080).associateBy { it.id }
            val wide = KitLayout.elements(context, kit, 2400, 1080).associateBy { it.id }
            for ((id, n) in narrow) {
                val v = wide.getValue(id)
                assertEquals("$kit $id width", n.width * 1920, v.width * 2400, .5f)
                assertEquals("$kit $id y", n.y * 1080, v.y * 1080, .5f)
                val (nx, _) = centre(n, 1920, 1080); val (vx, _) = centre(v, 2400, 1080)
                val shift = vx - nx
                assertTrue("$kit $id moved $shift", listOf(0f, 240f, 480f).any { kotlin.math.abs(shift - it) < .5f })
            }
        }
    }

    @Test fun theCentreOfTheScreenStaysClear() {
        for (kit in PadKit.entries) for ((w, h) in sizes.filter { it.first > it.second }) {
            val box = android.graphics.RectF(w * .4f, h * .35f, w * .6f, h * .65f)
            for (e in KitLayout.elements(context, kit, w, h)) {
                val (x, y, ew, eh) = px(e, w, h).toList()
                assertFalse("$kit $w x $h ${e.id}", android.graphics.RectF.intersects(box, android.graphics.RectF(x, y, x + ew, y + eh)))
            }
        }
    }

    @Test @Config(qualifiers = "mdpi")
    fun aBigScreenCapsTheControlsAtAHandheldHeight() {
        val metrics = context.resources.displayMetrics
        val dpi = metrics.ydpi.takeIf { it > metrics.densityDpi * .6f && it < metrics.densityDpi * 1.6f } ?: metrics.densityDpi.toFloat()
        val small = KitLayout.elements(context, PadKit.XBOX, 1600, 900).single { it.id == "legacy:a" }
        val big = KitLayout.elements(context, PadKit.XBOX, 3200, 1800).single { it.id == "legacy:a" }
        assertEquals(small.height * 900, big.height * 1800, 1f)
        val mm = small.height * 900 / dpi * 25.4f
        assertEquals(116f / 900f * 80f, mm, .3f)
    }

    @Test fun aHandheldScreenUsesTheFullReferenceSize() {
        val a = KitLayout.elements(context, PadKit.XBOX, 1920, 1080).single { it.id == "legacy:a" }
        assertEquals(116f * 1.2f, a.height * 1080, 1f)
    }

    @Test fun noDefaultControlSitsUnderTheCornerButton() {
        val density = context.resources.displayMetrics.density
        val corner = android.graphics.RectF(4 * density, 1080 - 52 * density, 52 * density, 1080 - 4 * density)
        for (kit in PadKit.entries) for (e in KitLayout.elements(context, kit, 1920, 1080)) {
            val (cx, cy) = centre(e, 1920, 1080)
            val r = minOf(e.width * 1920, e.height * 1080) / 2
            val nx = cx.coerceIn(corner.left, corner.right); val ny = cy.coerceIn(corner.top, corner.bottom)
            val touches = if (e.buttonShape == InputButtonShape.CIRCLE) hypot(cx - nx, cy - ny) < r - 1f
                else android.graphics.RectF.intersects(corner, android.graphics.RectF(e.x * 1920, e.y * 1080, (e.x + e.width) * 1920, (e.y + e.height) * 1080))
            assertFalse("$kit ${e.id} is under the corner button", touches)
        }
    }

    @Test fun clustersStayTogetherOnTallAndSquareScreens() {
        val ratio = mapOf(PadKit.STEAM_DECK to 96f / 58f, PadKit.STEAM_CONTROLLER to 96f / 58f, PadKit.XBOX to 94f / 58f,
            PadKit.DUALSHOCK to 96f / 58f, PadKit.DUALSENSE to 96f / 58f)
        for (kit in PadKit.entries) for ((w, h) in listOf(1080 to 1920, 1240 to 1080, 2400 to 1080)) {
            val e = KitLayout.elements(context, kit, w, h).associateBy { it.id }
            val face = listOf("legacy:y", "legacy:x", "legacy:b", "legacy:a").map { centre(e.getValue(it), w, h) }
            val cx = face.map { it.first }.average().toFloat(); val cy = face.map { it.second }.average().toFloat()
            val radius = e.getValue("legacy:a").height * h / 2
            val spread = if (h > w) .3f / .17f else ratio.getValue(kit)
            for ((x, y) in face) assertEquals("$kit $w x $h", radius * spread, hypot(x - cx, y - cy), 1f)
            if (kit == PadKit.XBOX && h <= w) {
                val (lx, ly) = centre(e.getValue("legacy:ls"), w, h); val (cx3, cy3) = centre(e.getValue("legacy:l3"), w, h)
                val unit = e.getValue("legacy:ls").height * h / 208f
                assertEquals("$w x $h", hypot(150f, 110f) * unit, hypot(cx3 - lx, cy3 - ly), 1f)
            }
        }
    }

    @Test @Config(qualifiers = "mdpi")
    fun smallControlsKeepTheirShapeOnABigLowDensityScreen() {
        for (kit in PadKit.entries) {
            val elements = KitLayout.elements(context, kit, 1920, 1080)
            for (e in elements.filter { it.buttonShape == InputButtonShape.CIRCLE }) assertEquals("$kit ${e.id}", e.width * 1920, e.height * 1080, 1f)
            assertEquals(kit.name, emptyList<Pair<String, String>>(), overlapping(elements, 1920, 1080))
        }
    }

    @Test fun theBottomLeftRowStartsRightOfTheCornerButtonWithGaps() {
        val density = context.resources.displayMetrics.density
        for (safe in listOf(Rect(), Rect(120, 0, 0, 40))) for (kit in PadKit.entries) for ((w, h) in sizes) {
            val elements = KitLayout.elements(context, kit, w, h, safe)
            val keyboard = elements.single { it.id == KitLayout.KEYBOARD_ID }
            val row = elements.filter { e -> val cy = e.y + e.height / 2; cy > keyboard.y && cy < keyboard.y + keyboard.height && e.x + e.width / 2 < .5f }
                .sortedBy { it.x }
            assertEquals("$kit $w x $h", keyboard, row.first())
            assertTrue("$kit $w x $h $safe keyboard at ${keyboard.x * w}", keyboard.x * w >= safe.left + 52 * density - .5f)
            for ((a, b) in row.zipWithNext()) assertTrue("$kit $w x $h ${a.id} ${b.id}", (b.x - a.x - a.width) * w >= 8 * density - .5f)
        }
    }

    @Test fun portraitSurfacesPutTheWholePadInTheLowerPartAboveABottomRow() {
        val density = context.resources.displayMetrics.density
        for (kit in PadKit.entries) for ((w, h) in listOf(1080 to 1920, 1200 to 2000, 1080 to 2400)) {
            val elements = KitLayout.elements(context, kit, w, h)
            val row = 60 * density
            val area = minOf(h * .6f, w * 1.15f)
            assertTrue("$kit $w x $h", elements.minOf { it.y } * h >= h - row - area - .5f)
            assertTrue("$kit $w x $h", elements.maxOf { (it.y + it.height) * h } <= h + .5f)
            val bottomRow = elements.filter { (it.y + it.height / 2) * h > h - row }
            assertTrue("$kit $w x $h", bottomRow.any { it.id == KitLayout.KEYBOARD_ID } && bottomRow.any { it.control == InputControl.QAM })
            assertTrue("$kit $w x $h", bottomRow.minOf { it.x } * w >= 52 * density - .5f)
            val stick = elements.first { it.id == "legacy:ls" }
            assertTrue("$kit $w x $h", stick.width * w >= w * if (kit.steam) .2f else .3f)
            assertTrue("$kit $w x $h", elements.any { it.id == "legacy:rs" })
        }
    }

    @Test fun anEmptySurfaceHasNoControls() {
        assertTrue(KitLayout.elements(context, PadKit.STEAM_DECK, 0, 1080).isEmpty())
        assertTrue(KitLayout.elements(context, PadKit.STEAM_DECK, 1920, 0).isEmpty())
    }

    @Test fun theDockedStripAddsGripsOnlyForSteamKitsAndNeverSystemButtons() {
        val density = context.resources.displayMetrics.density
        for (kit in PadKit.entries) {
            val placed = KitLayout.dock(context, kit, 1080, 1300, true, InputControl.LS, InputControl.RS)
            val boxes = GamepadGeometry.layout(1080f, 1300f, density, true, grips = kit.steam, trackpads = kit.steam)
            assertEquals(kit.name, kit.steam && boxes.containsKey(GamepadGeometry.Slot.L4), placed.any { it.control == InputControl.L4 })
            assertEquals(kit.name, kit.steam, placed.any { it.control == InputControl.L4 })
            assertNull(placed.firstOrNull { it.control == InputControl.GUIDE || it.control == InputControl.QAM })
            assertEquals(1, placed.count { it.id == KitLayout.DPAD_ID })
            assertTrue(placed.none { it.floating })
        }
    }

    @Test fun onlySteamKitsPutTrackpadsInTheDockedStrip() {
        for (kit in PadKit.entries) {
            val placed = KitLayout.dock(context, kit, 1080, 1300, true, InputControl.LS, InputControl.RIGHT_PAD).map { it.control }
            assertEquals(kit.toString(), kit.steam, InputControl.RIGHT_PAD in placed && InputControl.LEFT_PAD in placed)
            assertTrue(kit.toString(), InputControl.RS in placed && InputControl.LS in placed)
        }
    }

    @Test fun aSmallerPortraitPadKeepsTheGripsAndANarrowBottomRowNeverOverlaps() {
        val grips = setOf("legacy:l4", "legacy:l5", "legacy:r4", "legacy:r5")
        for (scale in listOf(.8f, 1f)) {
            val ids = KitLayout.elements(context, PadKit.STEAM_DECK, 720, 1280, scale = scale).map { it.id }
            assertTrue("$scale", ids.containsAll(grips))
        }
        for (kit in PadKit.entries) {
            val (w, h) = 720 to 1280
            val elements = KitLayout.elements(context, kit, w, h, Rect(120, 0, 0, 0))
            assertEquals(kit.toString(), emptyList<Pair<String, String>>(), overlapping(elements, w, h))
        }
    }

    @Test fun noKitControlIsSmallerThanTheOldGamepadOnAHandheld() {
        val dp = context.resources.displayMetrics.density
        for (kit in PadKit.entries) {
            val e = KitLayout.elements(context, kit, 1920, 1080).associateBy { it.id }
            assertTrue("$kit face", e.getValue("legacy:a").width * 1920 / 2 >= 30 * dp - 1)
            assertTrue("$kit d-pad", e.getValue(KitLayout.DPAD_ID).width * 1920 / 2 >= 60 * dp)
            assertTrue("$kit stick", e.getValue("legacy:ls").width * 1920 / 2 >= 40 * dp)
        }
    }

    @Test fun aTopCutoutNeverPushesTheShoulderRowIntoTheMiddleControls() {
        for (kit in PadKit.entries) for (top in listOf(0, 20, 60, 120)) {
            val elements = KitLayout.elements(context, kit, 1600, 900, Rect(0, top, 0, 0))
            val lb = elements.single { it.id == "legacy:lb" }
            val middle = elements.filter { it.id == KitLayout.DPAD_ID || it.id == "legacy:y" }
            for (m in middle) assertTrue("$kit $top ${m.id}", m.y * 900 >= (lb.y + lb.height) * 900 - .5f || m.x * 1600 >= (lb.x + lb.width) * 1600)
        }
    }

    @Test fun insetsThatLeaveLessRoomShrinkTheKitInsteadOfOverlappingIt() {
        for (kit in PadKit.entries) for ((w, h, safe) in listOf(Triple(1600, 900, Rect(0, 60, 0, 0)), Triple(1600, 900, Rect(0, 730, 0, 0)),
            Triple(1600, 900, Rect(0, 200, 0, 0)), Triple(1600, 900, Rect(0, 200, 0, 150)), Triple(1600, 900, Rect(0, 390, 0, 0)),
            Triple(1920, 1080, Rect(80, 120, 80, 90)), Triple(2400, 1080, Rect(120, 30, 60, 40)))) {
            val elements = KitLayout.elements(context, kit, w, h, safe)
            assertEquals("$kit $w x $h $safe", emptyList<Pair<String, String>>(), overlapping(elements, w, h))
            if (safe.top + safe.bottom <= 400) for (e in elements) {
                val (x, y, ew, eh) = px(e, w, h).toList()
                assertTrue("$kit $safe ${e.id}", x >= safe.left - .5f && y >= safe.top - .5f && x + ew <= w - safe.right + .5f && y + eh <= h - safe.bottom + .5f)
            }
        }
    }
}
