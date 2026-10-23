package com.droiddeck.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIcon {
    const val DARK = "dark"
    const val LIGHT = "light"

    private val aliases = linkedMapOf(DARK to "com.droiddeck.launcher.MainActivity", LIGHT to "com.droiddeck.launcher.LauncherLight")

    val options: List<Pair<String, Int>> = listOf(
        DARK to R.string.setup_launcher_icon_dark,
        LIGHT to R.string.setup_launcher_icon_light,
    )

    private fun component(context: Context, style: String) =
        ComponentName(context, aliases.getValue(style))

    private fun enabled(context: Context, style: String): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, style))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == DARK
            else -> false
        }

    fun current(context: Context): String = aliases.keys.firstOrNull { enabled(context, it) } ?: DARK

    fun activity(context: Context): ComponentName = component(context, current(context))

    fun repair(context: Context, launchedFrom: ComponentName?) {
        if (!enabled(context, DARK) || !enabled(context, LIGHT) || launchedFrom == component(context, DARK)) return
        context.packageManager.setComponentEnabledSetting(component(context, DARK),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    fun set(context: Context, style: String) {
        if (style !in aliases || style == current(context)) return
        val pm = context.packageManager
        pm.setComponentEnabledSetting(component(context, style),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        aliases.keys.filter { it != style }.forEach {
            pm.setComponentEnabledSetting(component(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
