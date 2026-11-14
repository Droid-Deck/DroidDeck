package com.droiddeck.launcher.input

import android.content.Context
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
@Config(sdk = [28], qualifiers = "w540dp-h470dp")
class ProfileToolbarMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE)

    @Before fun clear() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun stockButtonsAndPadsKeepTheirPixelBoundsAfterRemovingTheBar() {
        val original = InputStockLayouts.layouts().first { it.id == InputLibraries.DECK }
        val migrated = InputStockLayouts.withToolbar(original, 1240, 1080, 2.30625f, 174)
        val bar = 148
        for (old in original.elements) {
            val current = migrated.elements.single { it.id == old.id }
            assertEquals((old.x * 1240).toInt(), (current.x * 1240).toInt())
            assertEquals(bar + (old.y * (1080 - bar - 1)).toInt(), (current.y * 1080).toInt())
            assertEquals((old.height * (1080 - bar - 1)).toInt(), (current.height * 1080).toInt())
        }
        val qam = migrated.elements.single { it.id == "command:quick_access" }
        assertEquals(1111, (qam.x * 1240).toInt())
        assertEquals(24, (qam.y * 1080).toInt())
        assertEquals(101, (qam.width * 1240).toInt())
        assertEquals(101, (qam.height * 1080).toInt())
        assertEquals(InputAction.Command(InputCommand.SWAP_SCREENS), InputStockLayouts.actions(migrated)["command:swap_screens"])
    }

    @Test fun upgradePreservesEditedLayoutsAndActionsAndRunsOnlyOnce() {
        val fresh = InputLibraries.read(context)
        var old = InputStockLayouts.layouts().fold(fresh) { library, layout -> library.withLayout(layout) }
        val desktop = old.layouts.single { it.id == InputLibraries.TRACKPAD }
        val edited = desktop.copy(elements = desktop.elements.map { it.copy(x = .1f, width = .8f) })
        old = old.withLayout(edited)
        val bindings = old.preset(InputStockLayouts.DECK).copy(elements = mapOf("legacy:l4" to InputAction.Keys(listOf(28))))
        old = old.withPreset(bindings)
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", 14).toString()).commit()
        val migrated = InputLibraries.read(context)
        assertEquals(edited, migrated.layouts.single { it.id == edited.id })
        assertEquals(InputAction.Keys(listOf(28)), migrated.preset(InputStockLayouts.DECK).elements["legacy:l4"])
        assertEquals(InputAction.Command(InputCommand.MENU), migrated.preset(InputStockLayouts.DECK).elements["command:menu"])
        assertEquals(migrated, InputLibraries.read(context))
        assertEquals(migrated, InputLibraries.decode(InputLibraries.encode(migrated)))
    }

    @Test fun rememberedLegacyPanelBecomesOnlyTheSecondaryPartOfTheProfile() {
        val old = InputLibraries.read(context)
        SessionPrefs.setSecondScreenPanel(context, true, SecondScreenMode.DECK_CONTROLS.id)
        SessionPrefs.setDeckPanel(context, DeckPanel.LEFT)
        prefs.edit().putString("library", JSONObject(InputLibraries.encode(old)).put("version", 14).toString()).commit()
        val migrated = InputLibraries.read(context)
        assertEquals(DeckPanel.LEFT.bindings, migrated.activePart(true, InputRole.SECONDARY).bindings)
        assertEquals(old.activePart(true, InputRole.PHYSICAL), migrated.activePart(true, InputRole.PHYSICAL))
        assertEquals(old.activePart(true, InputRole.PRIMARY), migrated.activePart(true, InputRole.PRIMARY))
        assertEquals(old.activePart(false, InputRole.SECONDARY), migrated.activePart(false, InputRole.SECONDARY))
    }

    @Test fun disablingTheDisplayPreservesTheModeAndDoesNotDisableTheOtherSessionType() {
        SessionPrefs.setSecondScreenPanel(context, true, SecondScreenMode.TERMINAL.id)
        SessionPrefs.setSecondScreenEnabled(context, true, false)
        assertFalse(SessionPrefs.secondScreenEnabled(context, true))
        assertTrue(SessionPrefs.secondScreenEnabled(context, false))
        assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(context, true))
        SessionPrefs.setSecondScreenEnabled(context, true, true)
        assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(context, true))
    }
}
