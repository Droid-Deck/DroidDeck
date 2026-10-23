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

internal class XboxCredentials(private val app: Context, expectedGeneration: String? = null) : Closeable {
    val name = "droiddeck-xbox-${UUID.randomUUID()}"
    private val generation = lifecycle(app, expectedGeneration) { it }
    private val server = LocalServerSocket(name)
    @Volatile private var closed = false
    private val bound = java.util.concurrent.CountDownLatch(1)
    private var backend: Process? = null
    private var binaryHash = byteArrayOf()
    private lateinit var binary: java.io.File
    private val peers = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun bind(process: Process, file: java.io.File, digest: ByteArray) {
        backend = process
        binary = file.canonicalFile
        binaryHash = digest
        bound.countDown()
    }

    private fun rejected(reason: String): Boolean {
        note("Rejected credential peer: $reason")
        return false
    }

    /** Also written to the stores log: sign-in runs in its own process, where only that file reaches a shared log. */
    private fun note(text: String) {
        android.util.Log.w("XboxCredentials", text)
        com.droiddeck.launcher.stores.StoreLogFiles.stamped(app, "Xbox credentials: $text")
    }

    private fun authorized(pid: Int): Boolean {
        if (!bound.await(5, java.util.concurrent.TimeUnit.SECONDS) || closed) return rejected("unbound")
        val process = backend ?: return rejected("missing-child")
        if (!process.isAlive) return rejected("exited-child")
        val root = com.droiddeck.launcher.core.HostProcess.pidOf(process)
        if (root <= 0 || pid <= 0) return rejected("missing-process-id")
        val identity = identity(pid, root) ?: return rejected("foreign-backend")
        if (peers[pid] == identity) return true
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        binary.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (!java.security.MessageDigest.isEqual(binaryHash, digest.digest())) return rejected("different-executable")
        peers[pid] = identity
        return true
    }

    private fun identity(pid: Int, root: Int): String? {
        if (root <= 0 || pid <= 0) return null
        val status = java.io.File("/proc/$pid/status").readLines()
        val tracer = status.first { it.startsWith("TracerPid:") }.substringAfter(':').trim().toInt()
        val tracerGroup = java.io.File("/proc/$tracer/status").readLines()
            .first { it.startsWith("Tgid:") }.substringAfter(':').trim().toInt()
        if (tracerGroup != root) return null
        val executable = android.system.Os.stat(binary.path)
        if (!mapsBackend(java.io.File("/proc/$pid/maps").readText(), binary.path, executable.st_ino))
            return null
        return java.io.File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19] + ":${executable.st_dev}:${executable.st_ino}"
    }

    fun signalBackend(signal: Int) {
        val process = backend ?: return
        if (!process.isAlive) return
        val root = com.droiddeck.launcher.core.HostProcess.pidOf(process)
        peers.forEach { (pid, expected) -> runCatching {
            if (identity(pid, root) == expected) android.system.Os.kill(pid, signal)
        } }
    }

    init {
        Thread({
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    it.soTimeout = 10_000
                    val reply = runCatching {
                        val peer = it.peerCredentials
                        check(peer.uid == android.os.Process.myUid() && authorized(peer.pid))
                        val request = JSONObject(readLine(it.inputStream.buffered(), 2_097_152))
                        storage(app) {
                            check(!closed && epoch(app) == generation && authorized(peer.pid))
                            answer(request)
                        }
                    }.getOrElse {
                        note("Credential request rejected: ${it.javaClass.simpleName}")
                        JSONObject().put("ok", false)
                    }
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

    override fun close() { closed = true; bound.countDown(); runCatching { server.close() } }

    companion object {
        val lock = Any()
        internal fun mapsBackend(maps: String, path: String, inode: Long): Boolean = maps.lineSequence().any { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 6)
            fields.size == 6 && 'x' in fields[1] && fields[4].toLongOrNull() == inode &&
                runCatching { java.io.File(fields[5]).canonicalPath == path }.getOrDefault(false)
        }
        private fun epoch(app: Context): String = runCatching { java.io.File(StoreAccounts.dir(app, Store.XBOX), "generation").readText() }.getOrDefault("")
        fun <T> lifecycle(app: Context, expected: String? = null, block: (String) -> T): T = storage(app) {
            val current = epoch(app)
            check(expected == null || expected == current) { "Xbox account changed during the request" }
            block(current)
        }
        fun invalidate(app: Context, revoked: () -> Unit = {}) = storage(app) {
            java.io.File(StoreAccounts.dir(app, Store.XBOX), "generation").writeText(UUID.randomUUID().toString())
            StoreAccounts.clear(app, Store.XBOX)
            revoked()
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
