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
import android.system.StructPollfd
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
            val go = data.readFileDescriptor()
            reply?.writeInt(Process.myPid())
            Handler(Looper.getMainLooper()).post {
                // The app closes its end once it has the reply: an exec before then would end
                // this process with the reply undelivered.
                runCatching {
                    val poll = StructPollfd().apply { fd = go.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                    Os.poll(arrayOf(poll), GO_TIMEOUT_MS)
                }
                go.close()
                exec(argv, env, fds, targets)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun exec(argv: Array<String>, env: Array<String>, fds: List<ParcelFileDescriptor?>, targets: IntArray) {
        try {
            val top = targets.maxOrNull() ?: 2
            val (binderTop, binderFd) = keepBinderOpen(maxOf(BINDER_FDS, top + 1))
            // Out of the way first, so that placing one fd cannot overwrite another not yet placed.
            val above = maxOf(MOVED_FDS, top + 1, binderTop + 1)
            val moved = fds.map { pfd -> pfd?.let { Os.fcntlInt(it.fileDescriptor, OsConstants.F_DUPFD, above) } }
            moved.forEachIndexed { i, fd ->
                if (fd == null || i >= targets.size) return@forEachIndexed
                val source = ParcelFileDescriptor.adoptFd(fd).also { kept.add(it) }.fileDescriptor
                Os.dup2(source, targets[i])
                Os.fcntlInt(source, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC)
            }
            // A session of its own: in zygote's process group, a program signalling its group
            // (kill(0, sig)) would reach every process of the app.
            runCatching { Os.setsid() }
            // The native hook closes the binder fds in every other process (hook/handoff.c).
            val fill = { arg: String -> arg.replace(BINDER_FD_TOKEN, binderFd.toString()) }
            // This thread blocks SIGQUIT, SIGUSR1 and SIGPIPE (Android's runtime), and an exec keeps
            // that: the program's hook sets DDH_SIGMASK instead (none blocked, unless the request says).
            val mask = if (env.any { it.startsWith("DDH_SIGMASK=") }) emptyList() else listOf("DDH_SIGMASK=0")
            Os.execve(argv[0], argv.map(fill).toTypedArray(),
                env.filterNot { it.startsWith("DDH_WORKER_PID=") }.map(fill).toTypedArray() + mask +
                    "DDH_WORKER_PID=${Process.myPid()}")
        } catch (e: ErrnoException) {
            Log.e(TAG, "could not exec ${argv.firstOrNull()}", e)
        }
        Process.killProcess(Process.myPid())
    }

    /**
     * Binder fds stay open across the exec, at [from] or above, where no fd of the program goes.
     * Returns the highest fd used and the one on /dev/binder, whose closing Android takes for
     * the death of this process.
     */
    private fun keepBinderOpen(from: Int): Pair<Int, Int> {
        var highest = from - 1
        var binder = -1
        File("/proc/self/fd").listFiles()?.forEach { link ->
            val target = runCatching { Os.readlink(link.path) }.getOrNull() ?: return@forEach
            if (target !in BINDER_DEVICES && !target.startsWith("/dev/binderfs/")) return@forEach
            // A copy, not adoptFd(): some of these fds are owned (fdsan) by a ParcelFileDescriptor.
            val copy = runCatching { ParcelFileDescriptor.fromFd(link.name.toIntOrNull() ?: return@forEach) }.getOrNull()
                ?: return@forEach
            val kept = Os.fcntlInt(copy.fileDescriptor, OsConstants.F_DUPFD, from)
            highest = maxOf(highest, kept)
            if (target.substringAfterLast('/') == "binder") binder = kept
            copy.close()
        }
        return highest to binder
    }

    companion object {
        const val RUN = IBinder.FIRST_CALL_TRANSACTION
        /** In a program's arguments or environment, replaced by the number of its kept binder fd. */
        const val BINDER_FD_TOKEN = "@DDH_WORKER_BINDER_FD@"
        private const val GO_TIMEOUT_MS = 2000
        private val BINDER_DEVICES = setOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder")
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

    private class Slot(val index: Int) {
        lateinit var connection: ServiceConnection
        val connected = CountDownLatch(1)
        @Volatile var service: IBinder? = null
        @Volatile var pid = -1
        @Volatile var guest = false
        /** The session it runs a program for ([serve]'s number). */
        @Volatile var session = 0
        @Volatile var onExit: ((Int) -> Unit)? = null
        /** Set once its program was handed over: only then does its end count as the program's. */
        @Volatile var started = false
    }

    private val slots = arrayOfNulls<Slot>(classes.size)
    // Workers bound ahead of need while a session runs: a zygote fork and the bind take about
    // 60-100 ms, which every program the session starts would otherwise wait for.
    private val warm = ArrayDeque<Slot>()
    @Volatile private var warmTarget = 0
    // Numbers the sessions: an old session's teardown can still be running when the next one starts.
    @Volatile private var session = 0
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val WARM_WORKERS = 2
    private val connections = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "guest-worker-binder").apply { isDaemon = true } }
    @Volatile private var server: Thread? = null

    /** The app, once [attach] or [serve] ran in its main process; null before. */
    @Volatile var context: Context? = null
        private set

    /** Whether the session runs its programs in workers: the native userland, and not turned off. */
    fun enabled(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && com.droiddeck.launcher.session.SessionPrefs.childProcessHandoff(context) &&
            NativeGuest.enabled(context)

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
              onExit: (Int) -> Unit): Int = start(context, argv, env, fds, targets, false, onExit)

    /** [guest]: one of the session's guest programs, which [guestPids] lists and [stopGuests] ends. */
    private fun start(context: Context, argv: List<String>, env: List<String>, fds: List<ParcelFileDescriptor>, targets: IntArray,
                      guest: Boolean, onExit: (Int) -> Unit): Int {
        val app = context.applicationContext
        val slot = takeWarm() ?: bind(app)?.takeIf { fresh ->
            fresh.connected.await(10, TimeUnit.SECONDS).also { if (!it) release(app, fresh) }
        } ?: run {
            Log.w(TAG, "no worker started for ${argv.firstOrNull()}")
            return -1
        }
        slot.guest = guest
        slot.session = session
        slot.onExit = onExit
        refill(app)
        // The worker execs once this pipe closes: after the reply has reached us, so that its exec
        // does not cut the reply off, and after the slot is marked started.
        var go: Array<ParcelFileDescriptor>? = null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            go = ParcelFileDescriptor.createPipe()
            data.writeStringArray(argv.toTypedArray())
            data.writeStringArray(env.toTypedArray())
            data.writeInt(fds.size)
            fds.forEach { data.writeFileDescriptor(it.fileDescriptor) }
            data.writeIntArray(targets)
            data.writeFileDescriptor(go[0].fileDescriptor)
            slot.service!!.transact(GuestWorker.RUN, data, reply, 0)
            val pid = reply.readInt()
            val live = synchronized(slots) {
                (slots[slot.index] === slot).also { if (it) { slot.pid = pid; slot.started = true } }
            }
            if (!live) {
                Log.w(TAG, "worker ${slot.index} ended before running ${argv.firstOrNull()}")
                return -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "worker ${slot.index} refused ${argv.firstOrNull()}", e)
            release(app, slot)
            return -1
        } finally {
            data.recycle()
            reply.recycle()
            go?.forEach { runCatching { it.close() } }
        }
        Log.i(TAG, "worker ${slot.index} (pid ${slot.pid}) runs ${argv.lastOrNull { !it.startsWith("-") }}")
        return slot.pid
    }

    /** Binds a free worker; its [Slot.connected] opens once it is up. Null when all are in use. */
    private fun bind(app: Context): Slot? {
        val slot = synchronized(slots) {
            val index = slots.indexOfFirst { it == null }
            if (index < 0) {
                Log.w(TAG, "all ${slots.size} workers are busy")
                return null
            }
            Slot(index).also { slots[index] = it }
        }
        slot.connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                slot.service = binder
                slot.connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = release(app, slot)

            override fun onBindingDied(name: ComponentName) = release(app, slot)
        }
        // Callbacks off the main thread: start() waits for onServiceConnected, and a caller holding a
        // lock the main thread is waiting for (PulseAudio's launch) would otherwise wait out the timeout.
        if (!app.bindService(Intent(app, classes[slot.index]), Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                connections, slot.connection)) {
            release(app, slot)
            return null
        }
        return slot
    }

    private fun takeWarm(): Slot? = synchronized(slots) {
        while (warm.isNotEmpty()) {
            val slot = warm.removeFirst()
            if (slots[slot.index] === slot && slot.service?.isBinderAlive == true) return slot
        }
        null
    }

    /** Brings the warm workers back up to [warmTarget], one at a time, off the caller's thread. */
    private fun refill(app: Context) {
        if (warmTarget == 0 || !warming.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val missing = warmTarget - synchronized(slots) { warm.size }
                    if (missing <= 0) break
                    // all at once: the zygote forks them in parallel
                    val fresh = (1..missing).mapNotNull { bind(app) }
                    if (fresh.isEmpty()) break
                    for (slot in fresh) {
                        if (!slot.connected.await(10, TimeUnit.SECONDS)) {
                            release(app, slot)
                            continue
                        }
                        synchronized(slots) { if (slots[slot.index] === slot && warmTarget > 0) warm.addLast(slot) }
                        if (warmTarget == 0) release(app, slot)
                    }
                }
            } finally {
                warming.set(false)
            }
        }, "guest-worker-warm").apply { isDaemon = true; start() }
    }

    private fun release(app: Context, slot: Slot) {
        val removed = synchronized(slots) {
            warm.remove(slot)
            (slots[slot.index] === slot).also { if (it) slots[slot.index] = null }
        }
        if (!removed) return
        runCatching { app.unbindService(slot.connection) }
        val onExit = slot.onExit
        if (slot.started && onExit != null) Thread({ onExit(exitStatus(app, slot.pid)) }, "guest-worker-exit").start()
    }

    /** The worker's exit as a wait status (exit code << 8, or the signal), -1 when unknown. */
    private fun exitStatus(app: Context, pid: Int): Int {
        if (pid <= 0 || android.os.Build.VERSION.SDK_INT < 30) return -1
        val am = app.getSystemService(ActivityManager::class.java)
        repeat(20) {
            am.getHistoricalProcessExitReasons(app.packageName, pid, 1).firstOrNull()?.let {
                // an exit code only when the process exited itself; a signal number otherwise (a native crash)
                return if (it.reason == android.app.ApplicationExitInfo.REASON_EXIT_SELF) (it.status and 0xff) shl 8
                else if (it.status in 1..64) it.status and 0x7f
                else (it.status and 0xff) shl 8
            }
            Thread.sleep(50)
        }
        return -1
    }

    /** The pids of session [of]'s guest programs running in workers. */
    fun guestPids(of: Int = session): List<Int> =
        synchronized(slots) { slots.mapNotNull { s -> s?.takeIf { it.guest && it.session == of }?.pid?.takeIf { it > 0 } } }

    /** Ends session [of]'s guest programs that are still running, and, for the latest session, the warm workers. */
    fun stopGuests(of: Int = session) {
        if (of == session) {
            warmTarget = 0
            context?.let { app -> synchronized(slots) { warm.toList().also { warm.clear() } }.forEach { release(app, it) } }
        }
        val pids = guestPids(of)
        if (pids.isEmpty()) return
        pids.forEach { Process.sendSignal(it, OsConstants.SIGTERM) }
        Thread.sleep(500)
        pids.forEach { Process.killProcess(it) }
    }

    /** Listens for `droiddeck-spawn` requests from the session's guests. */
    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /** Starts a session's use of the workers (and the spawn socket, once); returns its number. */
    fun serve(context: Context): Int {
        val app = context.applicationContext
        this.context = app
        val number = ++session
        warmTarget = WARM_WORKERS
        refill(app)
        synchronized(this) {
            if (server != null) return number
            server = Thread({
                val socket = try {
                    LocalServerSocket(socketName(app))
                } catch (e: Exception) {
                    Log.e(TAG, "no spawn socket", e)
                    synchronized(this) { server = null }
                    return@Thread
                }
                while (true) {
                    val client = runCatching { socket.accept() }.getOrNull()
                    if (client == null) {
                        Thread.sleep(100)  // out of fds, for one: do not spin
                        continue
                    }
                    Thread({
                        try {
                            handle(app, client)
                        } catch (e: Throwable) {
                            Log.w(TAG, "spawn request failed", e)
                        }
                    }, "guest-spawn").start()
                }
            }, "guest-spawn-server").apply { isDaemon = true; start() }
        }
        return number
    }

    private fun handle(app: Context, client: LocalSocket) {
        client.use {
            if (client.peerCredentials.uid != Process.myUid()) return
            val input = DataInputStream(client.inputStream)
            val payload = ByteArray(input.readInt()).also { input.readFully(it) }
            val request = JSONObject(String(payload))
            val fds = ArrayList<ParcelFileDescriptor>()
            fun take() {
                val received = client.ancillaryFileDescriptors.orEmpty()
                received.forEach { fds.add(ParcelFileDescriptor.dup(it)) }
                received.forEach { runCatching { Os.close(it) } }
            }
            if (request.has("fdchunks")) {
                // The hook sends its fds after the request, a chunk with each byte (hook/handoff.c)
                val total = (request.optJSONArray("fds")?.length() ?: 0) + (if (request.has("table")) 1 else 0)
                while (fds.size < total) {
                    if (input.read() < 0) throw java.io.EOFException("fds ended at ${fds.size} of $total")
                    take()
                }
            } else {
                take()
            }
            // Until start() has them: closed on every way out before that
            var handedOver = false
            try {
            val argv = request.getJSONArray("argv").let { a -> List(a.length()) { a.getString(it) } }
            val env = LinkedHashMap<String, String>()
            request.optJSONObject("env")?.let { o -> o.keys().forEach { env[it] = o.getString(it) } }
            request.optJSONArray("envlist")?.let { a ->
                for (i in 0 until a.length()) a.getString(i).split('=', limit = 2).let { if (it.size == 2) env[it[0]] = it[1] }
            }
            // What the hook told the program that asked (it may run in a worker itself) is not this one's
            env.keys.removeAll { it.startsWith("DDH_") }
            val cwd = request.optString("cwd", "/")
            val wait = request.optBoolean("wait", false)
            // A path without a slash names a file in the cwd, as execve() takes it, not one on PATH
            val file = request.optString("file", "").let { if (it.isNotEmpty() && '/' !in it) "./$it" else it }
            // The handoff table (the hook's) comes last among the fds; the rest go to these numbers.
            val tableIndex = request.optInt("table", -1)
            val numbers = request.optJSONArray("fds")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(3) { it }
            val table = fds.getOrNull(tableIndex)?.takeIf { pfd ->
                runCatching { Os.readlink("/proc/self/fd/${pfd.fd}") }.getOrNull()?.contains("ddnatv-handoff") == true
            }
            val slot = request.optInt("slot", -1)
            val parent = request.optInt("parent", -1)
            var programFds = fds.filterIndexed { i, _ -> i != tableIndex }
            var targets = numbers
            if (table != null && slot >= 0) {
                // The program reports its own exit status into the table (hook/handoff.c)
                val tableFd = (numbers.maxOrNull() ?: 2) + 1
                programFds = programFds + table
                targets = numbers + tableFd
                env["DDH_TABLE"] = "$tableFd:$slot:$parent"
            }
            val root = env["DDNATV_ROOT"]?.let(::File)
            val out = client.outputStream
            if (root == null || argv.isEmpty()) {
                out.write("error no guest root or program\n".toByteArray())
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
            // The hook restores these in the program (tools/nativeguest/hook/handoff.c).
            if (request.has("sigign")) env["DDH_SIGIGN"] = request.getLong("sigign").toString()
            if (request.has("nofile")) env["DDH_NOFILE"] = request.getLong("nofile").toString()
            if (request.has("umask")) env["DDH_UMASK"] = request.getLong("umask").toString()
            if (request.has("pgid")) env["DDH_PGID"] = request.getLong("pgid").toString()
            if (request.has("anchor")) env["DDH_ANCHOR"] = request.getLong("anchor").toString()
            env["DDH_SIGMASK"] = request.optLong("sigmask", 0L).toString()
            val child = request.optInt("child", -1)
            val exited = CountDownLatch(1)
            var status = -1
            var worker = -1
            val pid = start(app, command, env.map { (k, v) -> "$k=$v" }, programFds, targets, true) { code ->
                status = code
                if (table != null && slot >= 0) reportExit(table, slot, code, parent, child, worker)
                exited.countDown()
            }
            worker = pid
            handedOver = true
            programFds.filter { it !== table }.forEach { runCatching { it.close() } }
            if (pid <= 0) table?.let { runCatching { it.close() } }
            out.write(if (pid > 0) "pid $pid\n".toByteArray() else "error no worker\n".toByteArray())
            out.flush()
            if (pid > 0 && wait) {
                exited.await()
                // droiddeck-spawn exits with this: an exit code, or 128 plus the signal
                val code = if (status < 0) 1 else if (status and 0x7f == 0) (status shr 8) and 0xff else 128 + (status and 0x7f)
                out.write("exit $code\n".toByteArray())
                out.flush()
            }
            } finally {
                if (!handedOver) fds.forEach { runCatching { it.close() } }
            }
        }
    }

    // Layout of the hook's table (tools/nativeguest/hook/handoff.c): a 64-byte header, then 32-byte
    // entries of int32 child, real, parent, state, status, ...
    // Written only while the entry is still this program's (state CLAIMED or HANDED, its child, parent
    // and worker): the parent may have reaped it and a later handoff taken the entry over meanwhile.
    private fun reportExit(table: ParcelFileDescriptor, slot: Int, status: Int, parent: Int, child: Int, worker: Int) {
        runCatching {
            val entry = 64L + slot * 32L
            val head = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.nativeOrder())
            Os.pread(table.fileDescriptor, head, entry)
            val real = head.getInt(4)
            val state = head.getInt(12)
            if (head.getInt(0) != child || head.getInt(8) != parent || (real != 0 && real != worker) || (state != 1 && state != 3))
                return@runCatching
            val word = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.nativeOrder())
            // An exit Android has no record of is reported as a failure, not as a signal.
            Os.pwrite(table.fileDescriptor, word.putInt(0, if (status < 0) 1 shl 8 else status).also { it.rewind() }, entry + 16)
            Os.pwrite(table.fileDescriptor, word.putInt(0, 2).also { it.rewind() }, entry + 12)
            if (parent > 0) Process.sendSignal(parent, OsConstants.SIGCHLD)
        }.onFailure { Log.w(TAG, "could not report an exit to the guest's parent", it) }
        runCatching { table.close() }
    }
}
