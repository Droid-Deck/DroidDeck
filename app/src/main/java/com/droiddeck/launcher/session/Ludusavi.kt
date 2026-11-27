package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object Ludusavi {
    data class Status(val running: Boolean = false, val output: String = "")

    private const val BIN = "/usr/local/bin/droiddeck-ludusavi"
    private const val CONFIG = "/root/.config/droiddeck-ludusavi"
    private const val X64_STEAM_ROOT = "/root/.droiddeck-x64/.local/share/Steam"
    private const val REL_BACKUPS = "DroidDeck/Saves/ludusavi"

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val starting = AtomicBoolean(false)

    fun installed(context: Context): Boolean = File(LinuxRuntime.rootDir(context), BIN.trimStart('/')).isFile

    private fun backupDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), REL_BACKUPS)

    fun report(message: String) {
        mutableStatus.value = mutableStatus.value.copy(output = message)
    }

    fun start(context: Context, operation: (Context, (String) -> Unit) -> Int) {
        val app = context.applicationContext
        if (!starting.compareAndSet(false, true)) return
        mutableStatus.value = Status(running = true, output = app.getString(R.string.ludusavi_running))
        scope.launch {
            val lines = mutableListOf<String>()
            try {
                val code = withContext(Dispatchers.IO) { operation(app) { lines.add(it) } }
                mutableStatus.value = Status(output = result(lines, "Exit: $code"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                mutableStatus.value = Status(output = result(lines, "Failed: $reason"))
            } finally {
                mutableStatus.value = mutableStatus.value.copy(running = false)
                starting.set(false)
            }
        }
    }

    private fun result(lines: List<String>, headline: String): String =
        (listOf(headline) + lines).joinToString("\n")

    internal fun withX64SteamRoot(config: String): String {
        if (config.lines().any { it.trim() == "path: $X64_STEAM_ROOT" }) return config
        val lines = config.lines().toMutableList()
        val roots = lines.indexOfFirst {
            val trimmed = it.trimStart()
            !trimmed.startsWith("#") && trimmed.startsWith("roots:")
        }
        val entry = listOf("  - store: steam", "    path: $X64_STEAM_ROOT")
        if (roots < 0) {
            val body = config.trimEnd('\n')
            return if (body.isEmpty()) "roots:\n${entry.joinToString("\n")}\n"
            else "$body\nroots:\n${entry.joinToString("\n")}\n"
        }
        val inline = lines[roots].trimStart().substringAfter("roots:").trim()
        if (inline == "[]" || inline.startsWith("[] ")) lines[roots] = "roots:"
        lines.addAll(roots + 1, entry)
        return lines.joinToString("\n")
    }

    private fun ensureX64Root(context: Context) {
        val file = File(LinuxRuntime.rootDir(context), "${CONFIG.trimStart('/')}/config.yaml")
        val current = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        val updated = withX64SteamRoot(current)
        if (updated == current) return
        file.parentFile?.mkdirs()
        file.writeText(updated)
    }

    fun run(context: Context, args: List<String>, onLine: (String) -> Unit): Int {
        runCatching { backupDir().mkdirs() }
        ensureX64Root(context)
        val base = listOf(BIN, "--config", CONFIG, "--no-manifest-update")
        return GuestCommand.run(context, base + args, logName = "ludusavi", onLine = onLine)
    }

    fun backup(context: Context, title: String, onLine: (String) -> Unit): Int = run(
        context, listOf("backup", "--api", "--force", "--path", backupDir().path, "--", title), onLine)

    fun preview(context: Context, title: String, onLine: (String) -> Unit): Int = run(
        context, listOf("backup", "--api", "--preview", "--path", backupDir().path, "--", title), onLine)

    fun restore(context: Context, title: String, onLine: (String) -> Unit): Int = run(
        context, listOf("restore", "--api", "--force", "--path", backupDir().path, "--", title), onLine)

    fun updateManifest(context: Context, onLine: (String) -> Unit): Int =
        run(context, listOf("manifest", "update"), onLine)
}
