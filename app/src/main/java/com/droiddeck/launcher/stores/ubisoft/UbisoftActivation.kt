package com.droiddeck.launcher.stores.ubisoft

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import java.io.File

/**
 * Answers Denuvo activations for a running Ubisoft game. droiddeck-upc.exe writes the challenge
 * the game's aux DLL sends to `bridge-<product>/denuvo-<id>.req`; the answer goes to `.rsp`.
 */
object UbisoftActivation {
    private const val POLL_MS = 250L
    private const val IDLE_MS = 60_000L
    private val watching = HashMap<Long, Long>()

    fun bridge(context: Context, product: Long) = File(LinuxRuntime.rootDir(context), "root/.cache/droiddeck/ubisoft/bridge-$product")

    /** Starts answering the product's activations; stale requests are cleared before this returns. */
    fun watch(app: Context, product: Long) {
        val dir = bridge(app, product)
        synchronized(watching) {
            val running = watching.containsKey(product)
            watching[product] = System.currentTimeMillis()
            if (running) return
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }
        Thread({
            var idleSince = 0L
            while (true) {
                runCatching { dir.listFiles { f -> f.name.startsWith("denuvo-") && f.name.endsWith(".req") }?.forEach { answer(app, product, it) } }
                    .onFailure { StoresState.logLine("Ubisoft: activation for $product: ${it.javaClass.simpleName}") }
                val now = System.currentTimeMillis()
                if (SessionState.running) idleSince = 0L
                else if (idleSince == 0L) idleSince = now
                else if (now - idleSince > IDLE_MS) synchronized(watching) {
                    if (now - (watching[product] ?: 0L) > IDLE_MS) { watching.remove(product); return@Thread }
                }
                runCatching { Thread.sleep(POLL_MS) }
            }
        }, "ubisoft-activation-$product").start()
    }

    private fun answer(app: Context, product: Long, request: File) {
        val challenge = runCatching { request.readBytes() }.getOrNull() ?: return
        request.delete()
        val reply = File(request.parentFile, request.name.removeSuffix(".req") + ".rsp")
        val token = activate(app, product, challenge)
        val part = File(reply.path + ".part")
        part.writeBytes(token ?: ByteArray(0))
        part.renameTo(reply)
    }

    private fun activate(app: Context, product: Long, challenge: ByteArray): ByteArray? {
        val s = UbisoftAuth.current(app) ?: return null.also { StoresState.logLine("Ubisoft: activation for $product: signed out") }
        val answer = UbisoftNative.call("denuvo", JSONObject().put("ticket", s.ticket).put("sessionId", s.sessionId)
            .put("productId", product).put("challenge", hex(challenge)))
        answer?.optString("error")?.takeIf { it.isNotEmpty() }?.let {
            StoresState.logLine("Ubisoft: activation for $product: $it")
            return null
        }
        val token = answer?.optString("token")?.takeIf { it.isNotEmpty() }?.let(::unhex)
        StoresState.logLine("Ubisoft: activation for $product: ${token?.size ?: 0} B game token")
        return token
    }

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    internal fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0) null else runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
