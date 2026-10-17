package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.content.SharedPreferences

object GogPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.gog", Context.MODE_PRIVATE)
}
