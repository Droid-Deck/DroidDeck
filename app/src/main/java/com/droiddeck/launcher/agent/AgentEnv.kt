package com.droiddeck.launcher.agent

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Experiment switches an agent sets without touching Download/droiddeck-env or rebuilding the apk:
 *
 * - environment lines (KEY=VALUE) for the session, applied after the user's own droiddeck-env, so
 *   they win. "next" lines are taken by the next session that starts and dropped when it ends;
 *   "persistent" ones stay until cleared.
 * - binary overrides: a file pushed to the app's external files (agent-inbox/<name>) is installed
 *   as /opt/droiddeck-agent/bin/<name> in the rootfs and put first on the next session's PATH -
 *   a gamescope build under test, say. Removed when that session ends.
 *
 * Kept in the app's own files (agent-env.json), never in a place the shell can write.
 */
object AgentEnv {
    private const val TAG = "AgentEnv"
    private const val FILE = "agent-env.json"
    private const val GUEST_BIN = "/opt/droiddeck-agent/bin"
    private const val BASE_PATH = "/usr/local/bin:/usr/bin:/bin"
    private val NAME = Regex("[A-Za-z0-9._-]{1,64}")
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val lock = Any()

    fun inbox(context: Context): File = File(context.getExternalFilesDir(null), "agent-inbox")

    fun list(context: Context): JSONObject = synchronized(lock) { load(context) }

    /** Add [lines] for the next session ([persistent] = every session until cleared). */
    fun set(context: Context, lines: List<String>, persistent: Boolean): JSONObject = synchronized(lock) {
        val bad = lines.filter { line -> KEY.matchEntire(line.substringBefore('=', "")) == null || !line.contains('=') }
        if (bad.isNotEmpty()) throw AgentException("INVALID_ENV", "Not KEY=VALUE: ${bad.joinToString()}")
        val state = load(context)
        val key = if (persistent) "persistent" else "pending"
        val merged = merge(strings(state.optJSONArray(key)), lines)
        state.put(key, JSONArray(merged))
        save(context, state)
        state
    }

    /** Drop lines: [scope] is "next", "persistent" or "all". */
    fun clear(context: Context, scope: String): JSONObject = synchronized(lock) {
        val state = load(context)
        when (scope) {
            "next" -> state.put("pending", JSONArray())
            "persistent" -> state.put("persistent", JSONArray())
            "all" -> { state.put("pending", JSONArray()); state.put("persistent", JSONArray()); state.put("pendingOverrides", JSONArray()) }
            else -> throw AgentException("INVALID_SCOPE", "Scope must be next, persistent or all")
        }
        save(context, state)
        state
    }

    /** Install agent-inbox/[name] as an override for the next session. */
    fun override(context: Context, name: String): JSONObject = synchronized(lock) {
        if (NAME.matchEntire(name) == null) throw AgentException("INVALID_NAME", "Override names are letters, digits, '.', '_' and '-'")
        val source = File(inbox(context), name)
        if (!source.isFile) throw AgentException("NO_SUCH_FILE", "Push the binary to ${source.path} first")
        val target = File(LinuxRuntime.rootDir(context), GUEST_BIN.removePrefix("/") + "/" + name)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, "$name.tmp")
        source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
        if (!staged.setExecutable(true, false) || !staged.renameTo(target)) {
            staged.delete()
            throw AgentException("OVERRIDE_FAILED", "Could not install $name in the rootfs")
        }
        source.delete()
        val state = load(context)
        state.put("pendingOverrides", JSONArray(merge(strings(state.optJSONArray("pendingOverrides")), listOf(name))))
        save(context, state)
        state.put("installed", "$GUEST_BIN/$name")
    }

    /**
     * At session start: the lines this session gets, after the user's droiddeck-env. Pending lines
     * and overrides become this session's own, to be dropped by [endSession].
     */
    fun beginSession(context: Context): List<String> = synchronized(lock) {
        val state = load(context)
        val active = strings(state.optJSONArray("pending"))
        val overrides = strings(state.optJSONArray("pendingOverrides"))
        state.put("active", JSONArray(active)).put("pending", JSONArray())
        state.put("activeOverrides", JSONArray(overrides)).put("pendingOverrides", JSONArray())
        save(context, state)
        // Anything else there was left by a session that never reached endSession (the app died).
        File(LinuxRuntime.rootDir(context), GUEST_BIN.removePrefix("/")).listFiles()
            ?.filter { it.name !in overrides }?.forEach { it.delete() }
        val lines = strings(state.optJSONArray("persistent")) + active
        val withPath = if (overrides.isEmpty()) lines else lines + "PATH=$GUEST_BIN:${pathOf(lines)}"
        if (withPath.isNotEmpty()) Log.i(TAG, "agent environment for this session: $withPath")
        withPath
    }

    /** At session end: this session's lines and override binaries go. */
    fun endSession(context: Context) = synchronized(lock) {
        val state = load(context)
        val overrides = strings(state.optJSONArray("activeOverrides"))
        val pending = strings(state.optJSONArray("pendingOverrides"))
        val bin = File(LinuxRuntime.rootDir(context), GUEST_BIN.removePrefix("/"))
        overrides.filter { it !in pending }.forEach { File(bin, it).delete() }
        state.put("active", JSONArray()).put("activeOverrides", JSONArray())
        save(context, state)
    }

    /** A PATH= line among [lines] (the user's or an agent's) is extended rather than replaced. */
    private fun pathOf(lines: List<String>) =
        lines.lastOrNull { it.startsWith("PATH=") }?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: BASE_PATH

    private fun merge(existing: List<String>, added: List<String>): List<String> {
        val keys = added.map { it.substringBefore('=') }.toSet()
        return existing.filter { it.substringBefore('=') !in keys } + added
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.optString(it) }.filter { it.isNotEmpty() }

    private fun load(context: Context): JSONObject {
        val file = File(context.filesDir, FILE)
        val state = try { if (file.isFile) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        for (key in listOf("pending", "active", "persistent", "pendingOverrides", "activeOverrides")) {
            if (!state.has(key)) state.put(key, JSONArray())
        }
        return state
    }

    private fun save(context: Context, state: JSONObject) {
        val file = File(context.filesDir, FILE)
        val staged = File(context.filesDir, "$FILE.tmp")
        staged.writeText(state.toString())
        if (!staged.renameTo(file)) Log.w(TAG, "could not save $FILE")
    }
}

/** Typed writes to the app's SharedPreferences, for driving a settings matrix without the UI. */
object AgentPrefs {
    fun set(context: Context, file: String, key: String, type: String, value: String?): JSONObject {
        if (file == AgentAccess.PROTECTED_PREFS) throw AgentException("PROTECTED_PREFS", "The agent preferences are set on the device only")
        if (file.isBlank() || key.isBlank()) throw AgentException("INVALID_PREF", "file and key are required")
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        val previous = prefs.all[key]
        val editor = prefs.edit()
        try {
            when (type) {
                "remove" -> editor.remove(key)
                "bool" -> editor.putBoolean(key, value?.toBooleanStrictOrNull() ?: throw NumberFormatException("not true/false"))
                "int" -> editor.putInt(key, value!!.toInt())
                "long" -> editor.putLong(key, value!!.toLong())
                "float" -> editor.putFloat(key, value!!.toFloat())
                "string" -> editor.putString(key, value ?: "")
                else -> throw AgentException("INVALID_PREF", "type must be bool, int, long, float, string or remove")
            }
        } catch (e: RuntimeException) {
            if (e is AgentException) throw e
            throw AgentException("INVALID_PREF", "'$value' is not a $type")
        }
        if (!editor.commit()) throw AgentException("PREF_WRITE_FAILED", "Could not write $file/$key")
        return JSONObject().put("file", file).put("key", key).put("type", type)
            .put("previous", previous ?: JSONObject.NULL).put("value", prefs.all[key] ?: JSONObject.NULL)
    }

    fun get(context: Context, file: String): JSONObject {
        val all = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
        return JSONObject().put("file", file).put("values", JSONObject(all.mapValues { it.value ?: JSONObject.NULL }))
    }
}
