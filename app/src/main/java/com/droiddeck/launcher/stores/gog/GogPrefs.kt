package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.content.SharedPreferences

/**
 * GOG's own records that are not credentials: the library cache, per-game facts the sync learnt
 * (generation, release date, size, cover), a build's client id. App-private preferences; the
 * sign-in itself lives in StoreAccounts. Public, not internal: the Java download manager reads it
 * and Kotlin mangles internal members' names for Java.
 */
object GogPrefs {
    @JvmStatic
    fun get(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("stores.gog", Context.MODE_PRIVATE)
}
