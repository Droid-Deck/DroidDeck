package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * LSFG's shader chain, from the user's own Lossless Scaling (Steam app 993090).
 *
 * The copy the Steam client installs, in any library it installs to, is taken by
 * itself and taken again whenever the client updates it. Once the session has seen the account
 * own the app, a Lossless.dll can also be imported by hand; it stays until the Steam copy changes
 * or another is imported. The DLL is copied in and its shaders are built into a cache named after
 * its contents, so a different DLL hands the compositor a different path and its engine reloads.
 * Everything here blocks: call it off the main thread.
 */
object Lossless {
    enum class Source { NONE, STEAM, IMPORT }

    enum class ImportResult { UPDATED, UNCHANGED, NOT_OWNED, INVALID, FAILED }

    data class State(val owned: Boolean, val ready: Boolean, val source: Source) {
        companion object {
            val NONE = State(owned = false, ready = false, source = Source.NONE)
        }
    }

    private const val TAG = "Lossless"
    private const val PREFS = "lossless"
    private const val KEY_OWNED = "owned"
    private const val KEY_ID = "id"
    private const val KEY_SOURCE = "source"
    private const val KEY_STEAM_STAMP = "steamStamp"
    private const val STEAM_DLL = "steamapps/common/Lossless Scaling/Lossless.dll"
    private const val UNCHANGED = -1

    fun state(context: Context): State {
        val source = prefs(context).getString(KEY_SOURCE, null)
            ?.let { name -> Source.entries.firstOrNull { it.name == name } } ?: Source.NONE
        return State(owned(context), cacheFile(context)?.isFile == true, source)
    }

    /** Recorded once: by the session's ownership check (a flag in the guest's home) or a Steam copy. */
    fun owned(context: Context): Boolean {
        if (prefs(context).getBoolean(KEY_OWNED, false)) return true
        if (!ownedFlag(context).exists()) return false
        markOwned(context)
        return true
    }

    fun cacheFile(context: Context): File? = prefs(context).getString(KEY_ID, null)?.let { cacheFor(context, it) }

    /** Takes a new or updated Steam copy and rebuilds a missing or outdated cache. A [LsfgNative] status. */
    @Synchronized
    fun sync(context: Context): Int {
        val steam = steamDll(context)
        if (steam != null) {
            markOwned(context)
            val stamp = "${steam.length()}:${steam.lastModified()}"
            if (stamp != prefs(context).getString(KEY_STEAM_STAMP, null)) {
                val status = adopt(context, steam, Source.STEAM)
                if (status != LsfgNative.STATUS_OK && status != UNCHANGED) {
                    Log.w(TAG, "Steam copy $steam not taken: ${statusName(status)}")
                }
                prefs(context).edit().putString(KEY_STEAM_STAMP, stamp).apply()
            }
        }
        return ensureBuilt(context)
    }

    @Synchronized
    fun import(context: Context, dll: File): ImportResult {
        if (!owned(context)) return ImportResult.NOT_OWNED
        return when (adopt(context, dll, Source.IMPORT)) {
            UNCHANGED -> ImportResult.UNCHANGED
            LsfgNative.STATUS_OK -> ImportResult.UPDATED
            LsfgNative.STATUS_UNREADABLE_FILE, LsfgNative.STATUS_NOT_PORTABLE_EXECUTABLE,
            LsfgNative.STATUS_MISSING_SHADERS -> ImportResult.INVALID
            else -> ImportResult.FAILED
        }
    }

    fun message(context: Context, result: ImportResult): String = context.getString(
        when (result) {
            ImportResult.UPDATED -> R.string.lsfg_imported
            ImportResult.UNCHANGED -> R.string.lsfg_import_unchanged
            ImportResult.NOT_OWNED -> R.string.lsfg_import_not_owned
            ImportResult.INVALID -> R.string.lsfg_import_invalid
            ImportResult.FAILED -> R.string.lsfg_import_failed
        },
    )

    private fun ensureBuilt(context: Context): Int {
        val id = prefs(context).getString(KEY_ID, null) ?: return LsfgNative.STATUS_NOT_INSTALLED
        val cache = cacheFor(context, id)
        if (usable(cache)) return LsfgNative.STATUS_OK
        val dll = storedDll(context)
        if (!dll.isFile) return LsfgNative.STATUS_NOT_INSTALLED
        return build(dll, cache)
    }

    private fun adopt(context: Context, source: File, from: Source): Int {
        val staged = File(dir(context).apply { mkdirs() }, "Lossless.dll.staged")
        try {
            val id = copyHashing(source, staged)
            if (id == prefs(context).getString(KEY_ID, null) && usable(cacheFor(context, id))) return UNCHANGED
            val cache = cacheFor(context, id)
            var status = LsfgNative.nativeValidateDll(staged.path)
            if (status == LsfgNative.STATUS_OK) status = build(staged, cache)
            if (status != LsfgNative.STATUS_OK) {
                cache.delete()
                return status
            }
            if (!staged.renameTo(storedDll(context))) {
                cache.delete()
                return LsfgNative.STATUS_CACHE_UNUSABLE
            }
            prefs(context).edit().putString(KEY_ID, id).putString(KEY_SOURCE, from.name).apply()
            removeStale(context, id)
            Log.i(TAG, "Lossless.dll from $source ($from) ready as $id")
            return LsfgNative.STATUS_OK
        } catch (e: java.io.IOException) {
            Log.w(TAG, "could not copy $source", e)
            return LsfgNative.STATUS_UNREADABLE_FILE
        } catch (t: Throwable) {
            Log.e(TAG, "could not take $source", t)
            return LsfgNative.STATUS_CACHE_UNUSABLE
        } finally {
            staged.delete()
        }
    }

    private fun build(dll: File, cache: File): Int = try {
        val status = LsfgNative.nativeBuildCache(dll.path, cache.path, true)
        Log.i(TAG, "shader cache ${cache.name}: ${statusName(status)}" +
            if (status == LsfgNative.STATUS_OK) " (${LsfgNative.nativeVariantName(LsfgNative.nativeCacheVariant(cache.path))})" else "")
        status
    } catch (t: Throwable) {
        Log.e(TAG, "shader cache ${cache.name}", t)
        LsfgNative.STATUS_CACHE_UNUSABLE
    }

    private fun usable(cache: File): Boolean = cache.isFile &&
        runCatching { LsfgNative.nativeCacheVariant(cache.path) != LsfgNative.VARIANT_NONE }.getOrDefault(false)

    private fun copyHashing(source: File, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            DigestOutputStream(target.outputStream(), digest).use { output -> input.copyTo(output) }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun removeStale(context: Context, keep: String) {
        File(context.filesDir, "lsfg-native").deleteRecursively()
        dir(context).listFiles { f -> f.name.startsWith("shaders-") && f.name != cacheFor(context, keep).name }
            ?.forEach { it.delete() }
    }

    /** The newest copy in any library the client installs to: its own, the card's, the Games folders. */
    private fun steamDll(context: Context): File? {
        val libraries = listOfNotNull(
            File(LinuxRuntime.rootDir(context), "root/.local/share/Steam"),
            GameStorage.effective(context)?.path?.let(::File),
        ) + SessionPrefs.addedGamesDirs(context).map(::File)
        return libraries.map { File(it, STEAM_DLL) }.filter { it.isFile }.maxByOrNull { it.lastModified() }
    }

    private fun markOwned(context: Context) {
        if (!prefs(context).getBoolean(KEY_OWNED, false)) prefs(context).edit().putBoolean(KEY_OWNED, true).apply()
    }

    private fun statusName(status: Int): String =
        runCatching { LsfgNative.nativeStatusName(status) }.getOrDefault("status $status")

    private fun ownedFlag(context: Context) = File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/lossless-owned")
    private fun dir(context: Context) = File(context.filesDir, "lsfg")
    private fun storedDll(context: Context) = File(dir(context), "Lossless.dll")
    private fun cacheFor(context: Context, id: String) = File(dir(context), "shaders-$id.cache")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
