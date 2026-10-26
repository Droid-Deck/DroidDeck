package com.droiddeck.launcher.stores.gfn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class GfnError(val code: String, message: String) : Exception(message)

/**
 * OpenNOW's core service (external/opennow, MIT, by Zortos) as a child process: sign-in, the
 * GeForce NOW catalog and CloudMatch sessions, spoken as newline-delimited JSON on its stdin and
 * stdout (external/opennow/docs/core-protocol.md). It ships as libopennowcore.so in the native
 * library directory, the one place the app may run programs from.
 */
object GfnCore {
    private const val TAG = "GfnCore"
    private const val PROTOCOL_VERSION = 5
    private const val BINARY = "libopennowcore.so"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val nextId = AtomicLong(1)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    /** The core runs in the :gfn process; other processes reach it through [GfnCoreService]. */
    private val inCoreProcess: Boolean by lazy {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        name.endsWith(":gfn")
    }

    fun addListener(listener: (String, JSONObject) -> Unit) { listeners += listener }
    fun removeListener(listener: (String, JSONObject) -> Unit) { listeners -= listener }

    /** Starts the core if it is not running, then checks the protocol and turns its reporting off. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (process?.isAlive == true) return
        val app = context.applicationContext
        val binary = File(app.applicationInfo.nativeLibraryDir, BINARY)
        check(binary.isFile) { "This build has no GeForce NOW core ($BINARY)" }
        val dataDir = File(app.filesDir, "gfn/core").apply { mkdirs() }
        val logFile = File(app.filesDir, "gfn/core.log")
        val started = ProcessBuilder(binary.path, "--data-dir", dataDir.path)
            .directory(dataDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        process = started
        writer = started.outputStream.bufferedWriter()
        Thread({ readLoop(started) }, "gfn-core-out").apply { isDaemon = true; start() }
        Log.i(TAG, "core started")
        request(app, "core.hello", JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("shell", "qt")
            .put("shellVersion", "droiddeck"), timeoutMs = 10_000)
        // DroidDeck never sends OpenNOW's analytics or crash reports anywhere.
        for ((key, value) in listOf("automaticBugReports" to "disabled", "errorReportingConsent" to "denied")) {
            runCatching { request(app, "settings.set", JSONObject().put("key", key).put("value", value)) }
                .onFailure { Log.w(TAG, "could not set $key: ${it.message}") }
        }
    }

    /** One request, answered with its `result` or thrown as [GfnError]. Blocking: call off the main thread. */
    fun request(context: Context, method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        if (!inCoreProcess) return GfnRemote.request(context, method, params, timeoutMs)
        if (method != "core.hello") ensureStarted(context)
        val id = "dd-" + nextId.getAndIncrement()
        val answer = CompletableFuture<JSONObject>()
        pending[id] = answer
        try {
            val line = JSONObject().put("type", "request").put("id", id).put("method", method).put("params", params).toString()
            synchronized(this) {
                val out = writer ?: throw GfnError("core_stopped", "The GeForce NOW core is not running")
                out.write(line)
                out.newLine()
                out.flush()
            }
            val response = try {
                answer.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw GfnError("timeout", "GeForce NOW did not answer in time ($method)")
            }
            if (response.optBoolean("ok")) return response.optJSONObject("result") ?: JSONObject()
            val error = response.optJSONObject("error")
            throw GfnError(error?.optString("code").orEmpty().ifEmpty { "error" },
                error?.optString("message").orEmpty().ifEmpty { "GeForce NOW request failed ($method)" })
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.close() }
        process?.destroy()
        process = null
        writer = null
        pending.values.forEach { it.completeExceptionally(GfnError("core_stopped", "The GeForce NOW core stopped")) }
        pending.clear()
    }

    private fun readLoop(running: Process) {
        try {
            running.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (message.optString("type")) {
                        "event" -> {
                            val name = message.optString("name")
                            val payload = message.optJSONObject("payload") ?: JSONObject()
                            listeners.forEach { runCatching { it(name, payload) } }
                        }
                        else -> pending[message.optString("id")]?.complete(message)
                    }
                }
            }
        } catch (_: Exception) {
        }
        Log.w(TAG, "core output ended")
        synchronized(this) {
            if (process === running) {
                process = null
                writer = null
            }
        }
        pending.values.forEach { it.completeExceptionally(GfnError("core_stopped", "The GeForce NOW core stopped")) }
    }
}
