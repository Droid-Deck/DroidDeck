package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.net.LocalServerSocket
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.util.UUID

internal class XboxCredentials(private val app: Context) : Closeable {
    val name = "droiddeck-xbox-${UUID.randomUUID()}"
    private val generation = storage(app) { epoch(app) }
    private val server = LocalServerSocket(name)
    @Volatile private var closed = false

    init {
        Thread({
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    it.soTimeout = 10_000
                    val reply = runCatching {
                        check(it.peerCredentials.uid == android.os.Process.myUid())
                        storage(app) {
                            check(epoch(app) == generation)
                            answer(JSONObject(readLine(it.inputStream.buffered(), 2_097_152)))
                        }
                    }.getOrElse { JSONObject().put("ok", false) }
                    runCatching { it.outputStream.write((reply.toString() + "\n").toByteArray()) }
                }
            }
        }, "xbox-credentials").start()
    }

    private fun answer(request: JSONObject): JSONObject {
        val key = request.getString("key")
        require(key.isNotEmpty() && key.length <= 512)
        val record = StoreAccounts.read(app, Store.XBOX)
        check(!StoreAccounts.isUnavailable(Store.XBOX))
        val json = record ?: JSONObject()
        val slots = json.optJSONObject("slots") ?: JSONObject().also { json.put("slots", it) }
        val result = JSONObject().put("ok", true)
        when (request.getString("op")) {
            "get" -> result.put("value", slots.opt(key) ?: JSONObject.NULL)
            "set" -> {
                val bytes = request.getJSONArray("value")
                require(bytes.length() <= 262_144)
                for (i in 0 until bytes.length()) require(bytes.getInt(i) in 0..255)
                slots.put(key, bytes)
                StoreAccounts.write(app, Store.XBOX, json)
            }
            "remove" -> { slots.remove(key); StoreAccounts.write(app, Store.XBOX, json) }
            else -> error("Invalid credential operation")
        }
        return result
    }

    override fun close() { closed = true; runCatching { server.close() } }

    companion object {
        val lock = Any()
        private fun epoch(app: Context): String = runCatching { java.io.File(StoreAccounts.dir(app, Store.XBOX), "generation").readText() }.getOrDefault("")
        fun invalidate(app: Context) = storage(app) {
            java.io.File(StoreAccounts.dir(app, Store.XBOX), "generation").writeText(UUID.randomUUID().toString())
            StoreAccounts.clear(app, Store.XBOX)
        }
        fun <T> storage(app: Context, block: () -> T): T = synchronized(lock) {
            java.io.RandomAccessFile(java.io.File(StoreAccounts.dir(app, Store.XBOX), "account.lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }
        fun readLine(input: InputStream, limit: Int): String {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() <= limit) {
                val byte = input.read()
                if (byte == 10) return out.toString("UTF-8")
                if (byte < 0) throw java.io.EOFException("Xbox backend disconnected")
                out.write(byte)
            }
            error("Xbox backend response exceeded its limit")
        }
    }
}
