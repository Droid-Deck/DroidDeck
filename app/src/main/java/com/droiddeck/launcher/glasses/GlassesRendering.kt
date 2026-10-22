package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard

/** Share the actual picture mode with settings so controls match the rendered view. */
internal object GlassesRendering {
    enum class Picture { FLAT, DUPLICATED, VIRTUAL_SCREEN, STEREO }

    fun native30HzAvailable(settings: GlassesSettings, state: GlassesState): Boolean =
        state.usable && state.supports30Hz && settings.gameOnGlasses

    fun refreshRates(settings: GlassesSettings, state: GlassesState): List<Int> = buildList {
        if (native30HzAvailable(settings, state)) add(30)
        add(60)
        if (state.sbs) { if (state.sbs90) add(90) }
        else addAll(state.rates2d.filter { it > 60 })
    }

    fun outputHz(settings: GlassesSettings, state: GlassesState): Int {
        val requested = if (state.sbs) settings.sbsHz else settings.refreshHz
        return when {
            requested == 30 -> if (native30HzAvailable(settings, state)) 30 else 60
            state.sbs && !state.sbs90 -> 60
            !state.sbs && requested !in state.rates2d -> state.rates2d.filter { it <= requested }.maxOrNull() ?: 60
            else -> requested
        }
    }

    fun mirrorRefreshLimit(settings: GlassesSettings, state: GlassesState, outputActive: Boolean): Int {
        if (!state.usable || outputActive) return 0
        val hz = outputHz(settings, state)
        return if (hz == 30 && kotlin.math.abs(state.hz - 30f) >= 0.5f) 60 else hz
    }

    fun fpsLimit(requested: Int, mirrorRefreshLimit: Int): Int = when {
        mirrorRefreshLimit <= 0 -> requested
        requested <= 0 -> mirrorRefreshLimit
        else -> minOf(requested, mirrorRefreshLimit)
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
        // Mirror output still uses the glasses' picture and sensors during VR.
        if (!usesGlasses(state, outputActive, steamVrRunning)) {
            // SteamVR uses the renderer's enabled flag as headset proximity.
            // A flat phone preview still needs an active HMD and one SBS eye;
            // otherwise SteamVR enters standby and leaves a frozen frame.
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
