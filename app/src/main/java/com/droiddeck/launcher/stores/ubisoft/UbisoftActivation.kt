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
    private val owners = HashMap<Long, String>()

    private const val INVALIDATE = "denuvo-invalidate"

    fun bridge(context: Context, product: Long) = File(LinuxRuntime.rootDir(context), "root/.cache/droiddeck/ubisoft/bridge-$product")

    /** Starts answering the product's activations; stale requests are cleared before this returns. */
    fun watch(app: Context, product: Long, owner: String?) {
        val dir = bridge(app, product)
        synchronized(watching) {
            val running = watching.containsKey(product)
            watching[product] = System.currentTimeMillis()
            if (owner.isNullOrEmpty()) owners.remove(product) else owners[product] = owner
            if (running) return
            dir.mkdirs()
            dir.listFiles()?.forEach { if (it.name != INVALIDATE) it.delete() }
        }
        Thread({
            var idleSince = 0L
            while (true) {
                runCatching {
                    val owner = synchronized(watching) { owners[product] }
                    val marker = File(dir, INVALIDATE)
                    if (marker.exists()) {
                        val issuer = runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: owner
                        if (invalidate(app, product, issuer)) marker.delete()
                        else if (issuer != null) runCatching { marker.writeText(issuer) }
                    }
                    val pending = marker.exists()
                    dir.listFiles { f -> f.name.startsWith("denuvo-") && f.name.endsWith(".req") }?.forEach { answer(app, product, it, owner, pending) }
                }
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

    private fun answer(app: Context, product: Long, request: File, owner: String?, pending: Boolean) {
        val challenge = runCatching { request.readBytes() }.getOrNull() ?: return
        request.delete()
        val reply = File(request.parentFile, request.name.removeSuffix(".req").removeSuffix(".cache") + ".rsp")
        val token = activate(app, product, challenge, owner, cached = request.name.endsWith(".cache.req") && !pending)
        val part = File(reply.path + ".part")
        part.writeBytes(token ?: ByteArray(0))
        part.renameTo(reply)
    }

    private fun invalidate(app: Context, product: Long, owner: String?): Boolean {
        val user = owner ?: return false
        val gone = TokenCache(cacheDir(app)).drop(user, product)
        if (gone) StoresState.logLine("Ubisoft: activation for $product: kept game token dropped")
        return gone
    }

    private fun activate(app: Context, product: Long, challenge: ByteArray, owner: String?, cached: Boolean): ByteArray? {
        val s = UbisoftAuth.current(app) ?: return null.also { StoresState.logLine("Ubisoft: activation for $product: signed out") }
        if (owner == null || s.userId != owner) return null.also { StoresState.logLine("Ubisoft: activation for $product: the signed-in account changed") }
        val cache = TokenCache(cacheDir(app))
        if (cached) cache.get(s.userId, product)?.let {
            StoresState.logLine("Ubisoft: activation for $product: kept ${it.size} B game token")
            return it
        }
        val answer = UbisoftNative.call("denuvo", JSONObject().put("ticket", s.ticket).put("sessionId", s.sessionId)
            .put("productId", product).put("challenge", hex(challenge)))
        answer?.optString("error")?.takeIf { it.isNotEmpty() }?.let {
            StoresState.logLine("Ubisoft: activation for $product: $it")
            return null
        }
        val token = answer?.optString("token")?.takeIf { it.isNotEmpty() }?.let(::unhex)
        StoresState.logLine("Ubisoft: activation for $product: ${token?.size ?: 0} B game token")
        if (token != null && !cache.put(s.userId, product, token)) StoresState.logLine("Ubisoft: activation for $product: game token not kept")
        return token
    }

    private fun cacheDir(app: Context) = File(app.filesDir, "ubisoft/denuvo")

    internal class TokenCache(private val dir: File) {
        private fun file(user: String, product: Long) = File(dir, "${user.filter { it.isLetterOrDigit() || it == '-' }}-$product.token")

        fun get(user: String, product: Long): ByteArray? = runCatching { file(user, product).readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }

        fun put(user: String, product: Long, token: ByteArray): Boolean = runCatching {
            dir.mkdirs()
            val target = file(user, product)
            val part = File(target.path + ".part")
            part.writeBytes(token)
            check(part.renameTo(target))
        }.isSuccess

        fun drop(user: String, product: Long): Boolean = file(user, product).let { it.delete() || !it.exists() }
    }

    internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    internal fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0) null else runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
