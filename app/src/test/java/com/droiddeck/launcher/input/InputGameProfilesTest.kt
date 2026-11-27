package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class InputGameProfilesTest {
    private val pad = SavedInputLayout("pad", "Gamepad", listOf(InputSurfaceElement(InputControl.A, .8f, .6f, .1f, .1f)), screen = InputLayoutScreen.MAIN)
    private val panel = SavedInputLayout("panel", "Panel", template = InputLayoutTemplate.DECK, screen = InputLayoutScreen.SECONDARY)
    private val third = SavedInputLayout("third", "Spare")
    private fun bindings(id: String, physical: Boolean = false, layout: String? = null) = SavedInputBindings(id, id, physical, layout)
    private val library = InputLibrary(
        profiles = listOf(InputProfile.GAME, InputProfile.DESKTOP).map { id ->
            SavedInputProfile(id, id, id, mapOf(InputRole.PHYSICAL to InputPart("$id-physical"), InputRole.PRIMARY to InputPart("$id-primary", "pad"),
                InputRole.SECONDARY to InputPart("$id-secondary", "panel")))
        },
        layouts = listOf(pad, panel, third),
        bindings = listOf(InputProfile.GAME, InputProfile.DESKTOP).flatMap { id ->
            listOf(bindings("$id-physical", physical = true), bindings("$id-primary", layout = "pad"), bindings("$id-secondary"))
        },
    ).validate()

    @Test fun aGameWithoutItsOwnControlsUsesTheSharedOnes() {
        assertNull(library.gameProfile(false, "1091500"))
        assertEquals(library.activeId(false), library.activeId(false, "1091500"))
        assertEquals("pad", library.activeLayout(false, InputRole.PRIMARY, "1091500")?.id)
    }

    @Test fun turningItOnGivesTheGameAnIndependentCopy() {
        val result = library.withGameProfile("1091500", "Cyberpunk 2077").validate()
        val id = result.activeId(false, "1091500")
        assertEquals(gameProfileId("1091500"), id)
        assertEquals("Cyberpunk 2077", result.profile(id).name)
        val layout = result.activeLayout(false, InputRole.PRIMARY, "1091500")!!
        assertNotEquals("pad", layout.id)
        assertEquals(pad.elements, layout.elements)
        assertNotEquals(result.activePart(false, InputRole.PRIMARY).bindings, result.activePart(false, InputRole.PRIMARY, "1091500").bindings)
        assertEquals(InputProfile.GAME, result.activeId(false, "other"))
    }

    @Test fun desktopSessionsIgnoreGameControls() {
        val result = library.withGameProfile("1091500", "Game")
        assertEquals(result.activeId(true), result.activeId(true, "1091500"))
    }

    @Test fun turningItOffKeepsTheCopyForNextTime() {
        val on = library.withGameProfile("1091500", "Game")
        val edited = on.withLayout(on.activeLayout(false, InputRole.PRIMARY, "1091500")!!.copy(scale = 1.2f))
        val off = edited.withoutGameProfile("1091500").validate()
        assertEquals(InputProfile.GAME, off.activeId(false, "1091500"))
        val again = off.withGameProfile("1091500", "Game").validate()
        assertEquals(1.2f, again.activeLayout(false, InputRole.PRIMARY, "1091500")!!.scale)
        assertEquals(off.profiles.size, again.profiles.size)
    }

    @Test fun buttonsRemovedForOneGameStayOnTheSharedControls() {
        val on = library.withGameProfile("1091500", "Game")
        val game = on.activeLayout(false, InputRole.PRIMARY, "1091500")!!
        val removed = on.withLayout(game.copy(style = game.style.copy(removed = setOf("legacy:a")))).validate()
        assertEquals(setOf("legacy:a"), removed.activeLayout(false, InputRole.PRIMARY, "1091500")!!.style.removed)
        assertTrue(removed.activeLayout(false, InputRole.PRIMARY)!!.style.removed.isEmpty())
    }

    @Test fun removingTheProfileDropsTheGameLink() {
        val result = library.withGameProfile("1091500", "Game").removeProfile(gameProfileId("1091500")).validate()
        assertTrue(result.games.isEmpty())
    }

    @Test fun gameLinksSurviveSaving() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val saved = InputLibraries.update(context) { it.withGameProfile("1091500", "Game") }
        val read = InputLibraries.read(context)
        assertEquals(mapOf("1091500" to gameProfileId("1091500")), read.games)
        assertEquals(saved.activeLayout(false, InputRole.PRIMARY, "1091500"), read.activeLayout(false, InputRole.PRIMARY, "1091500"))
        assertNotEquals(read.activeLayout(false, InputRole.PRIMARY)?.id, read.activeLayout(false, InputRole.PRIMARY, "1091500")?.id)
    }
}
