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
class InputLibraryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshLibraryHasOnlyTheMainGamepadAndTwoLowerScreenLayouts() {
        val library = InputLibraries.read(context)
        assertEquals(listOf("game", "desktop"), library.profiles.map { it.id })
        assertEquals(setOf(InputLibraries.OVERLAY, InputLibraries.DECK, InputLibraries.TRACKPAD), library.layouts.map { it.id }.toSet())
        assertEquals(6, library.bindings.size)
        assertEquals(InputLibraries.DECK, library.activeLayout(false, InputRole.SECONDARY)?.id)
        assertEquals(InputLibraries.TRACKPAD, library.activeLayout(true, InputRole.SECONDARY)?.id)
        assertEquals(InputAction.Pointer(), library.compileActive(true).action(InputRole.PHYSICAL, InputControl.LS))
        assertEquals(InputAction.Native(), library.compileActive(false).action(InputRole.PHYSICAL, InputControl.LS))

        val desktop = library.activeLayout(true, InputRole.SECONDARY)!!
        val pad = desktop.elements.single { it.kind == InputElementKind.TRACKPAD }
        assertTrue(pad.mouseGestures)
        assertEquals(3, desktop.elements.size)
        assertEquals(InputAction.Pointer(), library.compileActive(true).elementAction(InputRole.SECONDARY, pad.id, pad.control))
        assertEquals(InputAction.MouseButton(272), library.compileActive(true).elementClickAction(InputRole.SECONDARY, pad))
        val game = library.activeLayout(false, InputRole.SECONDARY)!!
        assertEquals(9, game.elements.size)
        assertEquals(setOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD), game.elements.filter { it.kind == InputElementKind.TRACKPAD }.map { it.control }.toSet())
        for (control in listOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)) {
            val element = game.elements.single { it.control == control }
            assertEquals(InputAction.Native(), library.compileActive(false).elementAction(InputRole.SECONDARY, element.id, element.control))
        }
        for ((isDesktop, commands) in listOf(true to listOf(InputCommand.MENU, InputCommand.KEYBOARD),
            false to listOf(InputCommand.STEAM, InputCommand.QUICK_ACCESS, InputCommand.MENU))) {
            val elements = library.activeLayout(isDesktop, InputRole.SECONDARY)!!.elements.filter { it.id.startsWith("command:") }
            assertEquals(commands.map { InputAction.Command(it) }, elements.map { library.compileActive(isDesktop).elementAction(InputRole.SECONDARY, it.id, it.control) })
            assertTrue(elements.all { InputButtonIcons.has(it.icon) && it.icon > 255 })
        }
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

    @Test fun resettingDefaultActionsKeepsTheMenuAndKeyboardCommands() {
        val library = InputLibraries.read(context)
        for (id in listOf("game", "desktop")) {
            val reset = library.resetProfile(id).validate()
            for (element in library.layout(id, InputRole.SECONDARY)!!.elements.filter { it.id.startsWith("command:") }) {
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
        assertEquals(.4f, stick.x + stick.width / 2f, .0001f)
        assertEquals(.5f, stick.y + stick.height / 2f, .0001f)
        assertFalse(elements.any { it.control == InputControl.GUIDE || it.control == InputControl.B })
        assertTrue(elements.all { it == it.bounded() })
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
        assertEquals(InputAction.Command(InputCommand.MENU), output.elementAction(InputRole.SECONDARY, "command:0", InputControl.A))
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
            assertEquals(InputAction.Command(InputCommand.MENU), changed.compile("game").elementAction(role, "command:0", InputControl.A))
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

}
