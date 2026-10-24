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
class InputProfilesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun migrationKeepsLegacyTouchMappingSeparateFromPhysicalControls() {
        ControllerPrefs.setTarget(context, "a", "b")
        ControllerPrefs.setTarget(context, "x", "off")
        ControllerPrefs.setSelectSteam(context, true)
        val game = InputProfiles.read(context, InputProfile.GAME)
        assertEquals(InputAction.Native(InputControl.B), game.action(InputRole.PRIMARY, InputControl.A))
        assertEquals(InputAction.Disabled, game.action(InputRole.PRIMARY, InputControl.X))
        assertEquals(InputAction.Native(), game.action(InputRole.PHYSICAL, InputControl.A))
        assertEquals(InputAction.Native(InputControl.GUIDE), game.action(InputRole.PHYSICAL, InputControl.SELECT))
        InputProfiles.reset(context, InputProfile.GAME)
        assertEquals(InputAction.Native(), InputProfiles.read(context, InputProfile.GAME).action(InputRole.PRIMARY, InputControl.A))
    }

    @Test fun bindingsAndDeviceKeysRoundTripWithoutAffectingOtherProfile() {
        val game = InputProfile.defaults(InputProfile.GAME)
            .withBinding(InputRole.SECONDARY, InputControl.A, InputAction.Keys(listOf(29, 30)))
            .copy(keys = listOf(PhysicalInputBinding(PhysicalInputKey("1:2:descriptor", "Thor", 131, 59), InputAction.Command(InputCommand.MENU))))
        InputProfiles.save(context, game)
        assertEquals(game, InputProfiles.read(context, InputProfile.GAME))
        assertEquals(InputAction.Pointer(), InputProfiles.read(context, InputProfile.DESKTOP).action(InputRole.PHYSICAL, InputControl.LS))
    }

    @Test fun profileSelectionIsExplicitAndNeverDependsOnDisplayPresence() {
        assertEquals(InputProfile.DESKTOP, InputProfiles.active(context, true).id)
        assertEquals(InputProfile.GAME, InputProfiles.active(context, false).id)
        InputProfiles.select(context, InputProfile.GAME)
        assertEquals(InputProfile.GAME, InputProfiles.active(context, true).id)
    }

    @Test fun unknownVersionDoesNotExecuteBindings() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit()
            .putString("profile.game", """{"version":999,"bindings":[]} """).commit()
        assertEquals(InputProfile.defaults(InputProfile.GAME), InputProfiles.read(context, InputProfile.GAME))
    }

    @Test fun layoutsPersistIndependentOfBindingsAndStayInsideScreen() {
        InputSurfaceLayout.save(context, InputProfile.GAME, listOf(InputSurfaceElement(InputControl.A, .95f, -.1f, 2f, 2f)))
        val element = InputSurfaceLayout.read(context, InputProfile.GAME).single()
        assertEquals(.9f, element.x); assertEquals(0f, element.y)
        assertTrue(element.x + element.width <= 1f)
        assertEquals(InputAction.Native(), InputProfiles.read(context, InputProfile.GAME).action(InputRole.SECONDARY, InputControl.A))
    }
}
