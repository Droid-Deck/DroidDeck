package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Each store's sign-in, kept in the app's private files: `filesDir/stores/<store>/credentials.json`.
 * Tokens never go to shared storage and never into a log; the file holds whatever the store's auth
 * flow handed back (access and refresh tokens, expiry, the account's display name, and for Amazon
 * the device registration), as one JSON object the store's own client reads and writes.
 */
object StoreAccounts {
    private const val TAG = "StoreAccounts"
    private const val FILE = "credentials.json"

    /** The store's private folder: credentials, caches, the engine's scratch. */
    fun dir(context: Context, store: Store): File = File(File(context.filesDir, "stores"), store.id).apply { mkdirs() }

    fun credentialsFile(context: Context, store: Store): File = File(dir(context, store), FILE)

    /** The stored credentials, or null when signed out or unreadable (never logged: the text is the secret). */
    fun read(context: Context, store: Store): JSONObject? {
        val f = credentialsFile(context, store)
        if (!f.isFile) return null
        return try { JSONObject(f.readText()) } catch (e: Exception) { Log.w(TAG, "${store.id}: credentials unreadable (${e.javaClass.simpleName})"); null }
    }

    fun write(context: Context, store: Store, json: JSONObject) {
        val f = credentialsFile(context, store)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun clear(context: Context, store: Store) {
        credentialsFile(context, store).delete()
    }

    /** The account's display name when signed in (the store's own name for it, or a stand-in), else null. */
    fun signedInAs(context: Context, store: Store): String? {
        val json = read(context, store) ?: return null
        if (json.optString("access_token", "").isEmpty()) return null
        return json.optString("display_name", "").ifEmpty { json.optString("username", "") }.ifEmpty { store.label }
    }
}
