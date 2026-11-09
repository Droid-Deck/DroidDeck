package com.droiddeck.launcher.session

internal object TurnipEnvironment {
    const val GAME_TU_DEBUG = "DROIDDECK_GAME_TU_DEBUG"

    fun gameEnvironment(
        tuOverride: String?,
        tuDriver: Boolean,
        tuSysmem: Boolean,
        extra: List<String>,
    ): List<String> = if (extra.none { it.startsWith("TU_DEBUG=") }) {
        listOf("$GAME_TU_DEBUG=${tuOverride ?: if (tuDriver || tuSysmem) "sysmem" else ""}")
    } else {
        emptyList()
    }
}
