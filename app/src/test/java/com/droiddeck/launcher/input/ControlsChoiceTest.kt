package com.droiddeck.launcher.input

import android.content.Context
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [JoyConPadTest.RecordingWriter::class])
class ControlsChoiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("session", "game-controls", "input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun mode() { SessionState.mode = SessionService.MODE_STEAM }

    private fun bigSticks(): String {
        val id = "big-sticks"
        InputLibraries.update(context) { library ->
            val stick = InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, "jump", InputElementKind.BUTTON, "Jump")
            library.withLayout(SavedInputLayout(id, "Big sticks", listOf(stick), screen = InputLayoutScreen.MAIN))
                .withPreset(SavedInputBindings("big-sticks.actions", "Big sticks", false, id, elements = mapOf("jump" to InputAction.Keys(listOf(57)))))
        }
        return id
    }

    @Test fun aNewLayoutForOneGameCopiesTheLayoutAndItsActionsForThatGameOnly() {
        val source = bigSticks()
        ControlsChoice.choose(context, null, source, desktop = false)
        val (layoutId, bindingsId) = ControlsChoice.newForGame(context, 7, "Castle Crashers", desktop = false)
        val library = InputLibraries.read(context)
        val copy = library.layouts.first { it.id == layoutId }
        assertEquals("Castle Crashers", copy.name)
        assertEquals(library.layouts.first { it.id == source }.elements, copy.elements)
        assertEquals(InputAction.Keys(listOf(57)), library.preset(bindingsId).elements["jump"])
        assertEquals(layoutId, library.preset(bindingsId).layoutId)
        assertEquals(bindingsId, GameControls.bindings(context, 7))
        assertEquals(layoutId, ControlsChoice.current(context, 7, steamMode = true))
        assertEquals(source, ControlsChoice.current(context, 8, steamMode = true))
        assertEquals(source, ControlsChoice.current(context, null, steamMode = true))
        assertEquals(Triple(InputProfile.GAME, layoutId, bindingsId), ControlsChoice.editTarget(context, 7, desktop = false))
    }

    @Test fun aCopyOfTheBuiltInGamepadKeepsFollowingTheKit() {
        val extra = InputSurfaceElement(InputControl.A, .45f, .1f, .08f, .1f, "extra", InputElementKind.BUTTON, "Extra")
        InputLibraries.update(context) { it.withLayout(it.layouts.first { l -> l.id == InputLibraries.OVERLAY }.copy(elements = listOf(extra), aspect = 2f)) }
        val (layoutId, _) = ControlsChoice.newForGame(context, 7, "Castle Crashers", desktop = false)
        val copy = InputLibraries.read(context).layouts.first { it.id == layoutId }
        assertEquals(InputLibraries.OVERLAY, copy.origin)
        assertTrue(OnScreenLayout.generated(copy))
        assertEquals(listOf(extra), copy.elements)
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(context, null, steamMode = false))
    }

    @Test fun onlyAGameWithItsOwnUnsharedLayoutEditsWithoutChangingOthers() {
        assertFalse(ControlsChoice.shared(context, null))
        assertTrue(ControlsChoice.shared(context, 7))
        ControlsChoice.choose(context, 7, InputLibraries.OVERLAY, desktop = false)
        assertTrue(ControlsChoice.shared(context, 7))
        ControlsChoice.newForGame(context, 7, "Castle Crashers", desktop = false)
        assertFalse(ControlsChoice.shared(context, 7))
        ControlsChoice.choose(context, 8, ControlsChoice.current(context, 7, steamMode = false), desktop = false)
        assertTrue(ControlsChoice.shared(context, 7))
    }

    @Test fun useDefaultForgetsTheGamesChoice() {
        ControlsChoice.newForGame(context, 7, "Castle Crashers", desktop = false)
        ControlsChoice.choose(context, 9, GameControls.STEAM, desktop = false, gameName = "Portal")
        assertEquals(listOf("Castle Crashers" to "Castle Crashers", "Portal" to context.getString(R.string.osc_layout_steam)),
            ControlsChoice.games(context).map { it.name to it.layout })
        ControlsChoice.useDefault(context, 7)
        assertNull(GameControls.bindings(context, 7))
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(context, 7, steamMode = true))
        assertEquals(listOf(9L), ControlsChoice.games(context).map { it.id })
    }

    @Test fun newIsOfferedOnlyForAGame() {
        assertTrue(ControlsChoice.options(context, null, steamMode = true).none { it.id == ControlsChoice.NEW })
        val last = ControlsChoice.options(context, 7, steamMode = true, gameName = "Castle Crashers").last()
        assertEquals(ControlsChoice.NEW to context.getString(R.string.osc_layout_new_for, "Castle Crashers"), last.id to last.name)
        assertEquals(listOf(InputLibraries.OVERLAY, GameControls.STEAM), ControlsChoice.options(context, null, steamMode = true).map { it.id })
    }

    @Test fun newForTheGameInTheControllerPageCopiesTheLayoutAndOpensTheEditorOnIt() {
        SessionState.mode = SessionService.MODE_STEAM
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.getField<MutableState<Long?>>(activity, "frameGenActiveGameId\$delegate").value = 77L
        ReflectionHelpers.getField<MutableState<String?>>(activity, "frameGenActiveGameName\$delegate").value = "Castle Crashers"
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "refreshInputProfile")
        val options = ReflectionHelpers.getField<MutableState<List<ControlsChoice.Option>>>(activity, "layoutOptions\$delegate").value
        assertEquals(ControlsChoice.NEW to "+ New for Castle Crashers", options.last().let { it.id to it.name })
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "chooseLayout", ReflectionHelpers.ClassParameter.from(String::class.java, ControlsChoice.NEW))
        val editor = ReflectionHelpers.getField<MutableState<Triple<String, String, String>?>>(activity, "layoutEditor\$delegate").value!!
        assertEquals(GameControls.bindings(activity, 77), editor.third)
        assertEquals("Castle Crashers", InputLibraries.read(activity).layouts.first { it.id == editor.second }.name)
        assertEquals(editor.second, ReflectionHelpers.getField<MutableState<String>>(activity, "layoutChoice\$delegate").value)
        assertEquals("Saved for Castle Crashers", ReflectionHelpers.getField<String>(activity, "layoutEditorScope"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "layoutEditorSplit"))
        assertNull(GameControls.bindings(activity, 78))
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(activity, null, steamMode = true))
    }

    @Test fun aGameWithItsOwnLayoutCanGoBackToTheDefault() {
        assertTrue(ControlsChoice.options(context, 42L, steamMode = true, gameName = "Game").none { it.id == ControlsChoice.DEFAULT })
        ControlsChoice.newForGame(context, 42L, "Game", desktop = false)
        assertTrue(ControlsChoice.options(context, 42L, steamMode = true, gameName = "Game").first().id == ControlsChoice.DEFAULT)
        ControlsChoice.choose(context, 42L, ControlsChoice.DEFAULT, desktop = false)
        assertFalse(ControlsChoice.hasOwn(context, 42L))
        assertTrue(ControlsChoice.options(context, 42L, steamMode = true, gameName = "Game").none { it.id == ControlsChoice.DEFAULT })
    }

    @Test fun aLayoutMadeForOneGameIsListedOnlyForThatGame() {
        val (layoutId, _) = ControlsChoice.newForGame(context, 7, "Castle Crashers", desktop = false)
        val mine = ControlsChoice.options(context, 7, steamMode = true, gameName = "Castle Crashers").first { it.id == layoutId }
        assertEquals(context.getString(R.string.osc_layout_detail_game, "Castle Crashers"), mine.detail)
        assertFalse(mine.deletable)
        assertTrue(ControlsChoice.options(context, 8, steamMode = true, gameName = "Portal").none { it.id == layoutId })
        assertTrue(ControlsChoice.options(context, null, steamMode = true).none { it.id == layoutId })
        ControlsChoice.useDefault(context, 7)
        assertTrue(ControlsChoice.options(context, 8, steamMode = true, gameName = "Portal").none { it.id == layoutId })
        assertTrue(ControlsChoice.options(context, 7, steamMode = true, gameName = "Castle Crashers").first { it.id == layoutId }.deletable)
    }

    @Test fun anUnusedLayoutCanBeDeletedButNotOneInUse() {
        val source = bigSticks()
        assertTrue(ControlsChoice.options(context, null, steamMode = true).first { it.id == source }.deletable)
        ControlsChoice.choose(context, null, source, desktop = false)
        assertFalse(ControlsChoice.options(context, null, steamMode = true).first { it.id == source }.deletable)
        ControlsChoice.choose(context, null, InputLibraries.OVERLAY, desktop = false)
        ControlsChoice.choose(context, 7, source, desktop = false, gameName = "Castle Crashers")
        assertFalse(ControlsChoice.options(context, null, steamMode = true).first { it.id == source }.deletable)
        assertNull(ControlsChoice.options(context, 8, steamMode = true, gameName = "Portal").first { it.id == source }.detail)
        ControlsChoice.useDefault(context, 7)
        ControlsChoice.delete(context, source)
        val library = InputLibraries.read(context)
        assertTrue(library.layouts.none { it.id == source })
        assertTrue(library.bindings.none { it.layoutId == source })
        assertTrue(ControlsChoice.options(context, null, steamMode = true).none { it.id == source })
    }

    @Test fun deletingALayoutAlsoRemovesAProfileThatCanNoLongerBeChosen() {
        InputLibraries.update(context) { it.duplicateProfile(InputProfile.GAME, "Game2") }
        val profile = InputLibraries.read(context).profiles.first { it.name == "Game2" }
        val layout = InputLibraries.read(context).layout(profile.id, InputRole.PRIMARY)!!
        assertEquals("Game2 / primary", layout.name)
        assertTrue(ControlsChoice.options(context, null, steamMode = true).first { it.id == layout.id }.deletable)
        ControlsChoice.delete(context, layout.id)
        val library = InputLibraries.read(context)
        assertTrue(library.profiles.none { it.id == profile.id })
        assertTrue(library.layouts.none { it.id == layout.id })
        assertTrue(profile.parts.values.none { part -> library.bindings.any { it.id == part.bindings } })
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(context, null, steamMode = true))
    }

    @Test fun steamTouchSavedForAGameFallsBackOutsideSteamWithANote() {
        ControlsChoice.choose(context, 7, GameControls.STEAM, desktop = false, gameName = "Portal")
        assertEquals(GameControls.STEAM, ControlsChoice.current(context, 7, steamMode = true))
        assertFalse(ControlsChoice.steamUnavailable(context, 7, steamMode = true))
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(context, 7, steamMode = false))
        assertTrue(ControlsChoice.steamUnavailable(context, 7, steamMode = false))
        assertFalse(ControlsChoice.steamUnavailable(context, 8, steamMode = false))
        assertTrue(ControlsChoice.options(context, 7, steamMode = false, gameName = "Portal").none { it.id == GameControls.STEAM })
    }

    @Test fun aLayoutNamedAfterTheGameThatDrawsItCountsAsThatGamesOwn() {
        val source = bigSticks()
        InputLibraries.update(context) { it.withLayout(it.layouts.first { l -> l.id == source }.copy(name = "ARMSX1")) }
        ControlsChoice.choose(context, 7, source, desktop = false, gameName = "ARMSX1")
        assertTrue(ControlsChoice.options(context, 8, steamMode = true, gameName = "Portal").none { it.id == source })
        assertEquals(context.getString(R.string.osc_layout_detail_game, "ARMSX1"),
            ControlsChoice.options(context, 7, steamMode = true, gameName = "ARMSX1").first { it.id == source }.detail)
    }

    @Test fun aLayoutTheGameProfileStillListsCanBeDeletedOnceAnotherIsChosen() {
        val source = bigSticks()
        InputLibraries.update(context) { it.chooseLayout(InputProfile.GAME, InputRole.PRIMARY, source) }
        assertEquals(source, ControlsChoice.current(context, null, steamMode = true))
        assertFalse(ControlsChoice.options(context, null, steamMode = true).first { it.id == source }.deletable)
        ControlsChoice.choose(context, null, InputLibraries.OVERLAY, desktop = false)
        assertTrue(ControlsChoice.options(context, null, steamMode = true).first { it.id == source }.deletable)
        ControlsChoice.delete(context, source)
        val library = InputLibraries.read(context)
        assertTrue(library.layouts.none { it.id == source })
        assertEquals(InputLibraries.OVERLAY, library.profile(InputProfile.GAME).parts.getValue(InputRole.PRIMARY).layout)
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(context, null, steamMode = true))
    }

    private fun desktopMouseFirst(): String {
        val mouse = SavedInputBindings("desktop.mouse", "Desktop / primary", false, InputLibraries.OVERLAY,
            controls = mapOf(InputControl.A to InputAction.MouseButton(272), InputControl.LS to InputAction.Pointer()))
        InputLibraries.update(context) { it.copy(bindings = listOf(mouse) + it.bindings).chooseBindings(InputProfile.DESKTOP, InputRole.PRIMARY, mouse.id) }
        return mouse.id
    }

    @Test fun choosingTheGamepadKeepsEachModesOwnActions() {
        val mouse = desktopMouseFirst()
        val game = InputLibraries.read(context).profile(InputProfile.GAME).parts.getValue(InputRole.PRIMARY).bindings
        ControlsChoice.choose(context, null, InputLibraries.OVERLAY, desktop = false)
        ControlsChoice.choose(context, null, InputLibraries.OVERLAY, desktop = true)
        val library = InputLibraries.read(context)
        assertNotEquals(mouse, library.activePart(false, InputRole.PRIMARY).bindings)
        assertEquals(game, library.activePart(false, InputRole.PRIMARY).bindings)
        assertEquals(mouse, library.activePart(true, InputRole.PRIMARY).bindings)
        ControlsChoice.choose(context, 7, InputLibraries.OVERLAY, desktop = false)
        assertNotEquals(mouse, GameControls.bindings(context, 7))
    }

    @Test fun aGameSelectionHoldingDesktopActionsIsDroppedOnUpgrade() {
        val mouse = desktopMouseFirst()
        val broken = InputLibraries.read(context).selectPart(false, InputRole.PRIMARY, mouse)
        val raw = org.json.JSONObject(InputLibraries.encode(broken)).put("version", 15).toString()
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().putString("library", raw).commit()
        val library = InputLibraries.read(context)
        assertNull(library.selection(false).parts[InputRole.PRIMARY])
        assertEquals(mouse, library.activePart(true, InputRole.PRIMARY).bindings)
        assertEquals(InputLibraries.OVERLAY, ControlsChoice.current(context, null, steamMode = true))
    }
}
