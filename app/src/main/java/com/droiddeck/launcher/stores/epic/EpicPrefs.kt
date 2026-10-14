package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.SharedPreferences

object EpicPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.epic", Context.MODE_PRIVATE)
}
