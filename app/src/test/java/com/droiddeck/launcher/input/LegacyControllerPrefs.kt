package com.droiddeck.launcher.input

import android.content.Context

/** Writes the controller settings older builds saved, so tests can seed the migrations that read them. */
internal object LegacyControllerPrefs {
    private fun edit(context: Context) = ControllerPrefs.prefs(context).edit()

    fun setTarget(context: Context, id: String, target: String) { edit(context).putString("map.$id", target).commit() }
    fun target(context: Context, id: String): String = ControllerPrefs.read(context).mapping[id] ?: id
    fun setSelectSteam(context: Context, on: Boolean) { edit(context).putBoolean("selectSteam", on).commit() }
    fun setOpacity(context: Context, percent: Int) { edit(context).putInt("opacity", percent).commit() }
    fun setStickClick(context: Context, on: Boolean) { edit(context).putBoolean("stickClick", on).commit() }
}
