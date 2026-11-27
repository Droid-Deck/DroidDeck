package com.droiddeck.launcher.runtime

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A guest program run as the main process of its own Android process.
 *
 * Android counts the processes in an app process's cgroup against its child-process limit, but not
 * the app processes themselves. The worker execs the program in place; its binder fd stays open
 * across the exec, so the system keeps the process as a live service process. The program's own
 * children are still counted. See docs/phantom-processes.md.
 */
open class GuestWorker : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != RUN) return super.onTransact(code, data, reply, flags)
            val argv = data.createStringArray() ?: return false
            val env = data.createStringArray() ?: return false
            val fds = List(data.readInt()) { data.readFileDescriptor() }
            val targets = data.createIntArray() ?: IntArray(fds.size) { it }
            reply?.writeInt(Process.myPid())
            Handler(Looper.getMainLooper()).postDelayed({ exec(argv, env, fds, targets) }, EXEC_DELAY_MS)
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun exec(argv: Array<String>, env: Array<String>, fds: List<ParcelFileDescriptor?>, targets: IntArray) {
        try {
            keepBinderOpen()
            // Out of the way first, so that placing one fd cannot overwrite another not yet placed.
            val moved = fds.map { pfd -> pfd?.let { Os.fcntlInt(it.fileDescriptor, OsConstants.F_DUPFD, MOVED_FDS) } }
            moved.forEachIndexed { i, fd ->
                if (fd == null || i >= targets.size) return@forEachIndexed
                val source = ParcelFileDescriptor.adoptFd(fd).also { kept.add(it) }.fileDescriptor
                Os.dup2(source, targets[i])
                Os.fcntlInt(source, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            }
            Os.execve(argv[0], argv, env)
        } catch (e: ErrnoException) {
            Log.e(TAG, "could not exec ${argv.firstOrNull()}", e)
        }
        Process.killProcess(Process.myPid())
    }

    /** Binder fds stay open across the exec, above the numbers the program's fds go to. */
    private fun keepBinderOpen() {
        File("/proc/self/fd").listFiles()?.forEach { link ->
            val target = runCatching { Os.readlink(link.path) }.getOrNull() ?: return@forEach
            if (!target.contains("binder")) return@forEach
            val fd = ParcelFileDescriptor.adoptFd(link.name.toIntOrNull() ?: return@forEach)
            kept.add(fd)
            Os.fcntlInt(fd.fileDescriptor, OsConstants.F_DUPFD, BINDER_FDS)
        }
    }

    companion object {
        const val RUN = IBinder.FIRST_CALL_TRANSACTION
        private const val EXEC_DELAY_MS = 100L
        private const val BINDER_FDS = 900
        private const val MOVED_FDS = 1200
        private const val TAG = "GuestWorker"
        private val kept = ArrayList<ParcelFileDescriptor>()

        fun isWorkerProcess(context: Context, process: String?): Boolean =
            process != null && process.startsWith(context.packageName + ":g") &&
                process.substring(context.packageName.length + 2).all { it.isDigit() }
    }
}

class GuestWorker0 : GuestWorker()
class GuestWorker1 : GuestWorker()
class GuestWorker2 : GuestWorker()
class GuestWorker3 : GuestWorker()
class GuestWorker4 : GuestWorker()
class GuestWorker5 : GuestWorker()
class GuestWorker6 : GuestWorker()
class GuestWorker7 : GuestWorker()
class GuestWorker8 : GuestWorker()
class GuestWorker9 : GuestWorker()
class GuestWorker10 : GuestWorker()
class GuestWorker11 : GuestWorker()
class GuestWorker12 : GuestWorker()
class GuestWorker13 : GuestWorker()
class GuestWorker14 : GuestWorker()
class GuestWorker15 : GuestWorker()
class GuestWorker16 : GuestWorker()
class GuestWorker17 : GuestWorker()
class GuestWorker18 : GuestWorker()
class GuestWorker19 : GuestWorker()
class GuestWorker20 : GuestWorker()
class GuestWorker21 : GuestWorker()
class GuestWorker22 : GuestWorker()
class GuestWorker23 : GuestWorker()
class GuestWorker24 : GuestWorker()
class GuestWorker25 : GuestWorker()
class GuestWorker26 : GuestWorker()
class GuestWorker27 : GuestWorker()
class GuestWorker28 : GuestWorker()
class GuestWorker29 : GuestWorker()
class GuestWorker30 : GuestWorker()
class GuestWorker31 : GuestWorker()
class GuestWorker32 : GuestWorker()
class GuestWorker33 : GuestWorker()
class GuestWorker34 : GuestWorker()
class GuestWorker35 : GuestWorker()
class GuestWorker36 : GuestWorker()
class GuestWorker37 : GuestWorker()
class GuestWorker38 : GuestWorker()
class GuestWorker39 : GuestWorker()
class GuestWorker40 : GuestWorker()
class GuestWorker41 : GuestWorker()
class GuestWorker42 : GuestWorker()
class GuestWorker43 : GuestWorker()
class GuestWorker44 : GuestWorker()
class GuestWorker45 : GuestWorker()
class GuestWorker46 : GuestWorker()
class GuestWorker47 : GuestWorker()
class GuestWorker48 : GuestWorker()
class GuestWorker49 : GuestWorker()
class GuestWorker50 : GuestWorker()
class GuestWorker51 : GuestWorker()
class GuestWorker52 : GuestWorker()
class GuestWorker53 : GuestWorker()
class GuestWorker54 : GuestWorker()
class GuestWorker55 : GuestWorker()
class GuestWorker56 : GuestWorker()
class GuestWorker57 : GuestWorker()
class GuestWorker58 : GuestWorker()
class GuestWorker59 : GuestWorker()
class GuestWorker60 : GuestWorker()
class GuestWorker61 : GuestWorker()
class GuestWorker62 : GuestWorker()
class GuestWorker63 : GuestWorker()

/** The pool of [GuestWorker] processes, and the socket guests ask it through (`droiddeck-spawn`). */
object GuestWorkers {
    private const val TAG = "GuestWorkers"
    private val classes: List<Class<out GuestWorker>> = listOf(
        GuestWorker0::class.java, GuestWorker1::class.java, GuestWorker2::class.java,
        GuestWorker3::class.java, GuestWorker4::class.java, GuestWorker5::class.java,
        GuestWorker6::class.java, GuestWorker7::class.java, GuestWorker8::class.java,
        GuestWorker9::class.java, GuestWorker10::class.java, GuestWorker11::class.java,
        GuestWorker12::class.java, GuestWorker13::class.java, GuestWorker14::class.java,
        GuestWorker15::class.java, GuestWorker16::class.java, GuestWorker17::class.java,
        GuestWorker18::class.java, GuestWorker19::class.java, GuestWorker20::class.java,
        GuestWorker21::class.java, GuestWorker22::class.java, GuestWorker23::class.java,
        GuestWorker24::class.java, GuestWorker25::class.java, GuestWorker26::class.java,
        GuestWorker27::class.java, GuestWorker28::class.java, GuestWorker29::class.java,
        GuestWorker30::class.java, GuestWorker31::class.java, GuestWorker32::class.java,
        GuestWorker33::class.java, GuestWorker34::class.java, GuestWorker35::class.java,
        GuestWorker36::class.java, GuestWorker37::class.java, GuestWorker38::class.java,
        GuestWorker39::class.java, GuestWorker40::class.java, GuestWorker41::class.java,
        GuestWorker42::class.java, GuestWorker43::class.java, GuestWorker44::class.java,
        GuestWorker45::class.java, GuestWorker46::class.java, GuestWorker47::class.java,
        GuestWorker48::class.java, GuestWorker49::class.java, GuestWorker50::class.java,
        GuestWorker51::class.java, GuestWorker52::class.java, GuestWorker53::class.java,
        GuestWorker54::class.java, GuestWorker55::class.java, GuestWorker56::class.java,
        GuestWorker57::class.java, GuestWorker58::class.java, GuestWorker59::class.java,
        GuestWorker60::class.java, GuestWorker61::class.java, GuestWorker62::class.java,
        GuestWorker63::class.java,
    )

    private class Slot(val index: Int, val connection: ServiceConnection) {
        @Volatile var pid = -1
    }

    private val slots = arrayOfNulls<Slot>(classes.size)
    @Volatile private var server: Thread? = null

    fun socketName(context: Context): String = "droiddeck-spawn." + context.packageName + "." + Process.myUid()

    /**
     * Runs [argv] (a host command line) as the main process of a free worker with [env] and the
     * fds in [stdio] as its 0, 1 and 2. Returns its pid, or -1. Blocks: not for the main thread.
     * [onExit] runs once the process is gone.
     */
    fun start(context: Context, argv: List<String>, env: List<String>, stdio: List<ParcelFileDescriptor>, onExit: (Int) -> Unit): Int =
        start(context, argv, env, stdio, IntArray(stdio.size) { it }, onExit)

    /** As [start], with the fds in [fds] placed at the numbers in [targets]. */
    fun start(context: Context, argv: List<String>, env: List<String>, fds: List<ParcelFileDescriptor>, targets: IntArray,
              onExit: (Int) -> Unit): Int {
        val app = context.applicationContext
        val connected = CountDownLatch(1)
        var service: IBinder? = null
        val slot: Slot
        synchronized(slots) {
            val index = slots.indexOfFirst { it == null }
            if (index < 0) {
                Log.w(TAG, "all ${slots.size} workers are busy")
                return -1
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = binder
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = release(app, index, onExit)

                override fun onBindingDied(name: ComponentName) = release(app, index, onExit)
            }
            slot = Slot(index, connection)
            slots[index] = slot
        }
        val intent = Intent(app, classes[slot.index])
        if (!app.bindService(intent, slot.connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) ||
            !connected.await(10, TimeUnit.SECONDS)) {
            Log.w(TAG, "worker ${slot.index} did not start")
            release(app, slot.index, null)
            return -1
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeStringArray(argv.toTypedArray())
            data.writeStringArray(env.toTypedArray())
            data.writeInt(fds.size)
            fds.forEach { data.writeFileDescriptor(it.fileDescriptor) }
            data.writeIntArray(targets)
            service!!.transact(GuestWorker.RUN, data, reply, 0)
            slot.pid = reply.readInt()
        } catch (e: Exception) {
            Log.w(TAG, "worker ${slot.index} refused ${argv.firstOrNull()}", e)
            release(app, slot.index, null)
            return -1
        } finally {
            data.recycle()
            reply.recycle()
        }
        Log.i(TAG, "worker ${slot.index} (pid ${slot.pid}) runs ${argv.lastOrNull { !it.startsWith("-") }}")
        return slot.pid
    }

    private fun release(app: Context, index: Int, onExit: ((Int) -> Unit)?) {
        val slot = synchronized(slots) { slots[index]?.also { slots[index] = null } } ?: return
        runCatching { app.unbindService(slot.connection) }
        if (onExit != null) Thread({ onExit(exitStatus(app, slot.pid)) }, "guest-worker-exit").start()
    }

    /** The worker's exit as a wait status (exit code << 8, or the signal), -1 when unknown. */
    private fun exitStatus(app: Context, pid: Int): Int {
        if (pid <= 0 || android.os.Build.VERSION.SDK_INT < 30) return -1
        val am = app.getSystemService(ActivityManager::class.java)
        repeat(20) {
            am.getHistoricalProcessExitReasons(app.packageName, pid, 1).firstOrNull()?.let {
                return if (it.reason == android.app.ApplicationExitInfo.REASON_SIGNALED) it.status and 0x7f
                else (it.status and 0xff) shl 8
            }
            Thread.sleep(50)
        }
        return -1
    }

    /** Kills every worker's program; for session teardown. */
    fun stopAll() {
        val pids = synchronized(slots) { slots.mapNotNull { it?.pid?.takeIf { pid -> pid > 0 } } }
        pids.forEach { Process.sendSignal(it, OsConstants.SIGTERM) }
        Thread.sleep(500)
        pids.forEach { Process.killProcess(it) }
    }

    /** Listens for `droiddeck-spawn` requests from the session's guests. */
    fun serve(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (server != null) return
            server = Thread({
                val socket = try {
                    LocalServerSocket(socketName(app))
                } catch (e: Exception) {
                    Log.e(TAG, "no spawn socket", e)
                    return@Thread
                }
                while (true) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: continue
                    Thread({ handle(app, client) }, "guest-spawn").start()
                }
            }, "guest-spawn-server").apply { isDaemon = true; start() }
        }
    }

    private fun handle(app: Context, client: LocalSocket) {
        client.use {
            if (client.peerCredentials.uid != Process.myUid()) return
            val input = DataInputStream(client.inputStream)
            val payload = ByteArray(input.readInt()).also { input.readFully(it) }
            val received = client.ancillaryFileDescriptors.orEmpty()
            val fds = received.map { ParcelFileDescriptor.dup(it) }
            received.forEach { runCatching { Os.close(it) } }
            val request = JSONObject(String(payload))
            val argv = request.getJSONArray("argv").let { a -> List(a.length()) { a.getString(it) } }
            val env = LinkedHashMap<String, String>()
            request.optJSONObject("env")?.let { o -> o.keys().forEach { env[it] = o.getString(it) } }
            request.optJSONArray("envlist")?.let { a ->
                for (i in 0 until a.length()) a.getString(i).split('=', limit = 2).let { if (it.size == 2) env[it[0]] = it[1] }
            }
            val cwd = request.optString("cwd", "/")
            val wait = request.optBoolean("wait", false)
            val file = request.optString("file", "")
            // The handoff table (the hook's) comes last among the fds; the rest go to these numbers.
            val tableIndex = request.optInt("table", -1)
            val numbers = request.optJSONArray("fds")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(3) { it }
            val table = fds.getOrNull(tableIndex)
            val slot = request.optInt("slot", -1)
            val parent = request.optInt("parent", -1)
            val programFds = fds.filterIndexed { i, _ -> i != tableIndex }
            val root = env["DDNATV_ROOT"]?.let(::File)
            val out = client.outputStream
            if (root == null || argv.isEmpty()) {
                out.write("error no guest root or program\n".toByteArray())
                fds.forEach { runCatching { it.close() } }
                return
            }
            val shell = listOf("/usr/bin/bash", "/bin/bash").first { File(root, it.removePrefix("/")).isFile }
            val args = if (file.isNotEmpty()) {
                listOf("-c", "cd -- \"\$1\" 2>/dev/null; a=\$2; f=\$3; shift 3; exec -a \"\$a\" \"\$f\" \"\$@\"",
                    "droiddeck-spawn", cwd, argv[0], file) + argv.drop(1)
            } else {
                listOf("-c", "cd -- \"\$1\" 2>/dev/null; shift; exec \"\$@\"", "droiddeck-spawn", cwd) + argv
            }
            val command = NativeGuest.command(root, shell, args)
            env["DDNATV_REAL_EXE"] = File(root, shell.removePrefix("/")).path
            val exited = CountDownLatch(1)
            var status = -1
            val pid = start(app, command, env.map { (k, v) -> "$k=$v" }, programFds, numbers) { code ->
                status = code
                if (table != null && slot >= 0) reportExit(table, slot, code, parent)
                exited.countDown()
            }
            programFds.forEach { runCatching { it.close() } }
            if (pid <= 0) table?.let { runCatching { it.close() } }
            out.write(if (pid > 0) "pid $pid\n".toByteArray() else "error no worker\n".toByteArray())
            out.flush()
            if (pid > 0 && wait) {
                exited.await()
                out.write("exit $status\n".toByteArray())
                out.flush()
            }
        }
    }

    // Layout of the hook's table (tools/nativeguest/hook/handoff.c): a 64-byte header, then 32-byte
    // entries of int32 child, real, parent, state, status, ...
    private fun reportExit(table: ParcelFileDescriptor, slot: Int, status: Int, parent: Int) {
        runCatching {
            val entry = 64L + slot * 32L
            val word = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.nativeOrder())
            Os.pwrite(table.fileDescriptor, word.putInt(0, if (status < 0) 9 else status).also { it.rewind() }, entry + 16)
            Os.pwrite(table.fileDescriptor, word.putInt(0, 2).also { it.rewind() }, entry + 12)
            if (parent > 0) Process.sendSignal(parent, OsConstants.SIGCHLD)
        }.onFailure { Log.w(TAG, "could not report an exit to the guest's parent", it) }
        runCatching { table.close() }
    }
}
