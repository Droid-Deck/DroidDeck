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
    // Some devices refuse the abstract socket from inside PRoot; the backend then falls back to this keyed loopback port.
    private val loopback = java.net.ServerSocket(0, 8, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int get() = loopback.localPort
    val key: String = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var loopbackUsed = false
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

    /** Each connection gets its own thread; an accept error is logged and retried instead of silently ending the loop. */
    private fun <S> listen(route: String, accept: () -> S, serve: (S) -> Unit) = Thread({
        var failures = 0
        while (!closed) {
            val socket = try { accept() } catch (e: Exception) {
                if (closed) break
                failures++
                note("Credential $route accept failed: ${e.javaClass.simpleName}")
                if (failures >= 20) { note("Credential $route listener stopped after repeated accept failures"); break }
                Thread.sleep(100)
                continue
            }
            failures = 0
            Thread({ serve(socket) }, "xbox-credentials-$route-request").start()
        }
    }, "xbox-credentials-$route").start()

    /** Logs requests that take 2 s or more, and the stage a request is still in after 8 s (the backend waits 30 s). */
    private fun tracked(route: String, block: (step: (String) -> Unit) -> JSONObject): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        val stage = java.util.concurrent.atomic.AtomicReference("auth")
        val done = java.util.concurrent.CountDownLatch(1)
        val stages = java.util.Collections.synchronizedList(mutableListOf<String>())
        var mark = started
        val step = { next: String ->
            val now = android.os.SystemClock.elapsedRealtime()
            stages += "${stage.getAndSet(next)} ${now - mark} ms"
            mark = now
        }
        Thread({
            if (!done.await(8, java.util.concurrent.TimeUnit.SECONDS)) note("Credential $route request still in ${stage.get()} after 8 s")
        }, "xbox-credentials-watch").apply { isDaemon = true; start() }
        try {
            return block(step)
        } finally {
            done.countDown()
            step("done")
            val total = android.os.SystemClock.elapsedRealtime() - started
            if (total >= 2000) note("Credential $route request took $total ms (${stages.joinToString(", ")})")
        }
    }

    init {
        listen("socket", { server.accept() }) { socket ->
            socket.use {
                it.soTimeout = 10_000
                val reply = tracked("socket") { step ->
                    runCatching {
                        val peer = it.peerCredentials
                        check(peer.uid == android.os.Process.myUid() && authorized(peer.pid))
                        step("read")
                        val request = JSONObject(readLine(it.inputStream.buffered(), 2_097_152))
                        step("lock")
                        storage(app) {
                            step("answer")
                            check(!closed && epoch(app) == generation && authorized(peer.pid))
                            answer(request, step)
                        }
                    }.getOrElse {
                        note("Credential request rejected: ${it.javaClass.simpleName}")
                        JSONObject().put("ok", false)
                    }
                }
                runCatching { it.outputStream.write((reply.toString() + "\n").toByteArray()) }
                    .onFailure { failure -> note("Credential reply not delivered: ${failure.javaClass.simpleName}") }
            }
        }
        listen("loopback", { loopback.accept() }) { socket ->
            socket.use {
                it.soTimeout = 10_000
                val reply = tracked("loopback") { step ->
                    runCatching {
                        val input = it.getInputStream().buffered()
                        check(it.inetAddress.isLoopbackAddress)
                        if (!java.security.MessageDigest.isEqual(readLine(input, 128).toByteArray(), key.toByteArray())) {
                            rejected("loopback-key"); error("loopback key")
                        }
                        if (!loopbackOwner(it.port)) { rejected("loopback-owner"); error("loopback owner") }
                        check(bound.await(5, java.util.concurrent.TimeUnit.SECONDS) && backend?.isAlive == true)
                        if (!loopbackUsed) { loopbackUsed = true; note("Backend reached the app over the loopback fallback") }
                        step("read")
                        val request = JSONObject(readLine(input, 2_097_152))
                        step("lock")
                        storage(app) {
                            step("answer")
                            check(!closed && epoch(app) == generation)
                            answer(request, step)
                        }
                    }.getOrElse { failure ->
                        note("Credential request rejected: ${failure.javaClass.simpleName}")
                        JSONObject().put("ok", false)
                    }
                }
                runCatching { it.getOutputStream().write((reply.toString() + "\n").toByteArray()) }
                    .onFailure { failure -> note("Credential reply not delivered: ${failure.javaClass.simpleName}") }
            }
        }
    }

    /** The connecting socket must belong to this app's user; an unreadable table (newer Android) leaves the key as the check. */
    private fun loopbackOwner(peerPort: Int): Boolean {
        val uid = android.os.Process.myUid()
        val rows = listOf("/proc/net/tcp", "/proc/net/tcp6").mapNotNull { runCatching { java.io.File(it).readLines().drop(1) }.getOrNull() }.flatten()
        val owners = rows.mapNotNull { row ->
            val fields = row.trim().split(Regex("\\s+"))
            val local = fields.getOrNull(1)?.substringAfterLast(':')?.toIntOrNull(16)
            val remote = fields.getOrNull(2)?.substringAfterLast(':')?.toIntOrNull(16)
            if (local == peerPort && remote == loopback.localPort) fields.getOrNull(7)?.toIntOrNull() else null
        }
        return owners.isEmpty() || owners.all { it == uid }
    }

    private fun answer(request: JSONObject, step: (String) -> Unit = {}): JSONObject {
        val key = request.getString("key")
        require(key.isNotEmpty() && key.length <= 512)
        step("record-read")
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
                step("record-save")
                StoreAccounts.write(app, Store.XBOX, json)
            }
            "remove" -> { slots.remove(key); step("record-save"); StoreAccounts.write(app, Store.XBOX, json) }
            else -> error("Invalid credential operation")
        }
        return result
    }

    override fun close() { closed = true; bound.countDown(); runCatching { server.close() }; runCatching { loopback.close() } }

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
