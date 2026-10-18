package com.droiddeck.launcher.glasses

data class GlassesResolution(val width: Int, val height: Int) {
    val key: String get() = "${width}x$height"
}

enum class GlassesDisplayProfile { TWO_D, THREE_D, VR }

internal object GlassesDisplayModes {
    fun profile(stereo: Boolean, vr: Boolean): GlassesDisplayProfile = when {
        vr -> GlassesDisplayProfile.VR
        stereo -> GlassesDisplayProfile.THREE_D
        else -> GlassesDisplayProfile.TWO_D
    }

    fun choice(settings: GlassesSettings, profile: GlassesDisplayProfile): String = when (profile) {
        GlassesDisplayProfile.TWO_D -> settings.resolution2d
        GlassesDisplayProfile.THREE_D -> settings.resolution3d
        GlassesDisplayProfile.VR -> settings.resolutionVr
    }

    fun selected(settings: GlassesSettings, state: GlassesState,
        stereo: Boolean = state.sbs, vr: Boolean = state.vrActive): GlassesResolution? {
        val selected = choice(settings, profile(stereo, vr)).ifEmpty {
            if (vr) choice(settings, profile(stereo, false)) else ""
        }
        return state.resolutions.firstOrNull { it.key == selected }
    }
}
