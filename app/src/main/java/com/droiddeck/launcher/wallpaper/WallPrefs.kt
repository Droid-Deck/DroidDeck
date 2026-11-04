package com.droiddeck.launcher.wallpaper

import android.content.Context

object WallPrefs {
    val FPS_CHOICES = listOf(15, 30, 60)
    val SPEED_CHOICES = listOf(0.5f, 1f, 2f)
    private const val FILE = "wall_wallpaper"
    private const val KEY_FPS = "fps"
    private const val KEY_SPEED = "speed"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun fps(context: Context): Int = prefs(context).getInt(KEY_FPS, 30).coerceIn(5, 120)

    fun speed(context: Context): Float = prefs(context).getFloat(KEY_SPEED, 1f).coerceIn(0.1f, 4f)

    fun setFps(context: Context, value: Int) = prefs(context).edit().putInt(KEY_FPS, value).apply()

    fun setSpeed(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_SPEED, value).apply()
}
