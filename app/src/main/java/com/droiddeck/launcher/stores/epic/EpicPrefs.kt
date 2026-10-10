package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.SharedPreferences

/**
 * Epic's own records that are not credentials: the library cache, each game's release date, the
 * cached deployment id and extra command line a launch needs. App-private preferences; the sign-in
 * lives in StoreAccounts. Public: the Java clients read it too.
 */
object EpicPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.epic", Context.MODE_PRIVATE)
}
