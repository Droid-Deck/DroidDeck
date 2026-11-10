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

    /** The mode the running session started in; null between sessions. */
    @Volatile var sessionMode: Boolean? = null

    /**
     * Whether a session's helpers (root commands, Flatpak sandboxes, installers) run natively: the
     * session's own mode while one runs, as the setting can change under it and the rootfs is only
     * prepared for native programs at a session's start.
     */
    fun forSession(context: Context): Boolean = sessionMode ?: enabled(context)

    /**
     * Development switch: etc/droiddeck/seccomp-sim names a helper (a guest path, then its arguments)
     * that applies Android's app seccomp filter before the program starts, for devices that run apps
     * without one (eng builds, or a permissive SELinux, where zygote skips it). Guest paths among the
     * arguments are given as their places in the rootfs, as the helper runs before the hook.
     */
    fun simulator(root: File): List<String>? {
        val words = File(root, "etc/droiddeck/seccomp-sim").takeIf { it.isFile }?.readText()?.trim()
            ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        val helper = words.firstOrNull()?.takeIf { it.startsWith("/") }?.let { File(root, it.removePrefix("/")) }
            ?.takeIf { it.isFile } ?: return null
        return listOf(helper.path) + words.drop(1).map { if (it.startsWith("/")) File(root, it.removePrefix("/")).path else it }
    }

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
        return simulator(root)?.let { it + launch } ?: launch
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

    /** Where guest directory [cwd] is, through the session's mounts ([binds], "src:dst"); the rootfs if it is nowhere. */
    fun workingDirectory(root: File, binds: List<String>, cwd: String): File =
        File(BwrapSpawner.hostPath(binds.map { BwrapSpawner.Bind.parse(it) }, root.path, cwd)).takeIf { it.isDirectory } ?: root

    /** A process for [guest] (argv) started as proot would with [binds], in guest directory [cwd]. */
    fun processBuilder(context: Context, root: File, binds: List<String>, guest: List<String>, cwd: String = "/root"): ProcessBuilder =
        ProcessBuilder(command(root, guest.first(), guest.drop(1)))
            .directory(workingDirectory(root, binds, cwd))
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

    /** What a rootfs needs before anything runs in it natively; cheap once done. */
    fun prepare(context: Context, root: File) {
        val relinked = relinkAbsoluteSymlinks(context, root)
        if (relinked > 0) android.util.Log.i(TAG, "native userland: $relinked absolute symlinks made relative")
        val patched = patchForSeccomp(root)
        if (patched > 0) android.util.Log.i(TAG, "native userland: $patched system call sites in ld.so and libc answer Android's blocked calls")
        syncLibraryDirs(root)
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

    /**
     * The guest path that host path [target] stands for in a session with [binds] ("src:dst"), or null
     * when it is a guest path already: the rootfs, or the longest mount source holding it (the first
     * such mount when several share a source).
     */
    fun guestLinkTarget(target: String, root: String, binds: List<String>): String? {
        if (!target.startsWith("/")) return null
        fun under(path: String, dir: String) = path == dir || path.startsWith("$dir/")
        var best: Pair<String, String>? = if (under(target, root)) root to "/" else null
        for (spec in binds) {
            val colon = spec.indexOf(':')
            if (colon < 0) continue
            val src = spec.substring(0, colon).trimEnd('/')
            val dst = spec.substring(colon + 1).trimEnd('/').ifEmpty { "/" }
            if (src.isEmpty() || src == dst || !under(target, src)) continue
            if (best == null || src.length > best.first.length) best = src to dst
        }
        val (src, dst) = best ?: return null
        val rest = target.removePrefix(src)
        return if (rest.isEmpty()) dst else dst.trimEnd('/') + rest
    }

    /**
     * Wine prefixes' drive links (dosdevices) hold guest paths ("s:" -> /mnt/droiddeck-sd), which
     * proot follows and the hook follows inside a mount. Earlier native sessions made them point at
     * their real places, which proot cannot see: those are given their guest paths back.
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
                    val guest = guestLinkTarget(Files.readSymbolicLink(link).toString(), root.path, binds) ?: continue
                    val temp = link.resolveSibling(".relink-" + link.fileName)
                    Files.deleteIfExists(temp)
                    Files.createSymbolicLink(temp, java.nio.file.Paths.get(guest))
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
