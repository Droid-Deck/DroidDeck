package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.runtime.LinuxNetworkLinkComponent
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.ProotFastPath
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreInstallRoot
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

internal object Xodus {
    const val DIR = "/usr/local/lib/droiddeck-xbox"
    fun market(): String = java.util.Locale.getDefault().country.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"

    class Child(val process: Process, private val credentials: XboxCredentials, private val runtimeDirectory: File? = null, private val launchDirectory: File? = null,
                private val networkLink: LinuxNetworkLinkComponent) : Closeable {
        @Synchronized fun send(frame: JSONObject) {
            process.outputStream.write((frame.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }
        override fun close() {
            try {
                credentials.close()
                runCatching { process.outputStream.close() }
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    credentials.signalBackend(android.system.OsConstants.SIGINT)
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        credentials.signalBackend(android.system.OsConstants.SIGKILL)
                        if (!process.waitFor(1, TimeUnit.SECONDS)) {
                            val pid = com.droiddeck.launcher.core.HostProcess.pidOf(process)
                            if (pid > 0) android.os.Process.killProcess(pid)
                        }
                    }
                }
            } finally {
                networkLink.stop()
                runtimeDirectory?.let { directory ->
                    File(directory, "xodus.sock").delete()
                    directory.delete()
                }
                launchDirectory?.let(XboxLaunchViews::remove)
            }
        }
    }

    @Synchronized private fun stage(app: Context): File {
        check(AddonPack.XBOX.await(app)) { "Couldn't download the Xbox components. Check your connection and try again." }
        val pack = AddonPack.XBOX.dir(app)
        val root = File(LinuxRuntime.rootDir(app), DIR.removePrefix("/"))
        val packaged = File(pack, "version").readText()
        if (runCatching { File(root, "version").readText() }.getOrNull() == packaged) return pack
        fun copy(source: File, relative: String) {
            if (source.isDirectory) { source.listFiles().orEmpty().forEach { copy(it, if (relative.isEmpty()) it.name else "$relative/${it.name}") }; return }
            if (relative == "version" || relative == AddonPack.COMPLETE) return
            val file = File(root, relative)
            file.parentFile?.mkdirs()
            val staged = File(file.path + ".staged")
            source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            staged.setExecutable(true, false)
            check(staged.renameTo(file))
        }
        copy(pack, "")
        File(root, "version").writeText(packaged)
        return pack
    }

    fun start(app: Context, args: List<String>, service: Boolean = false, runtimeDirectory: File? = null, launchDirectory: File? = null, credentialGeneration: String? = null): Child {
        check(LinuxRuntime.isInstalled(app)) { "Install the Linux runtime before using Xbox." }
        val pack = stage(app)
        val binary = "$DIR/${if (service) "xodus-service" else "xodus-cli"}"
        check(File(LinuxRuntime.rootDir(app), binary.removePrefix("/")).isFile) { "The Xbox backend is missing. Update the Linux runtime." }
        val networkLink = LinuxNetworkLinkComponent(app, LinuxRuntime.rootDir(app))
        val credentials = XboxCredentials(app, credentialGeneration)
        try {
            networkLink.attach(app)
            networkLink.publish()
            networkLink.start()
            val runtime = (runtimeDirectory ?: File(app.filesDir, "xbox-runtime")).apply { mkdirs() }
            val root = LinuxRuntime.rootDir(app)
            val external = Environment.getExternalStorageDirectory()
            val extraBinds = StoreInstallRoot.externalBindSpecsForXodus(app)
            val binds = LinuxRuntime.binds(app, null, null, external, extraBinds)
            val fastPathPreloaded = runCatching {
                File(root, "etc/ld.so.preload").useLines { lines -> lines.any { it.trim() == ProotFastPath.LIBRARY } }
            }.getOrDefault(false)
            val fastPathKey = if (ProotFastPath.enabled(app) && fastPathPreloaded &&
                File(root, ProotFastPath.LIBRARY.removePrefix("/")).isFile) ProotFastPath.key(root, binds) else null
            val guestCommand = mutableListOf(
                "/usr/bin/env", "HOME=/root", "XODUS_LOG=off", "RUST_LOG=off",
                "XDG_RUNTIME_DIR=${runtime.path}", "DROIDDECK_XBOX_CREDENTIAL_SOCKET=${credentials.name}", binary,
            )
            fastPathKey?.let { key ->
                guestCommand.addAll(guestCommand.lastIndex, ProotFastPath.guestEnv(root, binds, key))
            }
            guestCommand.addAll(args)
            val command = LinuxRuntime.commandWithBinds(app, root, binds, guestCommand)
            val builder = ProcessBuilder(command).directory(LinuxRuntime.rootDir(app))
            if (launchDirectory != null) builder.environment()["TMPDIR"] = launchDirectory.path
            builder.environment().apply {
                remove("LD_PRELOAD"); remove("LD_AUDIT"); remove("LD_LIBRARY_PATH")
                put("PROOT_LOADER", LinuxRuntime.prootLoader(app).path)
                put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(app).path)
                if (SessionPrefs.prootNoSeccomp(app)) put("PROOT_NO_SECCOMP", "1")
                fastPathKey?.let { ProotFastPath.hostEnv(it).let { (key, value) -> put(key, value) } }
                LinuxRuntime.prootLibraryPath(app).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            File(pack, binary.removePrefix("$DIR/")).inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val process = builder.start()
            credentials.bind(process, File(LinuxRuntime.rootDir(app), binary.removePrefix("/")), digest.digest())
            return Child(process, credentials, runtimeDirectory, launchDirectory, networkLink)
        } catch (e: Exception) {
            runCatching { networkLink.stop() }
            credentials.close()
            throw e
        }
    }

    fun run(app: Context, args: List<String>, timeout: Long = 90, credentialGeneration: String? = null): JSONObject {
        start(app, args, credentialGeneration = credentialGeneration).use { child ->
            val output = java.io.ByteArrayOutputStream()
            var readError: Throwable? = null
            val reader = Thread({
                try {
                    val buffer = ByteArray(8192)
                    child.process.inputStream.use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 8_000_000)
                            output.write(buffer, 0, count)
                        }
                    }
                } catch (t: Throwable) { readError = t; child.process.destroy() }
            }, "xbox-result").apply { start() }
            val diagnostic = diagnostics(child)
            check(child.process.waitFor(timeout, TimeUnit.SECONDS)) { "Xbox request timed out. Try again." }
            reader.join(3000); diagnostic.join(3000)
            check(readError == null && !reader.isAlive) { "Xbox returned an invalid response." }
            check(child.process.exitValue() == 0) { "Xbox request failed. Check your account and network connection." }
            return JSONObject(output.toString("UTF-8"))
        }
    }

    /** Passes the backend's known stderr diagnostics to [report]; other stderr text never leaves the process. */
    fun diagnostics(child: Child, report: (String) -> Unit = { com.droiddeck.launcher.stores.StoresState.logLine("Xbox: $it") }): Thread = Thread({
        runCatching {
            child.process.errorStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.matches(Regex("NEXUS_CREDENTIAL_ERROR_V1 [a-z-]{1,48}|NEXUS_LIBRARY_DIAGNOSTIC_V1 [a-z-]{1,48} [0-9]{1,3} [0-9]{1,6}|NEXUS_SESSION_DIAGNOSTIC_V1 [a-z-]{1,64}|Account library unavailable \\([a-z-]{1,64}\\); credentials and installed games are unchanged\\.|Account library timed out; installed games are unchanged\\.|Xbox profile is temporarily unavailable \\([a-z-]{1,48}\\); stored account is unchanged\\.|Xbox profile request timed out; stored account is unchanged\\.")))
                    report(line)
            } }
        }
    }, "xbox-diagnostics").apply { start() }
}
