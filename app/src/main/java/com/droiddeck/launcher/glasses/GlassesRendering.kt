package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard

/** Share the actual picture mode with settings so controls match the rendered view. */
internal object GlassesRendering {
    enum class Picture { FLAT, VIRTUAL_SCREEN, STEREO }

    fun outputHz(settings: GlassesSettings, state: GlassesState): Int =
        if (state.sbs) if (state.sbs90) settings.sbsHz else 60 else settings.refreshHz

    fun mirrorRefreshLimit(settings: GlassesSettings, state: GlassesState, outputActive: Boolean): Int =
        if (state.usable && !outputActive) outputHz(settings, state) else 0

    fun fpsLimit(requested: Int, mirrorRefreshLimit: Int): Int = when {
        mirrorRefreshLimit <= 0 -> requested
        requested <= 0 -> mirrorRefreshLimit
        else -> minOf(requested, mirrorRefreshLimit)
    }

    fun usesGlasses(state: GlassesState, outputActive: Boolean, steamVrRunning: Boolean): Boolean =
        outputActive || (steamVrRunning && state.usable)

    fun picture(settings: GlassesSettings, stereoDisplay: Boolean, steamVrRunning: Boolean): Picture =
        when (settings.picture3d) {
            GlassesSettings.PICTURE_SCREEN -> Picture.VIRTUAL_SCREEN
            GlassesSettings.PICTURE_SBS -> Picture.STEREO
            else -> when {
                steamVrRunning -> Picture.STEREO
                stereoDisplay -> Picture.VIRTUAL_SCREEN
                else -> Picture.FLAT
            }
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
            source = if (picture == Picture.STEREO) Cardboard.SOURCE_SBS else Cardboard.SOURCE_SCREEN,
            viewer = Cardboard.Viewer.VITURE.id,
            ipdMm = base.ipdMm,
            screenWidthDm = settings.screenWidthDm,
            screenDistanceDm = settings.screenDistanceDm,
            headTracking = settings.headTracking && state.canControl,
            mono = !state.sbs,
        )
    }
}
