package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.content.SharedPreferences

object AmazonPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.amazon", Context.MODE_PRIVATE)
}
