package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard
import kotlin.math.roundToInt

internal object GlassesRendering {
    enum class Picture { FLAT, DUPLICATED, VIRTUAL_SCREEN, STEREO }

    fun native30HzAvailable(settings: GlassesSettings, state: GlassesState): Boolean =
        state.usable && state.supports30Hz && settings.gameOnGlasses && state.viture?.supportsNative != true

    fun refreshRates(settings: GlassesSettings, state: GlassesState): List<Int> {
        state.viture?.takeIf { it.supportsNative }?.let { device ->
            val current = device.takeIf { it.nativeMode in 0..1 }?.selectedDisplayMode
                ?: return listOf(outputHz(settings, state))
            return device.displayModes.filter {
                it.stereo == current.stereo && it.ultrawide == current.ultrawide && it.height == current.height
            }.map { it.hz }.distinct().sorted()
        }
        return buildList {
            if (native30HzAvailable(settings, state)) add(30)
            if (state.sbs) {
                add(60)
                if (state.sbs90) add(90)
            } else addAll(state.rates2d.filter { it >= 60 }.distinct().sorted().ifEmpty { listOf(60) })
        }
    }

    fun outputHz(settings: GlassesSettings, state: GlassesState): Int {
        state.viture?.takeIf { it.supportsNative }?.let { device ->
            device.takeIf { it.nativeMode in 0..1 }?.selectedDisplayMode?.let { return it.hz }
            return state.hz.takeIf { it.isFinite() && it >= 1f }?.roundToInt() ?: 60
        }
        val requested = if (state.sbs) settings.sbsHz else settings.refreshHz
        val minimum = if (state.sbs) 60 else state.rates2d.minOrNull() ?: 60
        return when {
            requested == 30 -> if (native30HzAvailable(settings, state)) 30 else minimum
            state.sbs && !state.sbs90 -> 60
            !state.sbs && requested !in state.rates2d -> state.rates2d.filter { it <= requested }.maxOrNull() ?: minimum
            else -> requested
        }
    }

    fun mirrorRefreshLimit(settings: GlassesSettings, state: GlassesState, outputActive: Boolean): Int {
        if (!state.usable || outputActive) return 0
        val hz = outputHz(settings, state)
        return if (hz == 30 && kotlin.math.abs(state.hz - 30f) >= 0.5f) 60 else hz
    }

    fun usesGlasses(state: GlassesState, outputActive: Boolean, steamVrRunning: Boolean): Boolean =
        outputActive || (steamVrRunning && state.usable)

    fun picture(settings: GlassesSettings, stereoDisplay: Boolean, steamVrRunning: Boolean): Picture =
        when {
            steamVrRunning -> Picture.STEREO
            settings.picture3d == GlassesSettings.PICTURE_SCREEN -> Picture.VIRTUAL_SCREEN
            settings.picture3d == GlassesSettings.PICTURE_SBS -> Picture.STEREO
            stereoDisplay -> Picture.DUPLICATED
            else -> Picture.FLAT
        }

    fun cardboard(
        base: Cardboard,
        settings: GlassesSettings,
        state: GlassesState,
        outputActive: Boolean,
        steamVrRunning: Boolean,
    ): Cardboard {
        if (!usesGlasses(state, outputActive, steamVrRunning)) {
            return if (steamVrRunning && !base.enabled) base.copy(
                enabled = true,
                source = Cardboard.SOURCE_SBS,
                viewer = Cardboard.Viewer.NONE.id,
                mono = true,
            ) else base
        }
        val picture = picture(settings, state.sbs, steamVrRunning)
        if (picture == Picture.FLAT) return Cardboard.OFF
        return Cardboard(
            enabled = true,
            source = when (picture) {
                Picture.STEREO -> Cardboard.SOURCE_SBS
                Picture.DUPLICATED -> Cardboard.SOURCE_FLAT
                else -> Cardboard.SOURCE_SCREEN
            },
            viewer = Cardboard.Viewer.VITURE.id,
            ipdMm = base.ipdMm,
            screenWidthDm = settings.screenWidthDm,
            screenDistanceDm = settings.screenDistanceDm,
            headTracking = settings.headTracking && state.canControl &&
                (picture == Picture.VIRTUAL_SCREEN || steamVrRunning),
            mono = !state.sbs,
        )
    }
}
