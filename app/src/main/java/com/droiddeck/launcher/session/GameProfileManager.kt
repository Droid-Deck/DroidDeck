package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment

object GameProfileManager {
    @Synchronized
    fun reset(context: Context, scope: String) {
        require(GameEnvironment.validScope(scope))
        val failures = mutableListOf<Pair<String, Throwable>>()
        fun clear(name: String, action: () -> Unit) {
            runCatching(action).onFailure { failures += name to it }
        }

        clear("environment") {
            GameEnvironmentStore.save(
                context,
                GameEnvironmentStore.read(context).withEntries(scope, emptyMap()),
            )
        }
        clear("Proton") { ProtonDefault.requestGame(context, scope, null) }
        clear("component packages") { ComponentsManager.clearGameComponents(context, scope) }
        clear("texture filtering") { SessionPrefs.clearGameTextureFiltering(context, scope) }
        clear("Windows components") { WinComponents.setPicks(context, scope, emptyList()) }

        if (failures.isNotEmpty()) {
            val names = failures.joinToString { it.first }
            throw IllegalStateException("Could not clear: $names", failures.first().second)
        }
    }
}
