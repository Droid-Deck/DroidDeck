package com.droiddeck.launcher.input

import android.content.Context

object ScreenKeyTiming {
    const val DEFAULT_HOLD_MS = 400
    const val DEFAULT_DOUBLE_MS = 250
    const val DEFAULT_REPEAT_MS = 40
    val HOLD_RANGE = 200..800
    val DOUBLE_RANGE = 100..400
    val REPEAT_RANGE = 20..200

    @Volatile var holdMs: Long = DEFAULT_HOLD_MS.toLong()
        private set

    @Volatile var doubleMs: Long = DEFAULT_DOUBLE_MS.toLong()
        private set

    @Volatile var repeatMs: Long = DEFAULT_REPEAT_MS.toLong()
        private set

    fun hold(context: Context): Int = stored(context, "screenKeyHold", DEFAULT_HOLD_MS, HOLD_RANGE)
    fun double(context: Context): Int = stored(context, "screenKeyDouble", DEFAULT_DOUBLE_MS, DOUBLE_RANGE)
    fun repeat(context: Context): Int = stored(context, "screenKeyRepeat", DEFAULT_REPEAT_MS, REPEAT_RANGE)

    fun load(context: Context) {
        holdMs = hold(context).toLong()
        doubleMs = double(context).toLong()
        repeatMs = repeat(context).toLong()
    }

    fun setHold(context: Context, ms: Int) {
        val value = ms.coerceIn(HOLD_RANGE)
        ControllerPrefs.prefs(context).edit().putInt("screenKeyHold", value).apply()
        holdMs = value.toLong()
    }

    fun setDouble(context: Context, ms: Int) {
        val value = ms.coerceIn(DOUBLE_RANGE)
        ControllerPrefs.prefs(context).edit().putInt("screenKeyDouble", value).apply()
        doubleMs = value.toLong()
    }

    fun setRepeat(context: Context, ms: Int) {
        val value = ms.coerceIn(REPEAT_RANGE)
        ControllerPrefs.prefs(context).edit().putInt("screenKeyRepeat", value).apply()
        repeatMs = value.toLong()
    }

    private fun stored(context: Context, key: String, fallback: Int, range: IntRange) =
        ControllerPrefs.prefs(context).getInt(key, fallback).takeIf { it in range } ?: fallback
}
