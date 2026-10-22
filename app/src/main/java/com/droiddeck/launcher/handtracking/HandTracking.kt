package com.droiddeck.launcher.handtracking

import com.droiddeck.launcher.core.AddonPack
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.droiddeck.launcher.input.ControllerPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object HandTracking {
    data class Settings(
        val enabled: Boolean = false,
        val asked: Boolean = false,
        val side: String? = null,
        val permitted: Boolean = false,
        val sides: Set<String> = emptySet(),
        val downloaded: Boolean = false,
    ) {
        val usable: Boolean get() = sides.isNotEmpty()
        val ready: Boolean get() = enabled && permitted && usable && downloaded
    }

    private val state = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = state.asStateFlow()
    private val checks = MutableStateFlow(0)
    val checkRequests: StateFlow<Int> = checks.asStateFlow()
    @Volatile private var cameras: List<TrackingCamera>? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

    fun load(context: Context): Settings {
        val prefs = ControllerPrefs.prefs(context)
        val found = cameras(context)
        val next = Settings(
            enabled = prefs.getBoolean(ENABLED, false),
            asked = prefs.getBoolean(ASKED, false),
            side = prefs.getString(SIDE, null),
            permitted = permitted(context),
            sides = if (supported) TrackingCameras.sides(found) else emptySet(),
            downloaded = TrackingPack.installed(context),
        )
        state.value = next
        if (next.enabled && next.usable && !next.downloaded && TrackingPack.refresh(context) == AddonPack.State.Missing)
            TrackingPack.ensure(context)
        return next
    }

    fun cameras(context: Context): List<TrackingCamera> =
        cameras ?: TrackingCameras.list(context.applicationContext).also { cameras = it }

    fun camera(context: Context): TrackingCamera? = TrackingCameras.pick(cameras(context), state.value.side)

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun refreshPermission(context: Context) {
        state.value = state.value.copy(permitted = permitted(context))
    }

    fun allow(context: Context, side: String?) {
        val edit = ControllerPrefs.prefs(context).edit().putBoolean(ASKED, true).putBoolean(ENABLED, true)
        if (side != null) edit.putString(SIDE, side)
        edit.apply()
        load(context)
    }

    fun decline(context: Context) {
        ControllerPrefs.prefs(context).edit().putBoolean(ASKED, true).putBoolean(ENABLED, false).apply()
        load(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        ControllerPrefs.prefs(context).edit().putBoolean(ASKED, true).putBoolean(ENABLED, on).apply()
        load(context)
    }

    fun setSide(context: Context, side: String) {
        ControllerPrefs.prefs(context).edit().putString(SIDE, side).apply()
        load(context)
    }

    fun shouldAsk(settings: Settings, headset: Boolean): Boolean = headset && !settings.asked && settings.usable

    fun wanted(context: Context): Boolean = supported && load(context).ready

    fun requestCheck() {
        checks.value++
    }

    private const val ENABLED = "handCamera"
    private const val ASKED = "handCameraAsked"
    private const val SIDE = "handCameraSide"
}

internal enum class Advice { STARTING, STAND_UP, UPRIGHT, NOT_SEEN, MOVE_BACK, MOVE_CLOSER, SHOW_HANDS, GOOD }

internal object PlacementAdvice {
    const val MIN_SPAN = 0.12f
    const val MAX_SPAN = 0.26f
    const val MIN_ROOM = 0.05f

    fun of(placement: Placement, body: BodyFrame?): Advice = when {
        placement == Placement.FACE_UP || placement == Placement.FACE_DOWN -> Advice.STAND_UP
        placement == Placement.POINTS_UP || placement == Placement.POINTS_DOWN -> Advice.UPRIGHT
        body == null || !body.seen -> Advice.NOT_SEEN
        body.shoulderSpan > MAX_SPAN -> Advice.MOVE_BACK
        body.shoulderSpan < MIN_SPAN -> Advice.MOVE_CLOSER
        body.edgeRoom < MIN_ROOM -> Advice.MOVE_BACK
        body.hands.any { it == null } -> Advice.SHOW_HANDS
        else -> Advice.GOOD
    }
}
