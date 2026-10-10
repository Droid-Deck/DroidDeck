package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.content.SharedPreferences

/** Amazon's own records that are not credentials: the library cache, posters, versions. Public for the Java clients. */
object AmazonPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.amazon", Context.MODE_PRIVATE)
}
