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
@Config(sdk = [28])
class GameInputControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-game-controls"))
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun library(): InputLibrary {
        val result = InputLibraries.update(context) { it.duplicateProfile(InputProfile.GAME, "My game", "my-game") }
        GameInputControls.set(context, "42", "my-game")
        return result
    }
    @Test fun gameOverridesOnlyScreenPartsAndRestoresGeneralControlsOnExit() {
        val original = library()
        val game = GameInputControls.resolve(context, original, false, "42")
        for (role in listOf(InputRole.PRIMARY, InputRole.SECONDARY))
            assertEquals(original.profile("my-game").binding(role), game.activePart(false, role).bindings)
        assertEquals(original.activePart(false, InputRole.PHYSICAL), game.activePart(false, InputRole.PHYSICAL))
        assertEquals(original, GameInputControls.resolve(context, original, false, null))
        assertEquals(original, GameInputControls.resolve(context, original, false, "43"))
        assertEquals(original, InputLibraries.read(context))
    }
    @Test fun manualProfileAndScreenChoicesTakePriority() {
        val original = library()
        val manual = original.selectProfile(false, InputProfile.DESKTOP)
        assertEquals(manual, GameInputControls.resolve(context, manual, false, "42"))
        val role = InputRole.PRIMARY
        val chosen = original.profile(InputProfile.DESKTOP).binding(role)
        val mixed = original.selectPart(false, role, chosen)
        val effective = GameInputControls.resolve(context, mixed, false, "42")
        assertEquals(chosen, effective.activePart(false, role).bindings)
        assertEquals(original.profile("my-game").binding(InputRole.SECONDARY), effective.activePart(false, InputRole.SECONDARY).bindings)
    }
    @Test fun gameResetClearsOnlyTheSelectedControlsMapping() {
        library()
        GameInputControls.set(context, "43", InputProfile.DESKTOP)
        com.droiddeck.launcher.session.GameProfileManager.reset(context, "42")
        assertNull(GameInputControls.profile(context, "42"))
        assertEquals(InputProfile.DESKTOP, GameInputControls.profile(context, "43"))
    }
    @Test fun deletedProfileAndClearedOverridesFallBackToGeneral() {
        val original = library()
        val removed = original.removeProfile("my-game")
        assertEquals(removed, GameInputControls.resolve(context, removed, false, "42"))
        GameInputControls.set(context, "42", null)
        assertNull(GameInputControls.profile(context, "42"))
        assertEquals(original, GameInputControls.resolve(context, original, false, "42"))
    }
}
