package com.droiddeck.launcher.core

import com.droiddeck.launcher.gpu.GpuInfo

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

    fun defaults(preset: String, forceSsbs: Boolean = false, gpuModel: Int = GpuInfo.detect().model): Map<String, String?> = linkedMapOf<String, String?>(
        "MESA_SHADER_CACHE_DISABLE" to "false",
        "VKD3D_FEATURE_LEVEL" to "12_2",
        "VKD3D_SHADER_MODEL" to "6_9",
        // .NET 7 and later reserve 256 GB of address space for the GC heap at startup, more than an
        // Android process has free, and the runtime fails to start. 32 GB is plenty for a game.
        "DOTNET_GCRegionRange" to "0x800000000",
        // .NET's W^X writes JIT code through a second mapping that FEX does not watch, so FEX keeps
        // running the stale translation and the game hangs at startup.
        "DOTNET_EnableWriteXorExecute" to "0",
    ).apply {
        FexPreset.env(preset).forEach { put(it.substringBefore('='), it.substringAfter('=')) }
        // Steam settings' Force SSBS: droiddeck-game-env preloads libssbs.so into the game's Wine.
        if (forceSsbs) put(FORCE_SSBS, "1")
        if (gpuModel == 741) put("TU_DEBUG", "nolrz,nolrzfc")
    }

    const val FORCE_SSBS = "DROIDDECK_FORCE_SSBS"

    fun effective(
        config: Config,
        preset: String,
        scope: String,
        forceSsbs: Boolean = false,
        gpuModel: Int = GpuInfo.detect().model,
    ): Map<String, String?> =
        defaults(preset, forceSsbs, gpuModel) + config.shared + if (scope.isEmpty()) emptyMap() else config.games[scope].orEmpty()
}
