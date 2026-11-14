package com.droiddeck.launcher.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object SoundtrackSettings {
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2f

    var speed by mutableFloatStateOf(1f)
        private set
    var pitch by mutableFloatStateOf(1f)
        private set
    var skipSilence by mutableStateOf(false)
        private set
    var eqOn by mutableStateOf(false)
        private set
    var bands by mutableStateOf<List<Int>>(emptyList())
        private set
    var bass by mutableIntStateOf(0)
        private set

    class Resume(val root: String, val id: String, val positionMs: Long)

    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("soundtrack_settings", Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        speed = p.getFloat("speed", 1f).coerceIn(MIN_RATE, MAX_RATE)
        pitch = p.getFloat("pitch", 1f).coerceIn(MIN_RATE, MAX_RATE)
        skipSilence = p.getBoolean("skipSilence", false)
        eqOn = p.getBoolean("eqOn", false)
        bands = p.getString("bands", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        bass = p.getInt("bass", 0).coerceIn(0, 1000)
    }

    fun setSpeed(context: Context, value: Float) {
        speed = value.coerceIn(MIN_RATE, MAX_RATE)
        prefs(context).edit().putFloat("speed", speed).apply()
    }

    fun setPitch(context: Context, value: Float) {
        pitch = value.coerceIn(MIN_RATE, MAX_RATE)
        prefs(context).edit().putFloat("pitch", pitch).apply()
    }

    fun setSkipSilence(context: Context, on: Boolean) {
        skipSilence = on
        prefs(context).edit().putBoolean("skipSilence", on).apply()
    }

    fun setEqualizer(context: Context, on: Boolean, levels: List<Int>, bassStrength: Int) {
        eqOn = on
        bands = levels
        bass = bassStrength.coerceIn(0, 1000)
        prefs(context).edit().putBoolean("eqOn", on).putString("bands", levels.joinToString(",")).putInt("bass", bass).apply()
    }

    fun resume(context: Context): Resume? {
        val p = prefs(context)
        val root = p.getString("resumeRoot", null) ?: return null
        val id = p.getString("resumeId", null) ?: return null
        return Resume(root, id, p.getLong("resumePosition", 0L))
    }

    fun saveResume(context: Context, root: String, id: String, positionMs: Long) {
        prefs(context).edit().putString("resumeRoot", root).putString("resumeId", id).putLong("resumePosition", positionMs.coerceAtLeast(0L)).apply()
    }
}
