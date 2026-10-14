package com.droiddeck.launcher.core

import android.content.Context

object AppUiPrefs {
    const val DEFAULT_SCALE = 100
    val scales = listOf(75, 85, 100, 115, 125, 150)

    internal fun normalizeScale(percent: Int): Int = percent.takeIf { it in scales } ?: DEFAULT_SCALE

    fun scale(context: Context): Int = normalizeScale(
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).getInt("scale", DEFAULT_SCALE),
    )

    fun setScale(context: Context, percent: Int) {
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit()
            .putInt("scale", normalizeScale(percent)).apply()
    }
}
