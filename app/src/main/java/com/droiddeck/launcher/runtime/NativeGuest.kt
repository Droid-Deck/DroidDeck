package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.nio.file.Files

/**
 * Starts guest programs directly on the Android kernel, with no proot: the glibc loader of the
 * rootfs runs the program and [HOOK] translates the guest's absolute paths, execs and the system
 * calls Android's seccomp policy blocks, from inside the process.
 */
object NativeGuest {
    private const val TAG = "NativeGuest"
    const val HOOK = "usr/lib/libddnatv-hook.so"
    private val LIB_DIRS = listOf("usr/lib", "usr/lib/aarch64-linux-gnu", "usr/local/lib", "usr/lib/systemd")

    /** Libraries every native program loads before the hook, as /etc/ld.so.preload does for proot. */
    fun preloadList(root: File): List<String> {
        val listed = File(root, "etc/droiddeck/native-preload").takeIf { it.isFile }?.readLines().orEmpty()
        return listed.map { it.trim() }.filter { it.startsWith("/") && File(root, it.removePrefix("/")).isFile }
    }

    fun enabled(context: Context): Boolean =
        SessionPrefs.nativeUserland(context) && available(LinuxRuntime.rootDir(context))

    /**
     * Development switch: a guest path in etc/droiddeck/seccomp-sim names a helper that applies Android's
     * app seccomp filter before the program starts, for devices (eng builds) that run apps without one.
     */
    fun simulator(root: File): File? = File(root, "etc/droiddeck/seccomp-sim").takeIf { it.isFile }
        ?.readText()?.trim()?.takeIf { it.startsWith("/") }?.let { File(root, it.removePrefix("/")) }?.takeIf { it.isFile }

    fun hook(root: File): File = File(root, HOOK)

    fun loader(root: File): File? =
        listOf("usr/lib/ld-linux-aarch64.so.1", "lib/ld-linux-aarch64.so.1").map { File(root, it) }.firstOrNull { it.exists() }

    fun available(root: File): Boolean = hook(root).isFile && loader(root) != null

    fun libraryPath(root: File): String = LIB_DIRS.joinToString(":") { File(root, it).path }

    /** Environment every native process starts with; a guest `env -i` is re-seeded by the hook. */
    fun environment(root: File, preload: List<String> = emptyList()): Map<String, String> = linkedMapOf(
        "DDNATV_ROOT" to root.path,
        "LD_LIBRARY_PATH" to libraryPath(root),
        "LD_PRELOAD" to (preload.map { File(root, it.removePrefix("/")).path } + hook(root).path).joinToString(":"),
    )

    /**
     * The host command line for the guest program at [program] (an absolute guest path to a real
     * file, not a symlink).
     */
    fun command(root: File, program: String, args: List<String>): List<String> {
        val loader = checkNotNull(loader(root)) { "no glibc loader in ${root.path}" }
        val host = File(root, program.removePrefix("/"))
        val launch = listOf(loader.path, "--argv0", host.name, host.path) + args
        return simulator(root)?.let { listOf(it.path) + launch } ?: launch
    }

    /** The environment of the session process: the hook's root, the mounts proot used to make, the preload list. */
    fun sessionEnvironment(root: File, binds: List<String>, hostname: String? = null): Map<String, String> {
        val preload = preloadList(root)
        return linkedMapOf(
            "DDNATV_ROOT" to root.path,
            "DDNATV_HOSTNAME" to hostname.orEmpty(),
            "DDNATV_BINDS" to binds.joinToString("|") { if (it.contains(':')) it else "$it:$it" },
            "DDNATV_PRELOAD" to preload.joinToString(":"),
            "LD_LIBRARY_PATH" to libraryPath(root),
            "LD_PRELOAD" to (preload.map { File(root, it.removePrefix("/")).path } + hook(root).path).joinToString(":"),
        ).filterValues { it.isNotEmpty() }
    }

    /** A process for [guest] (argv) started as proot would with [binds], in guest directory [cwd]. */
    fun processBuilder(context: Context, root: File, binds: List<String>, guest: List<String>, cwd: String = "/root"): ProcessBuilder =
        ProcessBuilder(command(root, guest.first(), guest.drop(1)))
            .directory(File(root, cwd.removePrefix("/")).takeIf { it.isDirectory } ?: root)
            .also { builder ->
                builder.environment().apply {
                    remove("LD_AUDIT")
                    putAll(sessionEnvironment(root, binds, SessionPrefs.guestHostname(context)))
                }
            }

    private const val RUNPATH_CONF = "etc/ld.so.conf.d/zz-droiddeck-native.conf"
    private val SCAN_DIRS = listOf("usr/lib", "usr/local/lib", "opt")

    /**
     * ld.so opens the absolute RUNPATH directories of a library itself, with system calls the hook
     * cannot translate, so every one of them is also listed in ld.so.conf (which the hook maps).
     * Rescanned when dpkg's state or a library directory changes.
     */
    fun syncLibraryDirs(root: File) {
        val conf = File(root, RUNPATH_CONF)
        val signature = (listOf("var/lib/dpkg/status", "usr/lib", "usr/local/lib", "usr/lib/aarch64-linux-gnu")
            .joinToString(",") { File(root, it).lastModified().toString() }) + "," + conf.parentFile.list()?.size
        val stamp = File(root, "etc/droiddeck/native-runpath-signature")
        if (conf.isFile && stamp.isFile && stamp.readText() == signature) return
        val dirs = sortedSetOf<String>()
        for (name in SCAN_DIRS) File(root, name).walkTopDown().maxDepth(6)
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && ".so" in it.name }
            .forEach { file -> runCatching { ElfRunpath.read(file) }.getOrNull()?.let { dirs.addAll(it) } }
        val kept = dirs.filter { it.startsWith("/usr") || it.startsWith("/lib") || it.startsWith("/opt") }
        conf.parentFile?.mkdirs()
        conf.writeText(kept.joinToString("\n", postfix = "\n"))
        stamp.parentFile?.mkdirs()
        stamp.writeText(signature)
    }

    /** See [ElfSeccompPatch]: the linker and libc must survive Android's seccomp filter before the hook loads. */
    fun patchForSeccomp(root: File): Int =
        listOf("usr/lib/ld-linux-aarch64.so.1", "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/libc.so.6", "usr/lib/libc.so.6")
            .map { File(root, it) }.filter { it.exists() }
            .distinctBy { it.canonicalPath }.sumOf { file ->
                runCatching { ElfSeccompPatch.patchFile(file) }.getOrElse {
                    android.util.Log.e(TAG, "native userland: could not patch $file for Android's seccomp filter; programs will die with SIGSYS", it)
                    0
                }
            }

    private val HOST_LINK_TARGETS = listOf("/proc", "/dev", "/sys", "/sdcard", "/storage", "/data", "/system", "/vendor", "/apex",
        "/system_ext", "/product", "/odm", "/linkerconfig", "/mnt")

    /**
     * Symbolic links the kernel follows by itself (ld.so, Steam, anything that skips the hook) read
     * their absolute target on Android's own filesystem. The image's links were made for proot,
     * which reads the target inside the rootfs, so each is rewritten as a relative link, which
     * resolves the same way under proot and without it. Rerun when the runtime changes.
     */
    fun relinkAbsoluteSymlinks(context: Context, root: File): Int {
        val stamp = File(root, "etc/droiddeck/native-symlinks")
        val version = com.droiddeck.launcher.runtime.LinuxRuntimeInstaller.installedVersion(context).orEmpty() + "/2"
        if (stamp.isFile && stamp.readText() == version) return 0
        val rootPath = root.toPath()
        var rewritten = 0
        Files.walkFileTree(rootPath, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isSymbolicLink) {
                    try {
                        val target = Files.readSymbolicLink(file).toString()
                        if (target.startsWith("/") && HOST_LINK_TARGETS.none { target == it || target.startsWith("$it/") }
                            && !target.startsWith(root.path)) {
                            val relative = file.parent.relativize(rootPath.resolve(target.removePrefix("/")))
                            val temp = file.resolveSibling(".relink-" + file.fileName)
                            Files.deleteIfExists(temp)
                            Files.createSymbolicLink(temp, relative)
                            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            rewritten++
                        }
                    } catch (_: java.io.IOException) {
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
        })
        stamp.parentFile?.mkdirs()
        stamp.writeText(version)
        return rewritten
    }

    /** Where the kernel finds guest path [target] in a session with [binds] ("src:dst"), or null when it already does. */
    fun hostLinkTarget(target: String, root: String, binds: List<String>): String? {
        if (!target.startsWith("/") || target == root || target.startsWith("$root/")) return null
        val mounts = binds.mapNotNull { spec ->
            val colon = spec.indexOf(':')
            val src = (if (colon < 0) spec else spec.substring(0, colon)).trimEnd('/')
            val dst = (if (colon < 0) spec else spec.substring(colon + 1)).trimEnd('/')
            if (src.isEmpty() || dst.isEmpty()) null else src to dst
        }
        if (mounts.any { (src, dst) -> src != dst && (target == src || target.startsWith("$src/")) }) return null
        mounts.filter { (_, dst) -> target == dst || target.startsWith("$dst/") }.maxByOrNull { it.second.length }
            ?.let { (src, dst) -> return if (src == dst) null else src + target.removePrefix(dst) }
        if (HOST_LINK_TARGETS.any { target == it || target.startsWith("$it/") }) return null
        return root + if (target == "/") "" else target
    }

    /**
     * Wine prefixes' drive links (dosdevices) hold guest paths when proot made them ("s:" ->
     * /mnt/droiddeck-sd), and the kernel follows them as they are: Wine could not open Sekiro's
     * folder on the SD card library after a proot session. Proton keeps a link whose target reads
     * back as the one it wants, so they are made to point at the real places here.
     */
    fun relinkPrefixDrives(root: File, binds: List<String>): Int {
        val compatdata = listOf(File(root, "root/.local/share/Steam/steamapps/compatdata")) + binds.mapNotNull { spec ->
            val colon = spec.indexOf(':')
            if (colon > 0 && spec.substring(colon + 1).trimEnd('/').endsWith("/steamapps/compatdata")) File(spec.substring(0, colon)) else null
        }
        var rewritten = 0
        for (prefix in compatdata.distinct().flatMap { it.listFiles()?.toList().orEmpty() }) {
            val devices = File(prefix, "pfx/dosdevices").toPath()
            val links = try {
                Files.newDirectoryStream(devices).use { it.toList() }
            } catch (e: java.nio.file.NoSuchFileException) {
                continue
            } catch (e: java.io.IOException) {
                android.util.Log.w(TAG, "native userland: could not read the drive links of $prefix", e)
                continue
            } catch (e: java.nio.file.DirectoryIteratorException) {
                android.util.Log.w(TAG, "native userland: could not read the drive links of $prefix", e)
                continue
            }
            for (link in links) {
                try {
                    if (!Files.isSymbolicLink(link)) continue
                    val host = hostLinkTarget(Files.readSymbolicLink(link).toString(), root.path, binds) ?: continue
                    val temp = link.resolveSibling(".relink-" + link.fileName)
                    Files.deleteIfExists(temp)
                    Files.createSymbolicLink(temp, java.nio.file.Paths.get(host))
                    Files.move(temp, link, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    rewritten++
                } catch (_: java.io.IOException) {
                }
            }
        }
        return rewritten
    }

    fun builder(root: File, cwd: String, argv: List<String>, env: Map<String, String>, preload: List<String> = emptyList()): ProcessBuilder {
        val program = argv.first()
        val cmd = command(root, program, argv.drop(1))
        val builder = ProcessBuilder(cmd).redirectErrorStream(false)
        builder.directory(File(root, cwd.removePrefix("/")).takeIf { it.isDirectory } ?: root)
        builder.environment().apply {
            clear()
            putAll(environment(root, preload))
            putAll(env)
        }
        return builder
    }
}
