package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.File

/**
 * Each store's sign-in, kept in the app's private files: `filesDir/stores/<store>/credentials.json`.
 * Tokens never go to shared storage and never into a log; the file holds whatever the store's auth
 * flow handed back (access and refresh tokens, expiry, the account's display name, and for Amazon
 * the device registration), as one JSON object the store's own client reads and writes - sealed
 * with [CredentialCipher] under a key in the AndroidKeyStore. Every read and write of a sign-in
 * goes through here.
 *
 * - A plain file an earlier build wrote is sealed the first time it is read ([encryptAll] at app
 *   start), verified by opening it again, and the plain text is replaced.
 * - Where the Keystore does not work (some ROMs), the file stays plain, the log says so once, and
 *   the next start tries again.
 * - A sealed file that no longer opens (the key is gone: data cleared, a backup restored on another
 *   device) is deleted and the store reads as signed out.
 */
object StoreAccounts {
    private const val TAG = "StoreAccounts"
    private const val FILE = "credentials.json"

    @VisibleForTesting
    @Volatile
    internal var keys: CredentialCipher.KeyProvider = CredentialCipher.Keystore

    /** The Keystore failed once in this process: files stay plain until the next start tries again. */
    @VisibleForTesting
    @Volatile
    internal var keystoreFailed = false

    /** The store's private folder: credentials, caches, the engine's scratch. */
    fun dir(context: Context, store: Store): File = File(File(context.filesDir, "stores"), store.id).apply { mkdirs() }

    fun credentialsFile(context: Context, store: Store): File = File(dir(context, store), FILE)

    /** The stored credentials, or null when signed out or unreadable (never logged: the text is the secret). */
    @Synchronized
    fun read(context: Context, store: Store): JSONObject? {
        val f = credentialsFile(context, store)
        if (!f.isFile) return null
        val json = try { JSONObject(f.readText()) } catch (e: Exception) {
            Log.w(TAG, "${store.id}: credentials unreadable (${e.javaClass.simpleName})"); null
        } ?: return null
        if (!CredentialCipher.isEnvelope(json)) {
            seal(f, json)?.let { Log.i(TAG, "stores: credentials encrypted ${store.id}") }
            return json
        }
        return try {
            JSONObject(CredentialCipher.open(json, keys))
        } catch (e: Exception) {
            // The key that sealed it is gone; the sign-in cannot come back, so the store signs in again.
            Log.w(TAG, "stores: credentials for ${store.id} cannot be opened (${e.javaClass.simpleName}); signed out")
            f.delete()
            null
        }
    }

    @Synchronized
    fun write(context: Context, store: Store, json: JSONObject) {
        val f = credentialsFile(context, store)
        if (seal(f, json) == null) put(f, json.toString())
    }

    @Synchronized
    fun clear(context: Context, store: Store) {
        credentialsFile(context, store).delete()
        File(credentialsFile(context, store).path + ".tmp").delete()
    }

    /** At app start: every plain sign-in an earlier build left is sealed now. */
    fun encryptAll(context: Context) {
        Store.entries.forEach { store ->
            // An older build's temp file held the plain text too.
            File(credentialsFile(context, store).path + ".tmp").delete()
            read(context, store)
        }
    }

    /** The account's display name when signed in (the store's own name for it, or a stand-in), else null. */
    fun signedInAs(context: Context, store: Store): String? {
        val json = read(context, store) ?: return null
        if (json.optString("access_token", "").isEmpty()) return null
        return json.optString("display_name", "").ifEmpty { json.optString("username", "") }.ifEmpty { store.label }
    }

    /**
     * [json] sealed into [f], checked by opening what was written. Null - and [f] as it was - when
     * the Keystore cannot seal or the check fails.
     */
    private fun seal(f: File, json: JSONObject): File? {
        if (keystoreFailed) return null
        val text = json.toString()
        val envelope = try {
            CredentialCipher.seal(text, keys).also { check(CredentialCipher.open(it, keys) == text) { "check failed" } }
        } catch (e: Exception) {
            keystoreFailed = true
            Log.w(TAG, "stores: keystore unavailable, credentials stay plain (${e.javaClass.simpleName})")
            return null
        }
        put(f, envelope.toString())
        return f
    }

    /** [text] into [f] through a temp file and a rename, readable and writable by the app alone. */
    private fun put(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
