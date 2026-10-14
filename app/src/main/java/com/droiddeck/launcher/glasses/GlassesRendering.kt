package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard

/** Share the actual picture mode with settings so controls match the rendered view. */
internal object GlassesRendering {
    enum class Picture { FLAT, VIRTUAL_SCREEN, STEREO }

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
        if (!outputActive) return base
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
