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

    @Test fun steamShortcutsClearPinnedControlsAndStayInsideTheSafeArea() {
        val safe = Rect(20, 10, 30, 25)
        val pin = InputSurfaceElement(InputControl.A, .05f, .8f, .2f, .15f, "pin")
        val shortcuts = OnScreenLayout.steamShortcuts(1000, 600, safe, 2f, listOf(pin))
        val (steam, qam) = shortcuts
        assertEquals(OnScreenLayout.STEAM_SHORTCUT_ID, steam.id)
        assertEquals(OnScreenLayout.QAM_SHORTCUT_ID, qam.id)
        assertTrue(steam.y + steam.height < pin.y)
        assertEquals(OnScreenLayout.steamShortcuts(1000, 600, safe, 2f).last().y, qam.y)
        assertInside(shortcuts, 1000, 600, safe)

        val tall = pin.copy(y = .1f, height = .85f)
        val beside = OnScreenLayout.steamShortcuts(1000, 600, safe, 2f, listOf(tall))
        assertEquals(2, beside.size)
        assertTrue(beside.first().x > tall.x + tall.width)
        assertInside(beside, 1000, 600, safe)

        val cover = pin.copy(x = 0f, y = 0f, width = 1f, height = 1f)
        assertTrue(OnScreenLayout.steamShortcuts(1000, 600, safe, 2f, listOf(cover)).isEmpty())
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

    @Test fun onlyAnUntouchedBuiltInGamepadOrACopyOfOneFollowsTheKit() {
        assertTrue(OnScreenLayout.generated(gamepad()))
        assertTrue(OnScreenLayout.generated(SavedInputLayout("copy", "Copy", origin = InputLibraries.OVERLAY)))
        assertFalse(OnScreenLayout.generated(SavedInputLayout("custom", "Custom")))
        assertFalse(OnScreenLayout.generated(SavedInputLayout("copy", "Copy", origin = InputLibraries.DECK)))
        val edited = gamepad().copy(elements = listOf(InputSurfaceElement(InputControl.A, .5f, .5f, .1f, .1f)), aspect = 2f)
        assertFalse(OnScreenLayout.generated(edited))
        assertEquals(listOf("legacy:a"), OnScreenLayout.elements(context, edited, 2000, 1000, Rect()).map { it.id })
    }

    @Test fun theBuiltInGamepadFollowsTheVirtualControllersKitAndScale() {
        for ((type, kit) in listOf(SessionPrefs.CONTROLLER_DECK to PadKit.STEAM_DECK, SessionPrefs.CONTROLLER_XBOX360 to PadKit.XBOX,
            SessionPrefs.CONTROLLER_DUALSENSE to PadKit.DUALSENSE)) {
            SessionPrefs.setSteamController(context, type)
            val safe = Rect(40, 0, 20, 10)
            assertEquals(KitLayout.elements(context, kit, 2400, 1080, safe), OnScreenLayout.elements(context, gamepad(), 2400, 1080, safe))
            assertEquals(KitLayout.elements(context, kit, 2400, 1080, safe, 1.25f), OnScreenLayout.elements(context, gamepad().copy(scale = 1.25f), 2400, 1080, safe))
        }
        assertEquals(emptyList<InputSurfaceElement>(), OnScreenLayout.elements(context, null, 2400, 1080, Rect()))
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
            val generatedEdit = KitLayout.elements(context, PadKit.current(context), w, h).map { if (it.id == "legacy:a") it.copy(x = .3f, y = .4f, width = .12f, height = .12f) else it }
            val generatedSaved = generated.copy(elements = generatedEdit, aspect = w.toFloat() / h)
            val generatedPixels = px(OnScreenLayout.elements(context, generatedSaved, w, h, Rect()).first { it.id == "legacy:a" }, w, h)
            val generatedRefresh = px(OnScreenLayout.editorElements(context, generatedSaved, w, h, edited = true).first { it.id == "legacy:a" }, w, h)
            assertArrayEquals(generatedPixels, generatedRefresh, .001f)

            val custom = SavedInputLayout("custom", "Custom", listOf(InputSurfaceElement(InputControl.A, .3f, .4f, .12f, .12f)), aspect = w.toFloat() / h)
            val customRefresh = OnScreenLayout.editorElements(context, custom, w, h, edited = true)
            assertArrayEquals(px(customRefresh.single(), w, h), px(OnScreenLayout.editorElements(context, custom.copy(elements = customRefresh), w, h, edited = true).single(), w, h), .001f)
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

    @Test fun aNarrowLandscapeLayoutOnAWideLandscapeScreenKeepsItsEdgeAnchoring() {
        val right = InputSurfaceElement(InputControl.A, .9f, .4f, .06f, .08f, "a")
        val layout = SavedInputLayout("edited", "Edited", listOf(right), aspect = 4f / 3f)
        val (x, _, w, _) = px(OnScreenLayout.elements(context, layout, 2400, 1029, Rect()).single(), 2400, 1029).toList()
        assertEquals(2400f - .04f * 1029f * 4f / 3f, x + w, 1f)
    }

    @Test fun aLandscapeLayoutShownInPortraitKeepsItsClustersTogetherAtTheBottom() {
        val y = InputSurfaceElement(InputControl.Y, .8f, .25f, .08f, .16f, "y")
        val a = InputSurfaceElement(InputControl.A, .8f, .6f, .08f, .16f, "a")
        val layout = SavedInputLayout("edited", "Edited", listOf(y, a), aspect = 2f)
        val (_, yTop, _, yHeight) = px(OnScreenLayout.elements(context, layout, 1000, 2000, Rect(0, 0, 0, 40)).single { it.id == "y" }, 1000, 2000).toList()
        val (_, aTop, _, _) = px(OnScreenLayout.elements(context, layout, 1000, 2000, Rect(0, 0, 0, 40)).single { it.id == "a" }, 1000, 2000).toList()
        assertEquals(.35f * 500f, aTop - yTop, 1f)
        assertEquals(2000f - 40f - .75f * 500f, yTop, 1f)
        assertEquals(.16f * 500f, yHeight, 1f)
    }

    @Test fun controlsStayOutOfTheDisplayCutout() {
        val safe = Rect(120, 0, 0, 40)
        val generated = OnScreenLayout.elements(context, gamepad(), 2400, 1080, safe)
        assertInside(generated, 2400, 1080, safe)
        assertNotEquals(OnScreenLayout.elements(context, gamepad(), 2400, 1080, Rect()), generated)
        val edge = SavedInputLayout("edited", "Edited", listOf(InputSurfaceElement(InputControl.B, 0f, .9f, .05f, .1f)), aspect = 2400f / 1080)
        assertInside(OnScreenLayout.elements(context, edge, 2400, 1080, safe), 2400, 1080, safe)
    }

    @Test fun theUnfoldedStripUsesTheDockGeometryInLandscapeAndPortrait() {
        for ((size, portrait) in listOf((2208 to 800) to false, (1080 to 1300) to true)) {
            val (w, h) = size
            val placed = OnScreenLayout.elements(context, gamepad(), w, h, Rect(), dock = OnScreenLayout.Dock(portrait, grips = true))
            val steam = PadKit.current(context).steam
            val boxes = GamepadGeometry.layout(w.toFloat(), h.toFloat(), density, portrait, grips = steam, trackpads = steam)
            val ls = placed.first { it.id == "legacy:ls" }
            val box = boxes.getValue(GamepadGeometry.Slot.LEFT_MAIN)
            assertEquals(box.left, ls.x * w, 1f)
            assertEquals(box.width, ls.width * w, 1f)
            assertEquals(box.height, ls.height * h, 1f)
            assertSquare(placed.first { it.id == "legacy:a" }, w, h)
            assertNull(placed.firstOrNull { it.control == InputControl.GUIDE || it.control == InputControl.QAM })
            assertEquals(steam && GamepadGeometry.Slot.L4 in boxes, placed.any { it.control == InputControl.L4 })
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
    }

    @Test fun migratedScreenKeysAreDrawnNextToTheKitGamepad() {
        prefs.edit().clear().commit()
        ControllerPrefs.prefs(context).edit().putString("screenKeys", legacyKeys).commit()
        val layout = InputLibraries.read(context).activeLayout(false, InputRole.PRIMARY)!!
        val placed = OnScreenLayout.elements(context, layout, 2400, 1080, Rect()).map { it.id }
        assertTrue(placed.contains("imported:0"))
        assertTrue(placed.containsAll(listOf("legacy:a", KitLayout.DPAD_ID, "legacy:ls")))
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

    @Test fun aDeckStripPutsTheDeckGamepadSurfacesInTheStickSlotsAndOtherPadsKeepSticks() {
        val stock = InputStockLayouts.layouts().first { it.id == DeckPanel.GAMEPAD.layout }
        val deck = OnScreenLayout.Dock.deck(false, stock)
        assertEquals(OnScreenLayout.Dock(false, true, InputControl.LS, InputControl.RIGHT_PAD), deck)
        assertEquals(deck, OnScreenLayout.Dock.deck(false, null))
        val (w, h) = 2208 to 800
        val boxes = GamepadGeometry.layout(w.toFloat(), h.toFloat(), density, false)
        val placed = OnScreenLayout.elements(context, gamepad(), w, h, Rect(), dock = deck)
        val pad = placed.single { it.control == InputControl.RIGHT_PAD }
        assertEquals(InputElementKind.TRACKPAD, pad.kind)
        assertEquals(boxes.getValue(GamepadGeometry.Slot.RIGHT_MAIN).left, pad.x * w, 1f)
        assertNull(placed.firstOrNull { it.control == InputControl.RS })
        assertTrue(placed.any { it.control == InputControl.LS })
        val plain = OnScreenLayout.elements(context, gamepad(), w, h, Rect(), dock = OnScreenLayout.Dock(false, false))
        assertTrue(plain.any { it.control == InputControl.RS })
        assertNull(plain.firstOrNull { it.kind == InputElementKind.TRACKPAD })
        val swapped = stock.copy(elements = stock.elements.map {
            when (it.control) {
                InputControl.LS -> it.copy(control = InputControl.LEFT_PAD, id = "legacy:left-pad", kind = InputElementKind.TRACKPAD)
                InputControl.RIGHT_PAD -> it.copy(control = InputControl.RS, id = "legacy:rs", kind = InputElementKind.STICK)
                else -> it
            }
        })
        assertEquals(OnScreenLayout.Dock(false, true, InputControl.LEFT_PAD, InputControl.RS), OnScreenLayout.Dock.deck(false, swapped))
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
        assertEquals(OnScreenLayout.Dock(false, true, InputControl.LEFT_PAD, InputControl.RS), OnScreenLayout.Dock.deck(false, deckGamepad(upgraded)))
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
}
