package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectedGameProfileTest {
    private val proton = ComponentsManager.Proton("p1", "Proton 10", File("p1"), "/p1", "10", true)
    private val snapshot = ComponentsManager.Snapshot(
        protons = listOf(
            ComponentsManager.ProtonView(
                proton = proton,
                components = mapOf(
                    "fex" to component("FEX General"),
                    "dxvk" to component("DXVK General"),
                    "vkd3d" to component("VKD3D General"),
                ),
                originals = emptyList(),
                inUseByGame = false,
            ),
        ),
        packages = listOf(ComponentsManager.Package("dxvk.wcp", "dxvk", "DXVK Game", "", 1)),
    )
    private val game = Library.SteamGame(3180310, "Billies Wheelie", null, "internal")

    @Test fun inheritedValuesUseGeneralProfile() {
        val profile = resolve()

        assertEquals("Billies Wheelie", profile!!.name)
        assertEquals("PERFORMANCE_TSO", profile.fexPreset.value)
        assertTrue(profile.fexPreset.inherited)
        assertEquals("Proton 10", profile.proton.value)
        assertTrue(profile.proton.inherited)
        assertEquals("DXVK General", profile.components.getValue("dxvk").value)
        assertTrue(profile.components.getValue("dxvk").inherited)
        assertEquals(0, profile.environmentOverrides)
    }

    @Test fun explicitValuesOverrideGeneralProfile() {
        val preset = GameEnvironment.withGameFexPreset(GameEnvironment.Config(), game.profileKey, "STABILITY")
        val environment = preset.withEntries(game.profileKey, preset.entries(game.profileKey) + ("PROTON_LOG" to "1"))
        val profile = resolve(
            environment = environment,
            gameChoice = ProtonDefault.GameChoice(true, "p1"),
            gameComponents = mapOf("dxvk" to "dxvk.wcp"),
        )

        assertEquals("STABILITY", profile!!.fexPreset.value)
        assertFalse(profile.fexPreset.inherited)
        assertFalse(profile.proton.inherited)
        assertEquals("DXVK Game", profile.components.getValue("dxvk").value)
        assertFalse(profile.components.getValue("dxvk").inherited)
        assertEquals(1, profile.environmentOverrides)
    }

    @Test fun unknownSelectedAppIsIgnored() {
        assertNull(resolve(selectedAppId = 42))
    }

    private fun resolve(
        selectedAppId: Long = 3180310,
        environment: GameEnvironment.Config = GameEnvironment.Config(),
        gameChoice: ProtonDefault.GameChoice? = null,
        gameComponents: Map<String, String> = emptyMap(),
    ) = SelectedGameProfile.resolve(
        selectedAppId = selectedAppId,
        games = listOf(game),
        generalFexPreset = "PERFORMANCE_TSO",
        environment = environment,
        snapshot = snapshot,
        generalProtonId = "p1",
        gameProtonChoice = gameChoice,
        gameProtonId = "p1",
        gameComponents = gameComponents,
    )

    private fun component(label: String) = ComponentsManager.Component(label, "", label, null, null)
}
