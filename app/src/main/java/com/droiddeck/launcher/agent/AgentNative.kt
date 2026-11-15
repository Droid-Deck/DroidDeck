package com.droiddeck.launcher.agent

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.NativeGuest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Runs a guest program without proot (`droiddeckctl native`), for testing the native userland. */
object AgentNative {
    private const val OUTPUT_CAP = 256 * 1024

    fun run(context: Context, request: JSONObject, timeoutMs: Long): JSONObject {
        val root = LinuxRuntime.rootDir(context)
        com.droiddeck.launcher.session.SessionFiles.stage(context, root)
        // with the setting off, staging leaves the rootfs as proot wants it
        if (!com.droiddeck.launcher.session.SessionPrefs.nativeUserland(context)) NativeGuest.prepare(context, root)
        if (!NativeGuest.available(root)) throw AgentException("NATIVE_UNAVAILABLE", "The rootfs has no native hook or glibc loader yet; start a session once")
        val argvJson = request.optJSONArray("argv") ?: throw AgentException("INVALID_REQUEST", "argv is required")
        val argv = (0 until argvJson.length()).map { argvJson.getString(it) }
        if (argv.isEmpty()) throw AgentException("INVALID_REQUEST", "argv is empty")
        val env = linkedMapOf<String, String>()
        request.optJSONObject("env")?.let { obj -> obj.keys().forEach { env[it] = obj.getString(it) } }
        val preload = request.optJSONArray("preload")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val builder = NativeGuest.builder(root, request.optString("cwd", "/root"), argv, env, preload)
        val started = System.nanoTime()
        val process = builder.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val readers = listOf(
            Thread { drain(process.inputStream, out) },
            Thread { drain(process.errorStream, err) },
        ).onEach { it.isDaemon = true; it.start() }
        // written beside the wait: a program that never reads more than a pipe holds would block it
        val input = request.optString("stdin").toByteArray()
        Thread {
            try {
                process.outputStream.use { stdin -> if (input.isNotEmpty()) stdin.write(input) }
            } catch (_: java.io.IOException) {
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        readers.forEach { it.join(2000) }
        val code = if (finished) process.exitValue() else -1
        return JSONObject().put("ok", true).put("command", "native")
            .put("exitCode", code).put("timedOut", !finished)
            .put("ms", (System.nanoTime() - started) / 1_000_000)
            .put("stdout", JSONObject().put("text", out.toString()))
            .put("stderr", JSONObject().put("text", err.toString()))
            .put("argv", JSONArray(argv))
    }

    private fun drain(input: java.io.InputStream, into: StringBuilder) {
        val buffer = ByteArray(8192)
        val text = java.io.ByteArrayOutputStream()
        while (true) {
            val n = try { input.read(buffer) } catch (e: java.io.IOException) { -1 }
            if (n < 0) break
            if (text.size() < OUTPUT_CAP) text.write(buffer, 0, minOf(n, OUTPUT_CAP - text.size()))
        }
        into.append(text.toString(Charsets.UTF_8.name()))
    }
}
