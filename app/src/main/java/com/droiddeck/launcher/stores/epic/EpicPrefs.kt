package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.SharedPreferences

object EpicPrefs {
    const val FILE = "stores.epic"

    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
