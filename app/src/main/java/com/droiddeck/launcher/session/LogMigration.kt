package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

object LogMigration {
    private const val TAG = "LogMigration"
    private const val MARKER = ".moved-from-downloads"
    private const val MAX_ENTRIES = 16_384
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private val oldScrubMarker = Regex("""^\.scrubbed-(?:r?\d+)$""")

    private class CopyBudget(var entries: Int = 0, var bytes: Long = 0)

    @Synchronized
    fun run(context: Context) {
        val targetFile = LinuxRuntime.logDir(context)
        val target = SecureDiagnosticOutput.open(context, targetFile) ?: run {
            Log.w(TAG, "could not securely open the session log directory")
            return
        }
        target.use { output ->
            if (output.containsRegular(MARKER)) return
            val legacy = LinuxRuntime.legacyLogDir()
            var copied = 0
            var failed = false
            val budget = CopyBudget()
            val listed = SecureDiagnosticOutput.withExistingDirectory(legacy) { root ->
                val rootNames = names(root, budget) ?: return@withExistingDirectory false
                for (name in rootNames) {
                    val text = name.toString()
                    if (!safeName(text)) continue
                    val attrs = attributes(root, name) ?: continue
                    val session = SessionPaths.isSessionName(text)
                    val tools = text == SessionPaths.TOOLS_DIR
                    val log = attrs.isRegularFile && text.endsWith(".log")
                    if (!session && !tools && !log) continue
                    if (attrs.isSymbolicLink || (session || tools) && !attrs.isDirectory) {
                        failed = true
                        continue
                    }
                    if (session && output.contains(text)) continue
                    val destination = runCatching {
                        output.child(if (log) SessionPaths.TOOLS_DIR else text)
                    }.getOrNull()
                    if (destination == null) {
                        failed = true
                        continue
                    }
                    destination.use { child ->
                        val ok = if (attrs.isDirectory) copyDirectory(root, name, child, budget, 0)
                            else if (child.contains(text)) true else copyFile(root, name, child, text, budget)
                        if (ok) {
                            copied++
                        } else {
                            failed = true
                            if (session) {
                                child.clear()
                                child.deleteIfEmpty()
                            }
                        }
                    }
                }
                true
            }
            if (listed == false) {
                Log.w(TAG, "$legacy exceeds the safe migration traversal limit; logs there stay in place")
                return
            }
            if (listed == null && legacy.exists()) {
                Log.w(TAG, "$legacy cannot be securely listed; logs there stay until it can")
                return
            }
            if (!failed) runCatching {
                output.write(MARKER) { it.write("copied $copied item(s)\n".toByteArray()) }
            }.onFailure { Log.w(TAG, "could not mark the migration: ${it.message}") }
            if (copied > 0) Log.i(TAG, "copied $copied log item(s) from $legacy into $targetFile; originals retained")
        }
    }

    private fun copyDirectory(
        source: SecureDirectoryStream<Path>,
        name: Path,
        destination: DiagnosticOutputDirectory,
        budget: CopyBudget,
        depth: Int,
    ): Boolean {
        if (depth > 32) return false
        val child = runCatching { source.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            ?: return false
        return child.use { directory ->
            val entries = names(directory, budget) ?: return@use false
            for (entry in entries) {
                val text = entry.toString()
                if (!safeName(text)) return@use false
                if (oldScrubMarker.matches(text) || text == SessionArtifacts.SCRUBBED_TREE_MARKER) continue
                val attrs = attributes(directory, entry) ?: return@use false
                if (attrs.isSymbolicLink) return@use false
                if (attrs.isDirectory) {
                    val output = runCatching { destination.child(text) }.getOrNull() ?: return@use false
                    output.use { if (!copyDirectory(directory, entry, it, budget, depth + 1)) return@use false }
                } else if (attrs.isRegularFile) {
                    if (!destination.contains(text) && !copyFile(directory, entry, destination, text, budget)) {
                        return@use false
                    }
                } else return@use false
            }
            true
        }
    }

    private fun copyFile(
        source: SecureDirectoryStream<Path>,
        name: Path,
        destination: DiagnosticOutputDirectory,
        outputName: String,
        budget: CopyBudget,
    ): Boolean {
        val before = attributes(source, name) ?: return false
        if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null ||
            before.size() < 0 || before.size() > MAX_BYTES - budget.bytes) return false
        return runCatching {
            source.newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { input ->
                destination.write(outputName) { output ->
                    copyExact(input, output, before.size())
                    val after = attributes(source, name)
                    check(after?.fileKey() == before.fileKey() && after.size() == before.size())
                }
            }
            budget.bytes += before.size()
            true
        }.getOrDefault(false)
    }

    private fun copyExact(input: SeekableByteChannel, output: OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = input.read(buffer)
            check(read > 0)
            output.write(buffer.array(), 0, read)
            remaining -= read
        }
        check(input.size() == size)
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes? = runCatching {
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            .readAttributes()
    }.getOrNull()

    private fun names(directory: SecureDirectoryStream<Path>, budget: CopyBudget): List<Path>? {
        val names = ArrayList<Path>()
        val iterator = directory.iterator()
        while (iterator.hasNext()) {
            if (++budget.entries > MAX_ENTRIES) return null
            names.add(iterator.next().fileName)
        }
        return names
    }

    private fun safeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
}
