package com.droiddeck.launcher.session

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

class CdpPage(port: Int, path: String, private val onEvent: (String, JSONObject) -> Unit) : AutoCloseable {
    private val ws = LoopbackWebSocket(port, path, connectTimeoutMs = 2_000)
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "cdp-send").apply { isDaemon = true } }
    @Volatile var closed = false
        private set

    init {
        thread(name = "cdp-read", isDaemon = true) {
            try {
                while (true) {
                    val m = JSONObject(ws.receive() ?: break)
                    if (m.has("id")) pending.remove(m.optInt("id"))?.complete(m)
                    else onEvent(m.optString("method"), m.optJSONObject("params") ?: JSONObject())
                }
            } catch (_: Exception) {
            }
            closed = true
            pending.values.forEach { it.complete(JSONObject()) }
        }
    }

    fun send(method: String, params: JSONObject = JSONObject()) {
        if (closed) return
        val id = ids.incrementAndGet()
        val text = JSONObject().put("id", id).put("method", method).put("params", params).toString()
        runCatching { sender.execute { runCatching { ws.send(text) } } }
    }

    fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 3_000): JSONObject? {
        if (closed) return null
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val text = JSONObject().put("id", id).put("method", method).put("params", params).toString()
        runCatching { sender.execute { runCatching { ws.send(text) }.onFailure { future.complete(JSONObject()) } } }
            .onFailure { future.complete(JSONObject()) }
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull().also { pending.remove(id) }
    }

    fun evaluate(expression: String): Any? =
        call("Runtime.evaluate", JSONObject().put("expression", expression).put("returnByValue", true))
            ?.optJSONObject("result")?.optJSONObject("result")?.opt("value")

    override fun close() {
        closed = true
        sender.shutdown()
        runCatching { ws.close() }
    }
}
