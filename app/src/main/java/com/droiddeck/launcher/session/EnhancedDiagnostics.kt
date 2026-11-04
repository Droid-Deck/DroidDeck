package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object EnhancedDiagnostics {
    const val MANIFEST_FILE = "enhanced-diagnostics.json"

    private const val TAG = "EnhancedDiagnostics"
    private const val PREFS = "session"
    private const val ARMED = "enhancedDiagnosticsNext"
    private const val ACTIVE_RECORDS = "enhancedDiagnosticsActiveSessions"
    private const val SCHEMA = 1

    fun isArmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ARMED, false)

    fun setArmed(context: Context, armed: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ARMED, armed).commit()

    fun isActive(context: Context, folder: File?): Boolean = activeAt(context, folder) != null

    internal fun isCurrentSessionActive(context: Context): Boolean = isActive(context, SessionPaths.current())

    fun manifest(context: Context, folder: File?): JSONObject? {
        val activeAt = activeAt(context, folder) ?: return null
        return buildManifest(folder!!, activeAt)
    }

    @Synchronized
    internal fun consumeInto(context: Context, folder: File): Boolean {
        if (!isArmed(context)) return isActive(context, folder)
        if (!folder.isDirectory) return false
        val activatedAt = System.currentTimeMillis()
        if (!activateAndConsume(context, folder, activatedAt)) return false
        val marker = AtomicFile(File(folder, MANIFEST_FILE))
        val manifest = buildManifest(folder, activatedAt)
        val output = try {
            marker.startWrite()
        } catch (error: Exception) {
            Log.w(TAG, "could not start the informational enhanced diagnostics manifest", error)
            return true
        }
        try {
            output.write((manifest.toString() + "\n").toByteArray(Charsets.UTF_8))
            marker.finishWrite(output)
        } catch (error: Exception) {
            marker.failWrite(output)
            Log.w(TAG, "could not write the informational enhanced diagnostics manifest", error)
        }
        return true
    }

    @Synchronized
    internal fun clearRecordFor(context: Context, folder: File) {
        removeRecord(context, folder)
    }

    private fun buildManifest(folder: File, activatedAt: Long) = JSONObject()
        .put("schema", SCHEMA)
        .put("profile", "enhanced")
        .put("sessionId", folder.name)
        .put("activatedAtEpochMs", activatedAt)
        .put("exportBundlesBounded", true)
        .put("rawCaptureByteBounded", false)
        .put("rawCaptureLimitation", "Game and component diagnostics may grow until the session ends")
        .put("collectorsRequested", JSONArray(listOf(
            "session", "graphics", "games", "processes", "audio", "network", "components",
        )))

    private fun activeAt(context: Context, folder: File?): Long? {
        val path = trustedPath(context, folder) ?: return null
        return records(context).optLong(path, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
    }

    private fun activateAndConsume(context: Context, folder: File, activatedAt: Long): Boolean {
        val path = trustedPath(context, folder) ?: return false
        val records = records(context)
        records.keys().asSequence().toList().forEach { known ->
            if (!File(known).isDirectory) records.remove(known)
        }
        records.put(path, activatedAt)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE_RECORDS, records.toString())
            .putBoolean(ARMED, false)
            .commit()
    }

    private fun removeRecord(context: Context, folder: File) {
        val path = runCatching { folder.canonicalPath }.getOrNull() ?: return
        val records = records(context)
        if (records.has(path)) {
            records.remove(path)
            saveRecords(context, records)
        }
    }

    private fun trustedPath(context: Context, folder: File?): String? {
        if (folder == null || !SessionPaths.isSessionFolder(folder)) return null
        return runCatching {
            val canonical = folder.canonicalFile
            if (canonical.parentFile != SessionFiles.logDirectory(context).canonicalFile) null else canonical.path
        }.getOrNull()
    }

    private fun records(context: Context): JSONObject {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_RECORDS, null)
        return runCatching { JSONObject(text ?: "{}") }.getOrDefault(JSONObject())
    }

    private fun saveRecords(context: Context, records: JSONObject): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE_RECORDS, records.toString()).commit()
}
