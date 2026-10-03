package com.droiddeck.launcher.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Flatpak's sandboxes, started for the runtime's bwrap stand-in (droiddeck-bwrap).
 *
 * A Flatpak app runs with its runtime as /usr and itself as /app, which bwrap arranges with mount
 * namespaces Android gives no app. proot can show the same picture, and started here - beside the
 * session's proot rather than inside it - it traces the app once instead of twice. The stand-in
 * sends the sandbox Flatpak asked for (its binds, directories, symlinks and files, in guest paths),
 * this builds it under the cache and starts it, then streams the output, the pid and the exit
 * status back until one side goes away: the stand-in ending takes the sandbox down with it.
 *
 * The socket is abstract, so anything on the device could connect: only this app's own uid - the
 * uid every program in the runtime already runs as - is served.
 */
object BwrapSpawner {
    private const val TAG = "BwrapSpawner"
    const val SOCKET = "com.droiddeck.launcher.bwrap"
    private val seq = AtomicInteger()
    @Volatile private var server: LocalServerSocket? = null

    /** Listens for sandboxes for the rest of the process's life; later calls do nothing. */
    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val socket = try {
            LocalServerSocket(SOCKET)
        } catch (e: IOException) {
            Log.e(TAG, "cannot listen on $SOCKET", e)
            return
        }
        server = socket
        FileUtils.delete(File(app.cacheDir, "bwrap"))
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { Log.e(TAG, "accept", e); break }
                Thread({ serve(app, client) }, "bwrap-${seq.get() + 1}").apply { isDaemon = true; start() }
            }
        }, "bwrap-server").apply { isDaemon = true; start() }
    }

    private fun serve(context: Context, client: LocalSocket) {
        val id = seq.incrementAndGet()
        val dir = File(context.cacheDir, "bwrap/$id")
        client.use {
            val out = DataOutputStream(client.outputStream)
            try {
                val peer = client.peerCredentials
                if (peer.uid != Process.myUid()) {
                    Log.w(TAG, "refused a sandbox for uid ${peer.uid}")
                    return
                }
                val input = DataInputStream(client.inputStream)
                val kind = input.readUnsignedByte()
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                if (kind != 'R'.code) return
                val request = JSONObject(String(body, Charsets.UTF_8))
                val plan = plan(context, request, dir)
                run(context, plan, client, out, id)
            } catch (e: Exception) {
                Log.e(TAG, "sandbox $id", e)
                runCatching {
                    frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                    frame(out, 'X', "1".toByteArray())
                }
            } finally {
                FileUtils.delete(dir)
            }
        }
    }

    /** What proot is started with: the sandbox root, its binds (host to guest), and the program. */
    class Plan(val root: File, val binds: List<Bind>, val cwd: String, val argv: List<String>, val env: Map<String, String>)

    data class Bind(val host: String, val guest: String) {
        val spec: String get() = if (host == guest) host else "$host:$guest"

        companion object {
            fun parse(spec: String): Bind {
                val colon = spec.indexOf(':')
                return if (colon < 0) Bind(spec, spec) else Bind(spec.substring(0, colon), spec.substring(colon + 1))
            }
        }
    }

    /** True when [path] is [dir] or inside it. */
    internal fun under(path: String, dir: String): Boolean =
        dir == "/" || path == dir || path.startsWith(if (dir.endsWith("/")) dir else "$dir/")

    /**
     * Where a path the session's programs see lives on the host: through the session's own binds
     * (the most specific one), or else inside the rootfs.
     */
    internal fun hostPath(sessionBinds: List<Bind>, rootfs: String, guest: String): String {
        val bind = sessionBinds.filter { under(guest, it.guest) }.maxByOrNull { it.guest.length }
            ?: return rootfs.trimEnd('/') + guest
        val rest = guest.substring(bind.guest.length).trimStart('/')
        return if (rest.isEmpty()) bind.host else bind.host.trimEnd('/') + "/" + rest
    }

    /**
     * Guest [src] bound at [dest]: the bind itself, then every session bind below [src] moved
     * under [dest] - /dev brings the session's /dev/shm and GPU node, /proc its stand-ins for what
     * Android keeps apps from reading.
     */
    internal fun bindsFor(sessionBinds: List<Bind>, rootfs: String, src: String, dest: String): List<Bind> {
        val out = ArrayList<Bind>()
        out.add(Bind(hostPath(sessionBinds, rootfs, src), dest))
        for (b in sessionBinds.sortedBy { it.guest.length }) {
            if (b.guest != src && under(b.guest, src)) {
                out.add(Bind(b.host, (dest.trimEnd('/') + "/" + b.guest.substring(src.length).trimStart('/'))))
            }
        }
        return out
    }

    internal fun plan(context: Context, request: JSONObject, dir: File): Plan {
        val rootfs = LinuxRuntime.rootDir(context).path
        val session = LinuxRuntime.lastBinds(context).map { Bind.parse(it) }
        val root = File(dir, "root").apply { mkdirs() }
        val binds = ArrayList<Bind>()
        var scratch = 0
        fun addBind(b: Bind) {
            binds.removeAll { it.guest == b.guest }
            binds.add(b)
        }
        fun coveringBind(dest: String) = binds.filter { under(dest, it.guest) }.maxByOrNull { it.guest.length }
        fun newScratch(): File = File(dir, "x${scratch++}")
        /**
         * Where [dest] can be created on the host: inside the sandbox root, or inside a directory
         * of ours bound into it (a --tmpfs under the runtime's /usr, which Flatpak fills with the
         * GL extension's links). Null inside anything else bound in - the runtime itself, a host
         * directory - which must not be written to.
         */
        fun writable(dest: String): File? {
            val cover = coveringBind(dest) ?: return inRoot(root, dest)
            if (!under(cover.host, dir.path)) return null
            return inRoot(File(cover.host), dest.substring(cover.guest.length))
        }
        val ops = request.getJSONArray("ops")
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            val dest = op.optString("dest")
            when (op.getString("t")) {
                "bind" -> {
                    val src = op.getString("src")
                    val all = bindsFor(session, rootfs, src, dest)
                    if (!File(all.first().host).exists()) {
                        if (!op.optBoolean("try")) Log.w(TAG, "sandbox source $src is missing; left out")
                        continue
                    }
                    all.forEach { addBind(it) }
                }
                "proc" -> bindsFor(session, rootfs, "/proc", dest).forEach { addBind(it) }
                "dev" -> bindsFor(session, rootfs, "/dev", dest).forEach { addBind(it) }
                "dir" -> {
                    val perms = op.optInt("perms", 0x1ed)
                    val here = writable(dest)
                    when {
                        op.optBoolean("empty") && coveringBind(dest) != null ->
                            addBind(Bind(newScratch().apply { mkdirs(); chmod(this, perms) }.path, dest))
                        here != null && (here.isDirectory || here.mkdirs()) -> chmod(here, perms)
                        File(hostPath(binds, root.path, dest)).isDirectory -> {}
                        else -> addBind(Bind(newScratch().apply { mkdirs(); chmod(this, perms) }.path, dest))
                    }
                }
                "file" -> {
                    val data = Base64.decode(op.getString("data"), Base64.DEFAULT)
                    val here = writable(dest)
                    val f = if (here != null && here.parentFile?.let { it.isDirectory || it.mkdirs() } == true) here
                            else newScratch().also { addBind(Bind(it.path, dest)) }
                    f.writeBytes(data)
                    chmod(f, op.optInt("perms", 0x1b6))
                }
                "symlink" -> {
                    val target = op.getString("target")
                    val link = writable(dest)
                    if (link != null) {
                        link.parentFile?.mkdirs()
                        if (!Files.isSymbolicLink(link.toPath()) && !link.exists()) Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
                    } else {
                        // A link inside the runtime's own tree cannot be made; what it points at is
                        // bound in its place.
                        val abs = if (target.startsWith("/")) target else normalize(dest.substringBeforeLast('/') + "/" + target)
                        val host = hostPath(binds, root.path, abs)
                        if (File(host).exists()) addBind(Bind(host, dest)) else Log.w(TAG, "symlink $dest -> $target: nothing there; left out")
                    }
                }
            }
        }
        if (binds.any { it.guest == "/proc" }) genericCpuinfo(context)?.let { addBind(Bind(it.path, "/proc/cpuinfo")) }
        val argv = request.getJSONArray("argv").let { a -> (0 until a.length()).map { a.getString(it) } }
        val env = LinkedHashMap<String, String>()
        request.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.getString(k) } }
        if (binds.any { it.guest == "/dev" }) gpu(rootfs, root, env)?.forEach { addBind(it) }
        preload(session, rootfs, env).forEach { addBind(it) }
        browserSandboxes(env)
        return Plan(root, binds, request.optString("cwd", "/").ifEmpty { "/" }, argv, env)
    }

    /** Where a sandbox finds the runtime's own GPU driver. */
    private const val GPU_DIR = "/run/droiddeck-gpu"

    /**
     * The GPU, for an app that is given /dev. Flathub's Mesa has Turnip, but built for the DRM
     * kernel driver: Adreno on Android is KGSL, so apps fell back to drawing on the CPU. The
     * runtime's own Turnip has the KGSL backend and needs nothing the Freedesktop runtime lacks
     * but three libraries, which come with it; Mesa's GL then runs on it through Zink. An app's
     * own choice of driver (VK_ICD_FILENAMES, MESA_LOADER_DRIVER_OVERRIDE) is left alone.
     */
    private fun gpu(rootfs: String, root: File, env: MutableMap<String, String>): List<Bind>? {
        val lib = File(rootfs, "usr/lib")
        val driver = File(lib, "libvulkan_freedreno.so")
        if (!driver.isFile || !File("/dev/kgsl-3d0").exists()) return null
        if (env.containsKey("VK_ICD_FILENAMES") || env.containsKey("VK_DRIVER_FILES")) return null
        // A window on the desktop while labwc composites with pixman gets shared-memory buffers
        // only; Zink cannot present there, so such an app keeps Flathub's own Mesa (llvmpipe).
        // From the desktop, games go through droiddeck-gpu's gamescope and do get the GPU.
        if (env["WLR_RENDERER"] == "pixman" && env["GAMESCOPE_WAYLAND_DISPLAY"] == null) return null
        val binds = ArrayList<Bind>()
        for (name in listOf("libvulkan_freedreno.so", "libdisplay-info.so.3", "libSPIRV-Tools.so", "libSPIRV-Tools-opt.so")) {
            val f = File(lib, name)
            if (!f.exists()) { Log.w(TAG, "gpu: the runtime has no $name; apps draw on the CPU"); return null }
            binds.add(Bind(f.canonicalPath, "$GPU_DIR/$name"))
        }
        // Beside the sandbox root, in the sandbox's own directory: removed with it.
        val icd = File(root.parentFile, "freedreno_icd.json")
        icd.writeText("{\"file_format_version\": \"1.0.0\", \"ICD\": {\"library_path\": \"$GPU_DIR/libvulkan_freedreno.so\", \"api_version\": \"1.4.0\"}}\n")
        binds.add(Bind(icd.path, "$GPU_DIR/freedreno_icd.json"))
        env["VK_DRIVER_FILES"] = "$GPU_DIR/freedreno_icd.json"
        env["VK_ICD_FILENAMES"] = "$GPU_DIR/freedreno_icd.json"
        if (!env.containsKey("MESA_LOADER_DRIVER_OVERRIDE") && !env.containsKey("GALLIUM_DRIVER")) {
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["GALLIUM_DRIVER"] = "zink"
        }
        // The driver's own libraries, found beside it; nothing else is in that directory.
        env["LD_LIBRARY_PATH"] = listOf(env["LD_LIBRARY_PATH"], GPU_DIR).filter { !it.isNullOrEmpty() }.joinToString(":")
        return binds
    }

    /**
     * What every program in the session preloads (SessionFiles writes /etc/ld.so.preload): the
     * session shim - SysV shared memory for X11, the network calls Android refuses - and the
     * controller reader. A sandbox's /etc is the runtime's, so the same libraries go in through
     * LD_PRELOAD instead, with the directory the controllers' rings live in; without them an app
     * saw no gamepad. Both are built against an older glibc than any Flathub runtime has.
     */
    private fun preload(session: List<Bind>, rootfs: String, env: MutableMap<String, String>): List<Bind> {
        val libs = FileUtils.readString(File(rootfs, "etc/ld.so.preload"))?.lines()
            ?.map { it.trim() }?.filter { it.startsWith("/") && File(rootfs + it).isFile } ?: return emptyList()
        if (libs.isEmpty()) return emptyList()
        val binds = ArrayList<Bind>()
        val paths = libs.map { lib -> "$PRELOAD_DIR/${lib.substringAfterLast('/')}".also { binds.add(Bind(rootfs + lib, it)) } }
        env["LD_PRELOAD"] = (paths + listOfNotNull(env["LD_PRELOAD"]?.takeIf { it.isNotBlank() })).joinToString(":")
        // Not through ZYPAK_LD_PRELOAD to Chromium's children: the session shim's stat hooks then
        // hide the setuid bit zypak fakes on chrome-sandbox, and Chromium refuses to start.
        // The controllers: FAKE_EVDEV_DIR is <session>/dev/input, the rings beside it in <session>/dev.
        env["FAKE_EVDEV_DIR"]?.let { File(it).parent }?.let { dev ->
            binds.addAll(bindsFor(session, rootfs, dev, dev))
        }
        return binds
    }

    private const val PRELOAD_DIR = "/run/droiddeck-preload"

    /**
     * Browsers sandbox their own children, and neither way works in here. Firefox wants
     * namespaces and seccomp from the kernel; its content processes died without them (the
     * desktop sets the same for the runtime's own Firefox). Chromium and Electron apps start
     * their zygote through Flatpak's portal (zypak's spawn strategy), and the portal refuses:
     * it knows a Flatpak by /proc/<pid>/root/.flatpak-info, and a proot sandbox's root is the
     * host's. zypak's older strategy mimics the zygote inside the app's own sandbox instead.
     */
    private fun browserSandboxes(env: MutableMap<String, String>) {
        env.putIfAbsent("ZYPAK_ZYGOTE_STRATEGY_SPAWN", "0")
        // Qt WebEngine is Chromium without zypak: it sets up its own namespace sandbox.
        env.putIfAbsent("QTWEBENGINE_DISABLE_SANDBOX", "1")
        // Chromium on Wayland asks the render node the compositor names for its DRM version;
        // KGSL has none, and the GPU process aborts until Chromium gives up ("GPU process isn't
        // usable"). Electron picks Wayland from XDG_SESSION_TYPE; with an X server there (every
        // session has Xwayland) it stays on X11, which works. GTK and Qt go by their own settings.
        if (env.containsKey("DISPLAY")) env["XDG_SESSION_TYPE"] = "x11"
        for (name in listOf("MOZ_DISABLE_CONTENT_SANDBOX", "MOZ_DISABLE_GMP_SANDBOX", "MOZ_DISABLE_RDD_SANDBOX",
                            "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX", "MOZ_DISABLE_UTILITY_SANDBOX")) {
            env.putIfAbsent(name, "1")
        }
    }

    /**
     * /proc/cpuinfo without the cores' names. Snapdragon's ARMv9 cores (Cortex-A510/A715/X3) have
     * SVE2 by architecture, and LLVM - llvmpipe's JIT in Flathub's Mesa, which is what apps draw
     * with here - takes the CPU part to mean it may use it. Qualcomm leaves SVE off, so the first
     * shader compiled killed the app with SIGILL. Without a part number LLVM targets generic
     * ARMv8 plus the "Features" line, which lists what the kernel really enabled.
     */
    private fun genericCpuinfo(context: Context): File? = try {
        val out = File(context.cacheDir, "cpuinfo-generic")
        val text = File("/proc/cpuinfo").readLines()
            .filterNot { it.startsWith("CPU implementer") || it.startsWith("CPU part") || it.startsWith("CPU variant") || it.startsWith("CPU revision") }
            .joinToString("\n", postfix = "\n")
        // Sandboxes start side by side; one must never read another's half-written copy.
        if (!out.isFile || out.readText() != text) {
            val staged = File.createTempFile("cpuinfo", null, context.cacheDir)
            staged.writeText(text)
            if (!staged.renameTo(out)) staged.delete()
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "cpuinfo", e); null
    }

    /** [path] with "." and ".." resolved lexically. */
    internal fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(part)
        }
        return "/" + out.joinToString("/")
    }

    /**
     * [guest] inside the sandbox root, or null when the way there crosses a symlink: one of
     * Flatpak's (var/run -> ../run) may point anywhere once followed on the host.
     */
    private fun inRoot(root: File, guest: String): File? {
        var f = root
        for (part in guest.split('/').filter { it.isNotEmpty() }) {
            if (part == "..") return null
            f = File(f, part)
            if (Files.isSymbolicLink(f.toPath())) return null
        }
        return f
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (e: Exception) { /* the default mode serves */ }
    }

    private fun run(context: Context, plan: Plan, client: LocalSocket, out: DataOutputStream, id: Int) {
        val cmd = LinuxRuntime.prootPrefix(context, plan.root, plan.cwd)
        // DROIDDECK_PROOT_VERBOSE=N in the app's environment (flatpak run --env=...) has proot
        // say what it does, to logcat in full: for a sandbox that dies without a word.
        val verbose = plan.env["DROIDDECK_PROOT_VERBOSE"]?.toIntOrNull()
        if (verbose != null) { cmd.add(1, "-v"); cmd.add(2, verbose.toString()) }
        plan.binds.forEach { cmd.add("-b"); cmd.add(it.spec) }
        // proot hands its own environment on; the sandbox gets exactly what Flatpak asked for.
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        plan.env.forEach { (k, v) -> cmd.add("$k=$v") }
        cmd.addAll(plan.argv)
        val builder = ProcessBuilder(cmd).redirectErrorStream(true).directory(plan.root)
        builder.environment().apply {
            clear()
            put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
            put("PROOT_TMP_DIR", context.cacheDir.path)
            if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        val process = builder.start()
        val pid = HostProcess.pidOf(process)
        // A sandbox lives exactly as long as its bwrap stand-in, which ends it; a session starting
        // meanwhile (OrphanReaper) must not take it - an install's extra-data step, say.
        com.droiddeck.launcher.session.OrphanReaper.keep(pid)
        Log.i(TAG, "sandbox $id pid $pid: ${plan.argv.joinToString(" ")} (${plan.binds.size} binds)")
        synchronized(out) { frame(out, 'P', pid.toString().toByteArray()) }
        // The stand-in going away (Flatpak killed, the session over) ends the sandbox.
        Thread({
            try { while (client.inputStream.read() >= 0) { /* nothing is sent after the request */ } }
            catch (e: IOException) { /* closed */ }
            if (process.isAlive) {
                Log.i(TAG, "sandbox $id: its bwrap is gone; stopping it")
                process.destroy()
            }
        }, "bwrap-$id-watch").apply { isDaemon = true; start() }
        val buffer = ByteArray(16384)
        // The start of what the sandbox says goes to logcat too: a sandbox that fails at once
        // (proot refusing a bind, a missing library) says why there even when nothing reads it.
        var logged = 0
        process.inputStream.use { stream ->
            while (true) {
                val n = try { stream.read(buffer) } catch (e: IOException) { -1 }
                if (n < 0) break
                if (logged < 4096 || verbose != null) {
                    String(buffer, 0, if (verbose != null) n else minOf(n, 4096 - logged)).lines().filter { it.isNotBlank() }
                        .forEach { Log.i(TAG, "sandbox $id: $it") }
                    logged += n
                }
                try { synchronized(out) { frame(out, 'O', buffer.copyOf(n)) } } catch (e: IOException) { break }
            }
        }
        val status = process.waitFor()
        com.droiddeck.launcher.session.OrphanReaper.release(pid)
        Log.i(TAG, "sandbox $id exited $status")
        runCatching { synchronized(out) { frame(out, 'X', status.toString().toByteArray()) } }
    }

    private fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }
}
