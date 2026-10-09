package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.Library

data class ProfileValue(val value: String, val inherited: Boolean)

data class SelectedGameProfile(
    val appId: Long,
    val name: String,
    val fexPreset: ProfileValue,
    val proton: ProfileValue,
    val protonId: String?,
    val components: Map<String, ProfileValue>,
    val componentFiles: Map<String, String>,
    val environmentOverrides: Int,
    val windowsComponentNames: List<String>,
) {
    val hasOverrides: Boolean
        get() = !fexPreset.inherited ||
            !proton.inherited ||
            components.values.any { !it.inherited } ||
            environmentOverrides > 0 ||
            windowsComponentNames.isNotEmpty()

    companion object {
        fun resolve(
            selectedAppId: Long,
            games: List<Library.SteamGame>,
            generalFexPreset: String,
            environment: GameEnvironment.Config,
            snapshot: ComponentsManager.Snapshot,
            generalProtonId: String?,
            gameProtonChoice: ProtonDefault.GameChoice?,
            gameProtonId: String?,
            gameComponents: Map<String, String>,
            gameWindowsComponents: List<String>,
        ): SelectedGameProfile? {
            val game = games.firstOrNull { it.profileKey == selectedAppId.toString() } ?: return null
            val fexOverride = GameEnvironment.gameFexPreset(environment, game.profileKey)
            val selectedProtonId = if (gameProtonChoice == null) generalProtonId else gameProtonId
            val selectedProton = snapshot.protons.firstOrNull { it.proton.id == selectedProtonId }
            val packages = snapshot.packages.associateBy { it.file }
            val components = ComponentsManager.COMPONENTS.associateWith { component ->
                val override = gameComponents[component]
                ProfileValue(
                    value = override?.let { packages[it]?.version ?: it }
                        ?: selectedProton?.components?.get(component)?.selected.orEmpty(),
                    inherited = override == null,
                )
            }
            val otherEnvironment = environment.games[game.profileKey].orEmpty().keys - FexPreset.environmentKeys
            return SelectedGameProfile(
                appId = selectedAppId,
                name = game.name,
                fexPreset = ProfileValue(fexOverride ?: generalFexPreset, fexOverride == null),
                proton = ProfileValue(
                    selectedProton?.proton?.name ?: gameProtonChoice?.dir.orEmpty(),
                    gameProtonChoice == null,
                ),
                protonId = selectedProtonId,
                components = components,
                componentFiles = gameComponents,
                environmentOverrides = otherEnvironment.size,
                windowsComponentNames = gameWindowsComponents.map(WinComponentNames::of),
            )
        }
    }
}
