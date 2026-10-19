package com.droiddeck.launcher.stores.itch

import android.content.Context
import android.content.SharedPreferences

object ItchPrefs {
    const val FILE = "stores.itch"

    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
