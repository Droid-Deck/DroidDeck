package com.droiddeck.launcher.core

object GameEnvironment {
    data class Config(
        val shared: Map<String, String?> = emptyMap(),
        val games: Map<String, Map<String, String?>> = emptyMap(),
    ) {
        fun entries(scope: String) = if (scope.isEmpty()) shared else games[scope].orEmpty()

        fun withEntries(scope: String, entries: Map<String, String?>): Config =
            if (scope.isEmpty()) copy(shared = entries.toMap())
            else copy(games = if (entries.isEmpty()) games - scope else games + (scope to entries.toMap()))
    }

    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val unused = setOf(
        "WINENTSYNC", "WINE_FAST_YIELD", "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER",
        "WINE_NEW_MEDIASOURCE", "WRAPPER_MAX_IMAGE_COUNT", "WRAPPER_DMAHEAP_CACHED",
        "ALSA_LATENCY_MS", "ALSA_VOLUME", "ALSA_BASS_BOOST", "ALSA_PERFORMANCE_MODE",
        "DXVK_ASYNC", "DXVK_GPLASYNCCACHE",
    )

    fun validName(name: String) = namePattern.matches(name)
    fun supported(name: String) = name !in unused && !name.startsWith("BOX64_") && !name.startsWith("BOX86_")
    fun validValue(value: String) = '\u0000' !in value && value.length <= 8192
    fun validScope(scope: String) = scope.isEmpty() || (Regex("[1-9][0-9]*").matches(scope) && scope.toLongOrNull()?.let { it in 1..4294967295L } == true)

    fun defaults(preset: String, forceSsbs: Boolean = false): Map<String, String?> = linkedMapOf<String, String?>(
        "MESA_SHADER_CACHE_DISABLE" to "false",
        "VKD3D_FEATURE_LEVEL" to "12_2",
        "VKD3D_SHADER_MODEL" to "6_9",
        "DOTNET_GCRegionRange" to "800000000",
        "DOTNET_EnableWriteXorExecute" to "0",
    ).apply {
        FexPreset.env(preset).forEach { put(it.substringBefore('='), it.substringAfter('=')) }
        if (forceSsbs) put(FORCE_SSBS, "1")
    }

    const val FORCE_SSBS = "DROIDDECK_FORCE_SSBS"

    const val NO_STEAM = "DROIDDECK_NO_STEAM"

    fun gameNoSteam(config: Config, scope: String): Boolean =
        scope.isNotEmpty() && config.games[scope]?.get(NO_STEAM)?.lowercase() in setOf("1", "on", "true", "yes")

    fun withGameNoSteam(config: Config, scope: String, on: Boolean): Config {
        require(scope.isNotEmpty() && validScope(scope))
        return withScopeValue(config, scope, NO_STEAM, if (on) "1" else null)
    }

    fun effective(config: Config, preset: String, scope: String, forceSsbs: Boolean = false): Map<String, String?> =
        defaults(preset, forceSsbs) + config.shared + if (scope.isEmpty()) emptyMap() else config.games[scope].orEmpty()

    fun hasOverride(config: Config, scope: String, name: String): Boolean =
        scope.isNotEmpty() && config.entries(scope).containsKey(name)

    fun withScopeValue(config: Config, scope: String, name: String, value: String?): Config {
        require(scope.isEmpty() || validScope(scope))
        val entries = config.entries(scope).toMutableMap()
        if (value == null) entries.remove(name) else entries[name] = value
        return config.withEntries(scope, entries)
    }

    fun gameFexPreset(config: Config, scope: String): String? {
        require(scope.isNotEmpty() && validScope(scope))
        val entries = config.games[scope].orEmpty()
        if (FexPreset.environmentKeys.none(entries::containsKey)) return null
        return FexPreset.all.firstOrNull { preset ->
            val expected = FexPreset.environment(preset.id)
            FexPreset.environmentKeys.all { key -> entries.containsKey(key) && entries[key] == expected[key] }
        }?.id ?: CUSTOM_FEX_PRESET
    }

    fun withGameFexPreset(config: Config, scope: String, preset: String?): Config {
        require(scope.isNotEmpty() && validScope(scope))
        require(preset == null || FexPreset.all.any { it.id == preset })
        val entries = config.entries(scope).filterKeys { it !in FexPreset.environmentKeys }.toMutableMap()
        if (preset != null) {
            val values = FexPreset.environment(preset)
            FexPreset.environmentKeys.forEach { key -> entries[key] = values[key] }
        }
        return config.withEntries(scope, entries)
    }

    const val CUSTOM_FEX_PRESET = "__custom__"
}
