package com.droiddeck.launcher.core

import android.content.Context
import android.content.pm.ActivityInfo
import com.droiddeck.launcher.R

object AppUiPrefs {
    const val DEFAULT_SCALE = 100
    val scales = listOf(75, 85, 100, 115, 125, 150)

    val orientationOptions = listOf(
        ActivityInfo.SCREEN_ORIENTATION_USER to R.string.setup_launcher_orientation_auto,
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to R.string.setup_orientation_portrait,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to R.string.setup_orientation_auto,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to R.string.setup_orientation_landscape,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE to R.string.setup_orientation_reverse,
    )

    fun orientation(context: Context): Int =
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE)
            .getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_USER)
            .takeIf { value -> orientationOptions.any { it.first == value } }
            ?: ActivityInfo.SCREEN_ORIENTATION_USER

    fun setOrientation(context: Context, value: Int) {
        require(orientationOptions.any { it.first == value })
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().putInt("orientation", value).apply()
    }

    const val HOME_PAGE_STEAM = "steam"
    const val HOME_PAGE_APPS = "android-apps"

    val homePageOptions = listOf(
        HOME_PAGE_STEAM to R.string.rail_steam,
        HOME_PAGE_APPS to R.string.content_android_apps,
    )

    fun homePage(context: Context): String =
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE)
            .getString("home-page", null)
            .takeIf { value -> homePageOptions.any { it.first == value } }
            ?: HOME_PAGE_STEAM

    fun setHomePage(context: Context, value: String) {
        require(homePageOptions.any { it.first == value })
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit().putString("home-page", value).apply()
    }

    internal fun normalizeScale(percent: Int): Int = percent.takeIf { it in scales } ?: DEFAULT_SCALE

    fun scale(context: Context): Int = normalizeScale(
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).getInt("scale", DEFAULT_SCALE),
    )

    fun setScale(context: Context, percent: Int) {
        context.getSharedPreferences("app-ui", Context.MODE_PRIVATE).edit()
            .putInt("scale", normalizeScale(percent)).apply()
    }
}
