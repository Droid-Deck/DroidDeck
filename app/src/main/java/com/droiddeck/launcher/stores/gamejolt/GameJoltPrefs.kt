package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import android.content.SharedPreferences

object GameJoltPrefs {
    const val FILE = "stores.gamejolt"

    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
