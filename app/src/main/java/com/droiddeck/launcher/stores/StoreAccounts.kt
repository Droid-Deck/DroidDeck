package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.File

object StoreAccounts {
    private const val TAG = "StoreAccounts"
    private const val FILE = "credentials.json"

    @VisibleForTesting
    @Volatile
    internal var keys: CredentialCipher.KeyProvider = CredentialCipher.Keystore

    @VisibleForTesting
    @Volatile
    internal var keystoreFailed = false

    fun dir(context: Context, store: Store): File = File(File(context.filesDir, "stores"), store.id).apply { mkdirs() }

    fun credentialsFile(context: Context, store: Store): File = File(dir(context, store), FILE)

    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<Store>()

    @Volatile
    private var unavailableLogged = false

    @Synchronized
    fun read(context: Context, store: Store): JSONObject? {
        val f = credentialsFile(context, store)
        if (!f.isFile) { unavailable.remove(store); return null }
        val text = try { f.readText() } catch (e: Exception) { return markUnavailable(store, e) }
        val json = try { JSONObject(text) } catch (e: Exception) { return unreadable(f, store, e) }
        if (!CredentialCipher.isEnvelope(json)) {
            unavailable.remove(store)
            seal(f, json)?.let { Log.i(TAG, "stores: credentials encrypted ${store.id}") }
            return json
        }
        return try {
            JSONObject(CredentialCipher.open(json, keys)).also { unavailable.remove(store) }
        } catch (e: Exception) {
            if (CredentialCipher.isPermanent(e)) unreadable(f, store, e) else markUnavailable(store, e)
        }
    }

    fun isUnavailable(store: Store): Boolean = store in unavailable

    private fun unreadable(f: File, store: Store, e: Exception): JSONObject? {
        Log.w(TAG, "stores: credentials for ${store.id} cannot be opened (${e.javaClass.simpleName}); signed out")
        f.delete()
        unavailable.remove(store)
        return null
    }

    private fun markUnavailable(store: Store, e: Exception): JSONObject? {
        unavailable.add(store)
        if (!unavailableLogged) {
            unavailableLogged = true
            Log.w(TAG, "stores: credentials unreadable this start (${e.javaClass.simpleName})")
        }
        return null
    }

    @Synchronized
    fun write(context: Context, store: Store, json: JSONObject) {
        unavailable.remove(store)
        val f = credentialsFile(context, store)
        if (seal(f, json, retry = store == Store.XBOX) == null) {
            check(store != Store.XBOX) { "Android Keystore is unavailable for Xbox credentials" }
            put(f, json.toString())
        }
    }

    @Synchronized
    fun clear(context: Context, store: Store) {
        unavailable.remove(store)
        credentialsFile(context, store).delete()
        File(credentialsFile(context, store).path + ".tmp").delete()
    }

    fun encryptAll(context: Context) {
        Store.entries.forEach { store ->
            File(credentialsFile(context, store).path + ".tmp").delete()
            read(context, store)
        }
    }

    fun signedInAs(context: Context, store: Store): String? {
        val json = read(context, store) ?: return if (isUnavailable(store)) store.label else null
        if (store == Store.XBOX) return json.optString("username").takeIf { it.isNotBlank() }
        if (store == Store.BATTLENET) return json.optString("display_name").takeIf { it.isNotBlank() }
        if (json.optString("access_token", "").isEmpty()) return null
        return json.optString("display_name", "").ifEmpty { json.optString("username", "") }.ifEmpty { store.label }
    }

    /** The name the Stores page shows. Xbox shows the gamertag, else the sign-in email with its name part masked. */
    fun displayName(context: Context, store: Store): String? {
        if (store != Store.XBOX) return signedInAs(context, store)
        val json = read(context, store) ?: return if (isUnavailable(store)) store.label else null
        val signedIn = json.optString("username").takeIf { it.isNotBlank() } ?: return null
        json.optString("gamertag").takeIf { it.isNotBlank() }?.let { return it }
        return maskEmail(signedIn)
    }

    internal fun maskEmail(name: String): String {
        val at = name.indexOf('@')
        return if (at <= 0) name.take(1) + "•••" else name.take(1) + "•••" + name.substring(at)
    }

    private fun seal(f: File, json: JSONObject, retry: Boolean = false): File? {
        if (keystoreFailed && !retry) return null
        val text = json.toString()
        val envelope = try {
            CredentialCipher.seal(text, keys).also { check(CredentialCipher.open(it, keys) == text) { "check failed" } }
        } catch (e: Exception) {
            keystoreFailed = true
            Log.w(TAG, "stores: keystore unavailable (${e.javaClass.simpleName})")
            return null
        }
        put(f, envelope.toString())
        return f
    }

    private fun put(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
