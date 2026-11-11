package com.droiddeck.launcher.input

import android.content.Context
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
class InputLibraryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshLibraryHasTheMainGamepadAndTheStockLowerScreenLayouts() {
        val library = InputLibraries.read(context)
        assertEquals(listOf("game", "desktop"), library.profiles.map { it.id })
        assertEquals(setOf(InputLibraries.OVERLAY, InputLibraries.TRACKPAD) + DeckPanel.entries.map { it.layout }, library.layouts.map { it.id }.toSet())
        assertEquals(10, library.bindings.size)
        for (panel in DeckPanel.entries) assertEquals(panel.layout, library.panelLayout(false, panel.bindings)?.id)
        assertEquals(InputLibraries.DECK, library.activeLayout(false, InputRole.SECONDARY)?.id)
        assertEquals(InputLibraries.TRACKPAD, library.activeLayout(true, InputRole.SECONDARY)?.id)
        assertEquals(InputAction.Pointer(), library.compileActive(true).action(InputRole.PHYSICAL, InputControl.LS))
        assertEquals(InputAction.Native(), library.compileActive(false).action(InputRole.PHYSICAL, InputControl.LS))

        val desktop = library.activeLayout(true, InputRole.SECONDARY)!!
        val pad = desktop.elements.single { it.kind == InputElementKind.TRACKPAD }
        assertTrue(pad.mouseGestures)
        assertEquals(1, desktop.elements.size)
        assertEquals(InputAction.Pointer(), library.compileActive(true).elementAction(InputRole.SECONDARY, pad.id, pad.control))
        assertEquals(InputAction.MouseButton(272), library.compileActive(true).elementClickAction(InputRole.SECONDARY, pad))
        val game = library.activeLayout(false, InputRole.SECONDARY)!!
        assertEquals(6, game.elements.size)
        assertEquals(setOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD), game.elements.filter { it.kind == InputElementKind.TRACKPAD }.map { it.control }.toSet())
        for (control in listOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)) {
            val element = game.elements.single { it.control == control }
            assertEquals(InputAction.Native(), library.compileActive(false).elementAction(InputRole.SECONDARY, element.id, element.control))
        }
        for (layout in InputStockLayouts.layouts()) assertTrue(layout.elements.none { it.id.startsWith("command:") })
        assertTrue(game.elements.filter { it.kind == InputElementKind.TRACKPAD }.all { it.y == .02f })
        assertEquals(library, InputLibraries.decode(InputLibraries.encode(library)))
    }

    @Test fun legacyLowerScreenBindingsKeepTheirOriginalControlsWithoutSavedGeometry() {
        prefs.edit().putBoolean("migrated", true).putString("profile.game",
            """{"version":1,"bindings":[{"role":"SECONDARY","control":"a","action":{"type":"keys","codes":[57]}}]}""").commit()
        val library = InputLibraries.read(context)
        assertEquals(InputSurfaceLayout.defaults(), library.layout("game", InputRole.SECONDARY)?.elements)
        assertEquals(InputAction.Keys(listOf(57)), library.compile("game").elementAction(InputRole.SECONDARY, "legacy:a", InputControl.A))
    }

    @Test fun existingLibrariesKeepTheirSavedLayoutsBindingsAndSelections() {
        var original = InputLibraries.read(context).duplicateProfile("desktop", "Browser", "browser")
        val layout = SavedInputLayout("builtin.left-pad", "My left pad", InputSurfaceLayout.defaults(), screen = InputLayoutScreen.SECONDARY)
        original = original.withLayout(layout).withPreset(SavedInputBindings("preset.left-pad", "My left pad", false, layout.id,
            controls = mapOf(InputControl.LEFT_PAD to InputAction.Pointer(700f))))
            .selectProfile(true, "browser").selectPart(true, InputRole.SECONDARY, "preset.left-pad").validate()
        val raw = org.json.JSONObject(InputLibraries.encode(original)).put("version", 6).toString()
        prefs.edit().putString("library", raw).commit()
        assertEquals(original, InputLibraries.read(context))
        assertEquals(original, InputLibraries.read(context))
    }

    @Test fun resettingDefaultActionsKeepsTheStockPanelActions() {
        val library = InputLibraries.read(context)
        for (id in listOf("game", "desktop")) {
            val reset = library.resetProfile(id).validate()
            for (element in library.layout(id, InputRole.SECONDARY)!!.elements) {
                assertEquals(library.compile(id).elementAction(InputRole.SECONDARY, element.id, element.control),
                    reset.compile(id).elementAction(InputRole.SECONDARY, element.id, element.control))
            }
        }
    }

    @Test fun migrationPreservesExistingBindingsGeometryAndActiveSelection() {
        prefs.edit().putString("selected", "desktop").putBoolean("migrated", true)
            .putString("profile.desktop", """{"version":1,"bindings":[{"role":"PHYSICAL","control":"a","action":{"type":"keys","codes":[29,33]}}]}""").commit()
        InputSurfaceLayout.saveLegacy(context, "desktop", listOf(InputSurfaceElement(InputControl.B, .3f, .2f, .4f, .4f)))
        val legacy = InputProfiles.readLegacy(context, "desktop")
        assertEquals(InputAction.Keys(listOf(29, 33)), legacy.action(InputRole.PHYSICAL, InputControl.A))
        val library = InputLibraries.read(context)
        assertEquals(legacy.bindings, library.compile("desktop").bindings)
        assertEquals(legacy.keys, library.compile("desktop").keys)
        assertEquals("desktop", library.selected)
        assertEquals(.3f, library.layout("desktop", InputRole.SECONDARY)!!.elements.single().x)
        assertEquals(library, InputLibraries.decode(InputLibraries.encode(library)))
    }

    @Test fun profilesSharePhysicalBindingsWhileUsingDifferentTouchActions() {
        val original = InputLibraries.read(context)
        val shared = original.duplicateProfile("desktop", "Editor", "editor", sharePhysical = true)
        val p = shared.preset(shared.profile("desktop").binding(InputRole.PHYSICAL))
        val updated = shared.withPreset(p.copy(controls = p.controls + (InputControl.R3 to InputAction.Keys(listOf(29, 33)))))
            .copyBindings(shared.profile("editor").binding(InputRole.SECONDARY), "Editor touch", "editor", InputRole.SECONDARY, "editor.touch")
        val touch = updated.preset("editor.touch")
        val final = updated.withPreset(touch.copy(controls = touch.controls + (InputControl.A to InputAction.Keys(listOf(28))))).validate()
        assertEquals(InputAction.Keys(listOf(29, 33)), final.compile("editor").action(InputRole.PHYSICAL, InputControl.R3))
        assertEquals(InputAction.Keys(listOf(29, 33)), final.compile("desktop").action(InputRole.PHYSICAL, InputControl.R3))
        assertEquals(InputAction.MouseButton(272), final.compile("desktop").action(InputRole.SECONDARY, InputControl.A))
        assertEquals(InputAction.Keys(listOf(28)), final.compile("editor").action(InputRole.SECONDARY, InputControl.A))
        assertEquals(final.layout("desktop", InputRole.SECONDARY)!!.elements, final.layout("editor", InputRole.SECONDARY)!!.elements)
        assertNotEquals(final.layout("desktop", InputRole.SECONDARY)!!.id, final.layout("editor", InputRole.SECONDARY)!!.id)
    }

    @Test fun copyingPhysicalBindingsOnlyRelinksTheSelectedProfile() {
        val library = InputLibraries.read(context).duplicateProfile("desktop", "Editor", "editor")
        val id = library.profile("desktop").binding(InputRole.PHYSICAL)
        val copied = library.copyBindings(id, "Editor controller", "editor", InputRole.PHYSICAL, "private")
        assertEquals(id, copied.profile("desktop").binding(InputRole.PHYSICAL))
        assertEquals("private", copied.profile("editor").binding(InputRole.PHYSICAL))
        assertEquals(library.preset(id).controls, copied.preset("private").controls)
        assertEquals(library.profile("editor").parts, library.copyBindings(id, "Library copy").profile("editor").parts)
    }

    @Test fun copyingProfileIsIndependentAndPreservesElementActions() {
        val original = InputLibraries.read(context)
        val library = original.duplicateProfile("desktop", "Editor", "editor").validate()
        for (role in InputRole.entries) {
            assertNotEquals(library.profile("desktop").binding(role), library.profile("editor").binding(role))
            assertEquals(library.compile("desktop").bindings[role], library.compile("editor").bindings[role])
            if (library.layout("desktop", role)?.editable == true) {
                assertNotEquals(library.layout("desktop", role)!!.id, library.layout("editor", role)!!.id)
                assertEquals(library.layout("desktop", role)!!.elements, library.layout("editor", role)!!.elements)
            }
        }
        assertEquals(original.profiles, library.profiles.filterNot { it.id == "editor" })
        assertEquals(original.selected, library.selected)
    }

    @Test fun sharingGeometryKeepsActionsIndependentAndDetachingCopiesBoth() {
        var library = InputLibraries.read(context).duplicateProfile("desktop", "Editor", "editor")
        val source = library.layout("desktop", InputRole.SECONDARY)!!
        library = library.useLayout("editor", InputRole.SECONDARY, source.id, shared = true).validate()
        assertEquals(source.id, library.layout("editor", InputRole.SECONDARY)!!.id)
        assertNotEquals(library.profile("desktop").binding(InputRole.SECONDARY), library.profile("editor").binding(InputRole.SECONDARY))
        library = library.usePart("editor", InputRole.SECONDARY, "desktop", shared = true).validate()
        assertEquals(library.profile("desktop").parts[InputRole.SECONDARY], library.profile("editor").parts[InputRole.SECONDARY])
        val detached = library.usePart("editor", InputRole.SECONDARY, "editor", shared = false).validate()
        assertNotEquals(source.id, detached.layout("editor", InputRole.SECONDARY)!!.id)
        assertNotEquals(detached.profile("desktop").binding(InputRole.SECONDARY), detached.profile("editor").binding(InputRole.SECONDARY))
        assertEquals(library.compile("editor"), detached.compile("editor"))
    }

    @Test fun elementIdsSurviveGeometryEditsAndDeletionPrunesEveryLinkedPreset() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        val e = InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, "button-1")
        val b = library.preset(library.profile("game").binding(InputRole.SECONDARY))
        val saved = library.withLayout(layout.copy(elements = listOf(e)))
            .withPreset(b.copy(elements = mapOf(e.id to InputAction.Keys(listOf(57)))))
            .copyBindings(b.id, "Alternative", newId = "alternative").validate()
        val moved = saved.withLayout(layout.copy(elements = listOf(e.copy(x = .6f)))).validate()
        assertEquals(InputAction.Keys(listOf(57)), moved.preset(b.id).action(e))
        val deleted = moved.withLayout(layout.copy(elements = emptyList())).validate()
        assertTrue(deleted.preset(b.id).elements.isEmpty())
        assertTrue(deleted.preset("alternative").elements.isEmpty())
    }

    @Test fun missingAndIncompatibleReferencesAreRejected() {
        val library = InputLibraries.read(context)
        assertThrows(IllegalArgumentException::class.java) { library.removeBindings(library.profile("game").binding(InputRole.PHYSICAL)) }
        assertThrows(IllegalArgumentException::class.java) { library.removeLayout(library.layout("game", InputRole.SECONDARY)!!.id) }
        assertThrows(IllegalArgumentException::class.java) { library.chooseBindings("game", InputRole.PHYSICAL, library.profile("game").binding(InputRole.SECONDARY)) }
        assertThrows(IllegalArgumentException::class.java) { library.chooseLayout("game", InputRole.PHYSICAL, InputLibraries.DECK) }
    }

    @Test fun deletingActiveProfileFallsBackWithoutDeletingSharedParts() {
        val library = InputLibraries.read(context).duplicateProfile("desktop", "Editor", "editor")
        val removed = library.copy(selected = "editor", desktop = "editor").removeProfile("editor").validate()
        assertEquals(InputProfiles.AUTO, removed.selected)
        assertEquals("desktop", removed.desktop)
        assertEquals(library.layouts, removed.layouts)
        assertEquals(library.bindings, removed.bindings)
    }

    @Test fun futureLibrariesAreNotOverwritten() {
        val future = org.json.JSONObject(InputLibraries.encode(InputLibraries.read(context))).put("version", 999).toString()
        prefs.edit().putString("library", future).commit()
        InputLibraries.read(context)
        assertThrows(IllegalArgumentException::class.java) { InputLibraries.update(context) { it.copy(selected = "desktop") } }
        assertEquals(future, prefs.getString("library", null))
    }

    @Test fun unreadableLibrariesKeepStableEditorReferencesUntilAnEditPreservesRecoveryData() {
        val valid = InputLibraries.encode(InputLibraries.read(context))
        val invalidValues = listOf("{broken", org.json.JSONObject(valid).put("selected", "missing").toString())
        for (raw in invalidValues) {
            prefs.edit().putString("library", raw).commit()
            val first = InputLibraries.read(context)
            val second = InputLibraries.read(context)
            assertEquals(first, second)
            val id = first.profile("game").binding(InputRole.PRIMARY)
            assertEquals(first.preset(id), second.preset(id))
            assertEquals(raw, prefs.getString("library", null))
            val updated = InputLibraries.update(context) { it.copy(selected = "desktop") }
            assertEquals("desktop", InputLibraries.read(context).selected)
            assertEquals(updated, InputLibraries.read(context))
            assertTrue(prefs.all.filterKeys { it.startsWith("library-recovery.") }.containsValue(raw))
        }
        assertTrue(InputLibraries.hasLibraryRecovery(context))
        assertEquals(invalidValues.toSet(), prefs.all.filterKeys { it.startsWith("library-recovery.") }.values.toSet())
    }

    @Test fun resettingAProfileDoesNotResetSharedBindingsOrAccumulatePrivateCopies() {
        var library = InputLibraries.read(context).duplicateProfile("desktop", "Editor", "editor", sharePhysical = true)
        val common = library.preset(library.profile("desktop").binding(InputRole.PHYSICAL))
        library = library.withPreset(common.copy(controls = common.controls + (InputControl.A to InputAction.Keys(listOf(57)))))
            .resetProfile("editor").validate()
        val count = library.bindings.size
        repeat(100) { library = library.resetProfile("editor").validate() }
        assertEquals(count, library.bindings.size)
        assertEquals(InputAction.Keys(listOf(57)), library.compile("desktop").action(InputRole.PHYSICAL, InputControl.A))
        assertEquals(InputAction.MouseButton(272), library.compile("editor").action(InputRole.PHYSICAL, InputControl.A))
    }

    @Test fun clickActionsRoundTripAndArePrunedWithTheirElement() {
        val library = InputLibraries.read(context)
        val layout = library.layout("desktop", InputRole.SECONDARY)!!
        val pad = layout.elements.first { it.hasClick }
        val preset = library.preset(library.profile("desktop").binding(InputRole.SECONDARY))
        val updated = library.withPreset(preset.copy(clicks = mapOf(pad.id to InputAction.Keys(listOf(29, 33))))).validate()
        val roundTrip = InputLibraries.decode(InputLibraries.encode(updated))!!
        assertEquals(InputAction.Keys(listOf(29, 33)), roundTrip.compile("desktop").elementClickAction(InputRole.SECONDARY, pad))
        assertEquals(preset.action(pad), roundTrip.preset(preset.id).action(pad))
        assertTrue(roundTrip.withLayout(layout.copy(elements = emptyList())).preset(preset.id).clicks.isEmpty())
    }
    @Test fun versionTwoFixedGamepadMigratesOnceWithoutChangingOtherProfiles() {
        val original = InputLibraries.read(context)
        val fixed = original.copy(layouts = original.layouts.map { if (it.id == InputLibraries.OVERLAY) it.copy(elements = emptyList(), template = InputLayoutTemplate.OVERLAY) else it },
            bindings = original.bindings.map { if (it.layoutId == InputLibraries.OVERLAY) it.copy(layoutId = null, elements = emptyMap(), clicks = emptyMap()) else it })
        val raw = org.json.JSONObject(InputLibraries.encode(fixed)).put("version", 2).toString()
        prefs.edit().putString("library", raw).commit()
        ControllerPrefs.setStickClick(context, false)
        ControllerPrefs.setKeyboardButton(context, true)
        val size = context.resources.displayMetrics
        ControllerPrefs.setLayout(context, size.widthPixels, size.heightPixels, mapOf("a" to (.5f to .5f), "ls" to (.3f to .4f)))
        ControllerPrefs.setLayout(context, 1600, 900, mapOf("ls" to (.3f to .4f)))
        val migrated = InputLibraries.read(context)
        val saved = prefs.getString("library", null)
        assertEquals(migrated, InputLibraries.read(context))
        assertEquals(saved, prefs.getString("library", null))
        assertFalse(migrated.layouts.any { it.template == InputLayoutTemplate.OVERLAY })
        assertTrue(migrated.layouts.any { it.id == "migrated.layout.1600x900" })
        assertEquals(original.layout("game", InputRole.SECONDARY), migrated.layout("game", InputRole.SECONDARY))
        assertEquals(original.preset(original.profile("game").binding(InputRole.PHYSICAL)), migrated.preset(migrated.profile("game").binding(InputRole.PHYSICAL)))
        val layout = migrated.layout("game", InputRole.PRIMARY)!!
        val preset = migrated.preset(migrated.profile("game").binding(InputRole.PRIMARY))
        assertTrue(layout.editable)
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), preset.elements["builtin:keyboard"])
        assertEquals(InputAction.Disabled, preset.clickAction(layout.elements.first { it.control == InputControl.LS }))
    }

    @Test fun oldGamepadPlacementAndVisibilityBecomeEditableElements() {
        ControllerPrefs.setLayout(context, 1200, 800, mapOf("ls" to (.4f to .5f)))
        ControllerPrefs.setSteamButton(context, false)
        ControllerPrefs.setTarget(context, "b", ControllerPrefs.OFF)
        val elements = LegacyGamepadLayout(context, 1200, 800).elements()
        val stick = elements.first { it.control == InputControl.LS }
        assertEquals(.4f, stick.x + stick.width - stick.height * 800f / 1200f / 2f, .0001f)
        assertEquals(.5f, stick.y + stick.height / 2f, .0001f)
        assertFalse(elements.any { it.control == InputControl.GUIDE || it.control == InputControl.B })
        assertTrue(elements.all { it == it.bounded() })
    }

    @Test fun gamepadControlsAreRoundWithShoulderPillsAndACompactKeyboard() {
        val elements = LegacyGamepadLayout(context, 1200, 800).elements()
        val density = context.resources.displayMetrics.density
        for (element in elements.filter { it.id.startsWith("legacy:") }) {
            val shoulder = element.control in setOf(InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT)
            assertEquals(element.control.id, if (shoulder) InputButtonShape.PILL else InputButtonShape.CIRCLE, element.buttonShape)
        }
        val keyboard = elements.single { it.id == "builtin:keyboard" }
        assertEquals(InputButtonIcons.KEYBOARD, keyboard.icon)
        assertEquals(InputButtonShape.CIRCLE, keyboard.buttonShape)
        assertEquals(36 * density / 1200f, keyboard.width, .0001f)
        assertEquals(90 * density / 1200f, keyboard.x + keyboard.width / 2f, .0001f)
        assertEquals(1f - 34 * density / 800f, keyboard.y + keyboard.height / 2f, .0001f)
    }

    @Test fun dpadButtonsMatchTheFaceButtonsInSizeAndSpacing() {
        val elements = LegacyGamepadLayout(context, 1200, 800).elements()
        fun centre(c: InputControl) = elements.single { it.id == "legacy:${c.id}" }.let { (it.x + it.width / 2f) * 1200 to (it.y + it.height / 2f) * 800 }
        fun size(c: InputControl) = elements.single { it.id == "legacy:${c.id}" }.width * 1200
        assertEquals(size(InputControl.A), size(InputControl.UP), .01f)
        assertEquals(centre(InputControl.A).second - centre(InputControl.Y).second, centre(InputControl.DOWN).second - centre(InputControl.UP).second, .01f)
        assertEquals(centre(InputControl.B).first - centre(InputControl.X).first, centre(InputControl.RIGHT).first - centre(InputControl.LEFT).first, .01f)
    }

    @Test fun anUntouchedSavedDpadIsResizedAroundItsCentreAndAMovedOneIsKept() {
        val fresh = LegacyGamepadLayout(context, 1200, 800)
        val target = fresh.elements().filter { it.control in setOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT) }
        val cx = target.map { it.x + it.width / 2f }.average().toFloat()
        val cy = target.map { it.y + it.height / 2f }.average().toFloat()
        val scale = 26f / 30f
        val old = target.map { e ->
            val ex = cx + (e.x + e.width / 2f - cx) * 52f / 50f
            val ey = cy + (e.y + e.height / 2f - cy) * 52f / 50f
            e.copy(x = ex - e.width * scale / 2f, y = ey - e.height * scale / 2f, width = e.width * scale, height = e.height * scale, buttonShape = InputButtonShape.ROUNDED)
        }
        val restyled = fresh.restyle(old)
        for ((want, got) in target.zip(restyled)) {
            assertEquals(want.x, got.x, .0005f); assertEquals(want.y, got.y, .0005f)
            assertEquals(want.width, got.width, .0005f); assertEquals(InputButtonShape.CIRCLE, got.buttonShape)
        }
        val moved = old.mapIndexed { i, e -> if (i == 0) e.copy(buttonShape = InputButtonShape.RECTANGLE) else e }
        assertEquals(moved.drop(1).map { it.width }, fresh.restyle(moved).drop(1).map { it.width })
        val shifted = old.mapIndexed { i, e -> if (i == 0) e.copy(x = e.x + .03f) else e }
        assertEquals(shifted.map { listOf(it.x, it.y, it.width, it.height) }, fresh.restyle(shifted).map { listOf(it.x, it.y, it.width, it.height) })
    }

    @Test fun stickTouchAreasReachFurtherOutwardButKeepTheirInnerEdge() {
        val elements = LegacyGamepadLayout(context, 1200, 800).elements()
        for (control in listOf(InputControl.LS, InputControl.RS)) {
            val stick = elements.single { it.control == control }
            assertEquals(control.id, stick.height * 800f * 1.25f, stick.width * 1200f, 1f)
        }
        val left = elements.single { it.control == InputControl.LS }
        val right = elements.single { it.control == InputControl.RS }
        ControllerPrefs.setLayout(context, 1200, 800, mapOf("ls" to (.3f to .5f), "rs" to (.7f to .5f)))
        val placed = LegacyGamepadLayout(context, 1200, 800).elements()
        val ls = placed.single { it.control == InputControl.LS }
        val rs = placed.single { it.control == InputControl.RS }
        assertEquals(.3f, ls.x + ls.width - ls.height * 800f / 1200f / 2f, .0001f)
        assertEquals(.7f, rs.x + rs.height * 800f / 1200f / 2f, .0001f)
        assertTrue(left.width > left.height * 800f / 1200f && right.width > right.height * 800f / 1200f)
    }

    @Test fun adaptiveSticksToggleChangesOnlyTheSticksOfThatLayout() {
        val library = InputLibraries.read(context)
        val overlay = library.layouts.single { it.id == InputLibraries.OVERLAY }
        assertEquals(true, overlay.adaptiveSticks)
        InputLibraries.setAdaptiveSticks(context, overlay.id, false)
        val off = InputLibraries.read(context)
        val changed = off.layouts.single { it.id == overlay.id }
        assertEquals(false, changed.adaptiveSticks)
        assertFalse(ControllerPrefs.read(context).adaptiveSticks)
        assertEquals(overlay.elements.filter { it.kind != InputElementKind.STICK }, changed.elements.filter { it.kind != InputElementKind.STICK })
        assertEquals(library.layouts.filter { it.id != overlay.id }, off.layouts.filter { it.id != overlay.id })
        assertNull(off.layouts.single { it.id == InputLibraries.DECK }.adaptiveSticks)
        assertTrue(InputLibraries.setAdaptiveSticks(context, overlay.id, true))
        assertEquals(true, InputLibraries.read(context).layouts.single { it.id == overlay.id }.adaptiveSticks)
        prefs.edit().putString("library", JSONObject().put("version", 99).toString()).commit()
        assertFalse(InputLibraries.setAdaptiveSticks(context, overlay.id, false))
        assertTrue(ControllerPrefs.read(context).adaptiveSticks)
    }

    @Test fun savedGamepadsFromTheFirstUnifiedBuildGetTheRoundLookOnce() {
        val fresh = InputLibraries.read(context)
        val overlay = fresh.layouts.single { it.id == InputLibraries.OVERLAY }
        val old = overlay.copy(elements = overlay.elements.map { e ->
            if (e.id == "builtin:keyboard") InputSurfaceElement(InputControl.A, .02f, .9f, .12f, .08f, "builtin:keyboard", name = "Keyboard")
            else if (e.control == InputControl.A) e.copy(buttonShape = InputButtonShape.RECTANGLE, x = .5f)
            else e.copy(buttonShape = InputButtonShape.ROUNDED)
        })
        val custom = SavedInputLayout("mine", "Mine", listOf(InputSurfaceElement(InputControl.B, .1f, .1f, .1f, .1f)))
        val stored = fresh.withLayout(old).withLayout(custom)
        val renamed = InputSurfaceElement(InputControl.A, .02f, .9f, .12f, .08f, "builtin:keyboard", name = "Keys")
        assertEquals(renamed, LegacyGamepadLayout(context, 1200, 800).restyle(listOf(renamed)).single())
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(stored)).put("version", 8).toString()).commit()
        val upgraded = InputLibraries.read(context)
        val restyled = upgraded.layouts.single { it.id == InputLibraries.OVERLAY }.elements
        assertEquals(overlay.elements.single { it.id == "builtin:keyboard" }, restyled.single { it.id == "builtin:keyboard" })
        assertEquals(InputButtonShape.RECTANGLE, restyled.single { it.control == InputControl.A && it.id.startsWith("legacy:") }.buttonShape)
        assertEquals(.5f, restyled.single { it.control == InputControl.A && it.id.startsWith("legacy:") }.x, .0001f)
        assertEquals(InputButtonShape.PILL, restyled.single { it.control == InputControl.LB }.buttonShape)
        assertEquals(InputButtonShape.CIRCLE, restyled.single { it.control == InputControl.UP }.buttonShape)
        assertEquals(InputButtonShape.ROUNDED, upgraded.layouts.single { it.id == "mine" }.elements.single().buttonShape)
        assertEquals(upgraded, InputLibraries.read(context))
        for (desktop in listOf(false, true))
            assertEquals(InputAction.Command(InputCommand.KEYBOARD), upgraded.compileActive(desktop).elementAction(InputRole.PRIMARY, "builtin:keyboard", InputControl.A))
    }

    @Test fun quickPartsAreModeSpecificAndDoNotEditSavedProfiles() {
        val original = InputLibraries.read(context)
        val mixed = original.selectPart(true, InputRole.SECONDARY, InputStockLayouts.TRACKPAD).validate()
        assertEquals(original.profiles, mixed.profiles)
        assertEquals(original.compileActive(false), mixed.compileActive(false))
        assertEquals(InputLibraries.TRACKPAD, mixed.activeLayout(true, InputRole.SECONDARY)?.id)
        assertEquals(InputAction.Pointer(), mixed.compileActive(true).elementAction(InputRole.SECONDARY, "legacy:right-pad", InputControl.RIGHT_PAD))
        assertEquals(mixed, InputLibraries.decode(InputLibraries.encode(mixed)))
        val restored = mixed.selectProfile(true, InputProfiles.AUTO)
        assertTrue(restored.selection(true).parts.isEmpty())
        assertEquals(original.compileActive(true), restored.compileActive(true))
    }

    @Test fun explicitAutoSelectionStaysDistinctFromAnInheritedProfileAfterSaving() {
        val original = InputLibraries.read(context)
        val binding = original.profile("game").binding(InputRole.PHYSICAL)
        val inherited = original.selectPart(false, InputRole.PHYSICAL, binding)
        val explicit = original.selectProfile(false, InputProfiles.AUTO).selectPart(false, InputRole.PHYSICAL, binding)
        assertFalse(InputLibraries.decode(InputLibraries.encode(inherited))!!.selection(false).profileSelected)
        assertTrue(InputLibraries.decode(InputLibraries.encode(explicit))!!.selection(false).profileSelected)
        assertEquals(inherited.compileActive(false), explicit.compileActive(false))
    }

    @Test fun versionSevenSelectionsRetainKnownProfileAndPartChoices() {
        val original = InputLibraries.read(context)
        val binding = original.profile("game").binding(InputRole.PHYSICAL)
        val choices = listOf(
            original.selectPart(false, InputRole.PHYSICAL, binding) to false,
            original.selectProfile(false, InputProfiles.AUTO) to true,
            original.selectProfile(false, "game").selectPart(false, InputRole.PHYSICAL, binding) to true,
            original.selectPart(false, InputRole.SECONDARY, InputStockLayouts.TRACKPAD) to false,
        )
        for ((library, selected) in choices) {
            val old = org.json.JSONObject(InputLibraries.encode(library)).put("version", 7)
            old.getJSONObject("selections").getJSONObject("game").remove("profileSelected")
            prefs.edit().putString("library", old.toString()).commit()
            val restored = InputLibraries.read(context)
            assertEquals(selected, restored.selection(false).profileSelected)
            assertEquals(library, restored)
            assertEquals(restored, InputLibraries.read(context))
        }
    }

    @Test fun savedQuickCombinationOwnsIndependentCopiesOfAllThreeParts() {
        val original = InputLibraries.read(context)
        val mixed = original.selectPart(true, InputRole.SECONDARY, InputStockLayouts.TRACKPAD)
        val saved = mixed.saveSelection(true, "Writing", "writing").validate()
        assertEquals("writing", saved.activeId(true))
        assertTrue(saved.selection(true).parts.isEmpty())
        for (role in InputRole.entries) {
            val source = mixed.activePart(true, role)
            val copy = saved.activePart(true, role)
            assertNotEquals(source.bindings, copy.bindings)
            if (role != InputRole.PHYSICAL) assertNotEquals(source.layout, copy.layout)
            assertEquals(mixed.preset(source.bindings).elements, saved.preset(copy.bindings).elements)
        }
        assertEquals(original.profile("desktop"), saved.profile("desktop"))
    }

    @Test fun activeQuickPartsCannotBeDeletedAndInvalidCombinationsAreRejected() {
        val library = InputLibraries.read(context).selectPart(false, InputRole.SECONDARY, InputStockLayouts.TRACKPAD)
        assertThrows(IllegalArgumentException::class.java) { library.removeBindings(InputStockLayouts.TRACKPAD) }
        assertThrows(IllegalArgumentException::class.java) { library.removeLayout(InputLibraries.TRACKPAD) }
        assertThrows(IllegalArgumentException::class.java) { library.selectPart(false, InputRole.PHYSICAL, InputStockLayouts.TRACKPAD).validate() }
        assertThrows(IllegalArgumentException::class.java) { library.selectProfile(false, "missing").validate() }
    }

    @Test fun oldFixedLowerPanelsMigrateIntoEditableLayoutsWithWorkingCommands() {
        val current = InputLibraries.read(context)
        val profile = current.profile("game")
        val old = current.copy(layouts = current.layouts.map { if (it.id == InputLibraries.DECK) it.copy(elements = emptyList(), template = InputLayoutTemplate.DECK) else it },
            bindings = current.bindings.filterNot { it.layoutId == InputLibraries.DECK })
            .withPreset(SavedInputBindings("old-deck", "Old Deck", false, controls = mapOf(InputControl.L4 to InputAction.Keys(listOf(57)))))
            .withProfile(profile.copy(parts = profile.parts + (InputRole.SECONDARY to InputPart("old-deck", InputLibraries.DECK)))).validate()
        val raw = org.json.JSONObject(InputLibraries.encode(old)).put("version", 3).toString()
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().putString("library", raw).commit()
        val upgraded = InputLibraries.read(context)
        assertTrue(upgraded.layout("game", InputRole.SECONDARY)!!.editable)
        val output = upgraded.compile("game")
        assertEquals(InputAction.Keys(listOf(57)), output.elementAction(InputRole.SECONDARY, "legacy:l4", InputControl.L4))
        assertEquals(InputStockLayouts.layouts().single { it.id == InputLibraries.DECK }.elements, upgraded.layout("game", InputRole.SECONDARY)!!.elements)
        assertEquals(upgraded, InputLibraries.read(context))
    }

    @Test fun stockPresetsAreScopedToTheSecondScreenAndCopiedWithTheirActions() {
        val library = InputLibraries.read(context)
        for (layout in InputStockLayouts.layouts()) {
            val role = InputRole.SECONDARY
            assertFalse(library.layouts.first { it.id == layout.id }.supports(InputRole.PRIMARY))
            val changed = library.useLayout("game", role, layout.id, shared = false).validate()
            val copy = changed.layout("game", role)!!
            assertNotEquals(layout.id, copy.id)
            assertEquals(layout.elements, copy.elements)
            val stock = InputStockLayouts.presets(listOf(layout)).single()
            for (element in layout.elements) assertEquals(stock.elements[element.id] ?: stock.controls[element.control] ?: InputAction.Native(),
                changed.compile("game").elementAction(role, element.id, element.control))
        }
    }

    @Test fun versionFourScopingPreservesLayoutsAlreadySharedAcrossScreens() {
        val original = InputLibraries.read(context)
        val deck = original.layouts.first { it.id == InputLibraries.DECK }.copy(screen = InputLayoutScreen.BOTH)
        val shared = original.withLayout(deck).useLayout("game", InputRole.PRIMARY, deck.id, shared = true)
            .useLayout("game", InputRole.SECONDARY, deck.id, shared = true)
        val json = org.json.JSONObject(InputLibraries.encode(shared)).put("version", 4)
        val layouts = json.getJSONArray("layouts")
        for (i in 0 until layouts.length()) layouts.getJSONObject(i).remove("screen")
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().putString("library", json.toString()).commit()
        val migrated = InputLibraries.read(context)
        assertEquals(InputLayoutScreen.BOTH, migrated.layouts.first { it.id == deck.id }.screen)
        assertEquals(shared.profiles, migrated.profiles)
        assertEquals(shared.bindings, migrated.bindings)
        assertEquals(migrated, InputLibraries.read(context))
    }

    @Test fun horizontalScrollAndLayoutTargetSurviveSerialization() {
        val library = InputLibraries.read(context)
        val preset = library.preset(library.profile("desktop").binding(InputRole.PHYSICAL))
        val changed = library.withPreset(preset.copy(controls = preset.controls + (InputControl.RS to InputAction.Scroll(17f, true))))
        assertEquals(changed, InputLibraries.decode(InputLibraries.encode(changed)))
    }


    @Test fun bottomScreenLayoutsKeepTheNeutralLookAndPickedColorsSurvive() {
        val fresh = InputLibraries.read(context)
        assertNull(fresh.layouts.single { it.id == InputLibraries.DECK }.style.tint)
        assertNull(fresh.layouts.single { it.id == InputLibraries.TRACKPAD }.style.tint)
        assertEquals(ControllerPrefs.STEAM_BLUE, fresh.layouts.single { it.id == InputLibraries.OVERLAY }.style.tint)
        val red = ControllerPrefs.tints.last().first
        val blue = fresh.layouts.map { it.copy(style = it.style.copy(tint = if (it.id == InputLibraries.TRACKPAD) red else ControllerPrefs.STEAM_BLUE)) }
        val mine = SavedInputLayout("mine", "Mine", listOf(InputSurfaceElement(InputControl.B, .1f, .1f, .1f, .1f)))
        val old = JSONObject(InputLibraries.encode(blue.fold(fresh) { library, layout -> library.withLayout(layout) }.withLayout(mine))).put("version", 8)
        prefs.edit().putString("library", old.toString()).commit()
        val upgraded = InputLibraries.read(context)
        assertNull(upgraded.layouts.single { it.id == InputLibraries.DECK }.style.tint)
        assertEquals(red, upgraded.layouts.single { it.id == InputLibraries.TRACKPAD }.style.tint)
        assertEquals(ControllerPrefs.STEAM_BLUE, upgraded.layouts.single { it.id == InputLibraries.OVERLAY }.style.tint)
        assertEquals(ControllerPrefs.STEAM_BLUE, upgraded.layouts.single { it.id == "mine" }.style.tint)
        InputLibraries.update(context) { it.withLayout(it.layouts.single { l -> l.id == "mine" }.let { l -> l.copy(style = l.style.copy(tint = null)) }) }
        assertNull(InputLibraries.read(context).layouts.single { it.id == "mine" }.style.tint)
        assertEquals(InputLibraries.read(context), InputLibraries.read(context))
    }

    @Test fun versionTenStockPanelsReturnToGrayOnce() {
        val fresh = InputLibraries.read(context)
        val blue = fresh.layouts.filter { it.id == InputLibraries.DECK || it.id == InputLibraries.TRACKPAD }
            .map { it.copy(style = it.style.copy(tint = ControllerPrefs.STEAM_BLUE)) }
        val old = JSONObject(InputLibraries.encode(blue.fold(fresh) { library, layout -> library.withLayout(layout) })).put("version", 10)
        prefs.edit().putString("library", old.toString()).commit()
        val upgraded = InputLibraries.read(context)
        assertNull(upgraded.layouts.single { it.id == InputLibraries.DECK }.style.tint)
        assertNull(upgraded.layouts.single { it.id == InputLibraries.TRACKPAD }.style.tint)
        assertEquals(ControllerPrefs.STEAM_BLUE, upgraded.layouts.single { it.id == InputLibraries.OVERLAY }.style.tint)
        assertEquals(upgraded, InputLibraries.read(context))
    }
    private fun versionElevenPanel(layout: SavedInputLayout, commands: List<Int>, padTop: Float = .14f) = layout.copy(elements = commands.mapIndexed { position, i ->
        InputSurfaceElement(InputControl.A, .02f + position * .3f, .01f, .28f, .11f, "command:$i", name = "Command $i", icon = InputButtonIcons.MENU)
    } + layout.elements.map { if (it.kind == InputElementKind.TRACKPAD) it.copy(y = padTop, height = it.y + it.height - padTop) else it })

    private fun versionElevenLibrary(change: (InputLibrary) -> InputLibrary = { it }): InputLibrary {
        val fresh = InputLibraries.read(context)
        val stock = InputStockLayouts.layouts()
        var old = fresh.copy(layouts = fresh.layouts.filter { it.id == InputLibraries.OVERLAY || it.id == InputLibraries.DECK || it.id == InputLibraries.TRACKPAD },
            bindings = fresh.bindings.filter { b -> DeckPanel.entries.none { it != DeckPanel.BOTH && it.bindings == b.id } })
        old = old.withLayout(versionElevenPanel(stock.single { it.id == InputLibraries.DECK }, listOf(2, 3, 0)))
            .withLayout(versionElevenPanel(stock.single { it.id == InputLibraries.TRACKPAD }, listOf(0, 1)))
        old = old.copy(bindings = old.bindings.map { b ->
            when (b.layoutId) {
                InputLibraries.DECK -> b.copy(elements = b.elements + mapOf("command:0" to InputAction.Command(InputCommand.MENU), "command:2" to InputAction.Command(InputCommand.STEAM)))
                InputLibraries.TRACKPAD -> b.copy(elements = b.elements + ("command:1" to InputAction.Command(InputCommand.KEYBOARD)))
                else -> b
            }
        })
        return change(old).validate()
    }

    private fun storeVersionEleven(library: InputLibrary) {
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(library)).put("version", 11).toString()).commit()
    }

    @Test fun versionTwelveMovesStockPanelCommandsToTheBarAndAddsTheDeckTabs() {
        val mine = SavedInputLayout("mine", "Mine", listOf(InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, "command:0", name = "Menu")),
            screen = InputLayoutScreen.SECONDARY)
        val minePreset = SavedInputBindings("mine.bindings", "Mine", false, mine.id, elements = mapOf("command:0" to InputAction.Command(InputCommand.MENU)))
        val old = versionElevenLibrary { it.withLayout(mine).withPreset(minePreset) }
        assertTrue(old.layouts.single { it.id == InputLibraries.DECK }.elements.any { it.id.startsWith("command:") })
        storeVersionEleven(old)
        val upgraded = InputLibraries.read(context)
        val stock = InputStockLayouts.layouts()
        for (id in listOf(InputLibraries.DECK, InputLibraries.TRACKPAD))
            assertEquals(stock.single { it.id == id }.elements, upgraded.layouts.single { it.id == id }.elements)
        assertTrue(upgraded.bindings.filter { it.layoutId == InputLibraries.DECK || it.layoutId == InputLibraries.TRACKPAD }
            .all { b -> b.elements.keys.none { it.startsWith("command:") } })
        assertEquals(InputAction.Pointer(), upgraded.preset(InputStockLayouts.TRACKPAD).elements["legacy:right-pad"])
        assertEquals(mine, upgraded.layouts.single { it.id == mine.id })
        assertEquals(minePreset, upgraded.preset(minePreset.id))
        for (panel in DeckPanel.entries) {
            assertEquals(stock.single { it.id == panel.layout }, upgraded.layouts.single { it.id == panel.layout })
            assertEquals(panel.layout, upgraded.preset(panel.bindings).layoutId)
        }
        assertEquals(old.profiles, upgraded.profiles)
        assertEquals(upgraded, InputLibraries.read(context))
    }

    @Test fun versionTwelveLeavesEditedStockPanelsAlone() {
        val old = versionElevenLibrary { library ->
            val trackpad = library.layouts.single { it.id == InputLibraries.TRACKPAD }
            library.withLayout(trackpad.copy(elements = trackpad.elements.map { if (it.kind == InputElementKind.TRACKPAD) it.copy(x = .1f, width = .8f) else it }))
        }
        storeVersionEleven(old)
        val upgraded = InputLibraries.read(context)
        assertEquals(old.layouts.single { it.id == InputLibraries.TRACKPAD }, upgraded.layouts.single { it.id == InputLibraries.TRACKPAD })
        assertEquals(old.preset(InputStockLayouts.TRACKPAD), upgraded.preset(InputStockLayouts.TRACKPAD))
        assertEquals(InputStockLayouts.layouts().single { it.id == InputLibraries.DECK }.elements, upgraded.layouts.single { it.id == InputLibraries.DECK }.elements)
    }

    @Test fun stockSecondScreenPanelsKeepTapsClicking() {
        val library = InputLibraries.read(context)
        for (desktop in listOf(false, true)) for (panel in listOf(null, InputStockLayouts.TRACKPAD) + DeckPanel.entries.map { it.bindings }) {
            val shown = library.panelLayout(desktop, panel)!!.elements
            assertTrue(shown.isNotEmpty())
            assertFalse(library.compileActive(desktop, panel).showsMouseButton(InputRole.SECONDARY, shown, library.compileStock(desktop, panel)))
        }
    }

    @Test fun userBoundSecondScreenMouseButtonIsDetected() {
        val stock = InputLibraries.read(context)
        val grips = DeckPanel.GRIPS.bindings
        val button = stock.panelLayout(false, grips)!!.elements.first { it.control.dimensions == 1 }
        val bound = stock.withPreset(stock.preset(grips).copy(elements = mapOf(button.id to InputAction.MouseButton(272))))
        for (desktop in listOf(false, true)) {
            assertTrue(bound.compileActive(desktop, grips).showsMouseButton(InputRole.SECONDARY, listOf(button), bound.compileStock(desktop, grips)))
            assertFalse(bound.compileActive(desktop, grips).showsMouseButton(InputRole.PRIMARY, listOf(button), bound.compileStock(desktop, grips)))
        }
        val pad = stock.panelLayout(true, InputStockLayouts.TRACKPAD)!!.elements.single()
        val rebound = stock.withPreset(stock.preset(InputStockLayouts.TRACKPAD).let { it.copy(clicks = it.clicks + (pad.id to InputAction.MouseButton(273))) })
        assertTrue(rebound.compileActive(true).showsMouseButton(InputRole.SECONDARY, listOf(pad), rebound.compileStock(true)))
    }
}
