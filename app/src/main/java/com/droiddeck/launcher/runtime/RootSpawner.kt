package com.droiddeck.launcher.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.OrphanReaper
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

object RootSpawner {
    private const val TAG = "RootSpawner"
    fun socket(context: Context): String = "${context.packageName}.root"
    private val seq = AtomicInteger()
    @Volatile private var server: LocalServerSocket? = null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val name = socket(app)
        val socket = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(TAG, "cannot listen on $name", e)
            return
        }
        server = socket
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { Log.e(TAG, "accept", e); break }
                Thread({ serve(app, client) }, "root-${seq.get() + 1}").apply { isDaemon = true; start() }
            }
        }, "root-server").apply { isDaemon = true; start() }
    }

    private fun serve(context: Context, client: LocalSocket) {
        val id = seq.incrementAndGet()
        val received = ArrayList<FileDescriptor>()
        client.use {
            val out = DataOutputStream(client.outputStream)
            try {
                if (client.peerCredentials.uid != Process.myUid()) {
                    Log.w(TAG, "refused root for uid ${client.peerCredentials.uid}")
                    return
                }
                val input = DataInputStream(client.inputStream)
                val kind = input.readUnsignedByte()
                client.ancillaryFileDescriptors?.let { received.addAll(it) }
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                client.ancillaryFileDescriptors?.let { received.addAll(it) }
                if (kind != 'R'.code) return
                run(context, JSONObject(String(body, Charsets.UTF_8)), received, client, input, out, id)
            } catch (e: Exception) {
                Log.e(TAG, "root $id", e)
                runCatching {
                    frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                    frame(out, 'X', "1".toByteArray())
                }
            } finally {
                received.forEach { fd -> runCatching { android.system.Os.close(fd) } }
            }
        }
    }

    private class Stdio(val redirect: ProcessBuilder.Redirect?, val fd: FileDescriptor?, val dup: ParcelFileDescriptor?)

    private fun stdio(fd: FileDescriptor?, write: Boolean): Stdio {
        if (fd == null) return Stdio(null, null, null)
        val dup = try { ParcelFileDescriptor.dup(fd) } catch (e: IOException) { return Stdio(null, fd, null) }
        val path = File("/proc/self/fd/${dup.fd}")
        val opens = try {
            if (write) FileOutputStream(path, true).close() else FileInputStream(path).close()
            true
        } catch (e: IOException) { false }
        if (!opens) return Stdio(null, fd, dup)
        val redirect = if (write) ProcessBuilder.Redirect.appendTo(path) else ProcessBuilder.Redirect.from(path)
        return Stdio(redirect, fd, dup)
    }

    private fun pump(from: InputStream, to: OutputStream, name: String) =
        Thread({
            val buffer = ByteArray(16384)
            try {
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    to.flush()
                }
            } catch (e: IOException) {
            } finally {
                runCatching { to.close() }
            }
        }, name).apply { isDaemon = true; start() }

    private fun run(context: Context, request: JSONObject, fds: List<FileDescriptor>, client: LocalSocket,
                    input: DataInputStream, out: DataOutputStream, id: Int) {
        val root = LinuxRuntime.rootDir(context)
        val argv = request.getJSONArray("argv").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (argv.isEmpty()) throw IOException("no command")
        val cwd = request.optString("cwd", "/root").ifEmpty { "/root" }
        val cmd = LinuxRuntime.prootPrefix(context, root, cwd, true)
        cmd.add(1, "--link2symlink")
        LinuxRuntime.lastBinds(context).filterNot { it.startsWith("/proc/self/fd/") }.forEach { cmd.add("-b"); cmd.add(it) }
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        request.optJSONObject("env")?.let { e -> e.keys().forEach { k -> cmd.add("$k=${e.getString(k)}") } }
        cmd.addAll(argv)
        val builder = ProcessBuilder(cmd).directory(root)
        builder.environment().apply {
            clear()
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).path)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        val streams = listOf(stdio(fds.getOrNull(0), false), stdio(fds.getOrNull(1), true), stdio(fds.getOrNull(2), true))
        try {
            builder.redirectInput(streams[0].redirect ?: ProcessBuilder.Redirect.PIPE)
            builder.redirectOutput(streams[1].redirect ?: ProcessBuilder.Redirect.PIPE)
            builder.redirectError(streams[2].redirect ?: ProcessBuilder.Redirect.PIPE)
            val process = builder.start()
            val pid = HostProcess.pidOf(process)
            OrphanReaper.keep(pid)
            Log.i(TAG, "root $id pid $pid: ${argv.joinToString(" ")}")
            synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) }
            val pumps = ArrayList<Thread>()
            if (streams[0].redirect == null) {
                val fd = streams[0].fd
                if (fd != null) pumps.add(pump(FileInputStream(fd), process.outputStream, "root-$id-in").also { it.isDaemon = true })
                else runCatching { process.outputStream.close() }
            }
            if (streams[1].redirect == null) pumps.add(pump(process.inputStream,
                streams[1].fd?.let { FileOutputStream(it) } ?: OutputStream.nullOutputStream(), "root-$id-out"))
            if (streams[2].redirect == null) pumps.add(pump(process.errorStream,
                streams[2].fd?.let { FileOutputStream(it) } ?: OutputStream.nullOutputStream(), "root-$id-err"))
            Thread({
                try {
                    while (true) {
                        val kind = input.readUnsignedByte()
                        val body = ByteArray(input.readInt()).also { input.readFully(it) }
                        if (kind == 'S'.code) String(body).trim().toIntOrNull()?.let { signal(pid, it) }
                    }
                } catch (e: IOException) {
                }
                if (process.isAlive) {
                    Log.i(TAG, "root $id: its caller is gone; stopping it")
                    signal(pid, android.system.OsConstants.SIGHUP)
                    android.os.Process.sendSignal(pid, android.system.OsConstants.SIGQUIT)
                }
            }, "root-$id-watch").apply { isDaemon = true; start() }
            val status = process.waitFor()
            pumps.filter { it.name.endsWith("-out") || it.name.endsWith("-err") }.forEach { it.join(2000) }
            OrphanReaper.release(pid)
            Log.i(TAG, "root $id exited $status")
            runCatching { synchronized(out) { frame(out, 'X', status.toString().toByteArray()) } }
        } finally {
            streams.forEach { s -> runCatching { s.dup?.close() } }
        }
    }

    private fun signal(top: Int, signal: Int) {
        val parents = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = try { File(dir, "stat").readText() } catch (e: IOException) { return@forEach }
            val fields = stat.substringAfterLast(')').trim().split(' ')
            fields.getOrNull(1)?.toIntOrNull()?.let { parents[pid] = it }
        }
        val tree = HashSet<Int>()
        var grew = true
        while (grew) {
            grew = false
            for ((pid, parent) in parents) {
                if (pid !in tree && (parent == top || parent in tree)) { tree.add(pid); grew = true }
            }
        }
        tree.forEach { runCatching { android.os.Process.sendSignal(it, signal) } }
    }

    private fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }
}
