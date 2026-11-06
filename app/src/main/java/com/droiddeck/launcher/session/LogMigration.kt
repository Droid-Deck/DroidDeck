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
    private const val LEGACY_MARKER = ".moved-from-downloads"
    private const val LEGACY_V2_MARKER = ".moved-from-downloads-v2"
    private const val MARKER = ".moved-from-downloads-v3"
    private const val LEGACY_SESSION_PENDING_PREFIX = ".legacy-migration-pending-"
    private const val LEGACY_SESSION_COMPLETE_PREFIX = ".legacy-migration-complete-"
    private const val SESSION_PENDING_PREFIX = ".legacy-migration-v3-pending-"
    private const val SESSION_COMPLETE_PREFIX = ".legacy-migration-v3-complete-"
    private const val MAX_ENTRIES = 16_384
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private val legacyGlobalCompletion = Regex("""^copied \d+ item\(s\)\n$""")
    private val oldScrubMarker = Regex("""^\.scrubbed-(?:r?\d+)$""")

    private class CopyBudget(var entries: Int = 0, var bytes: Long = 0)

    @Synchronized
    fun run(context: Context) {
        try {
            runOnce(context)
        } catch (error: AtomicPublicationUnsupportedException) {
            Log.w(
                TAG,
                "atomic no-replace log migration is unsupported on this device " +
                    "(errno ${error.errno}); legacy logs remain in place",
            )
        }
    }

    private fun runOnce(context: Context) {
        SecureDiagnosticOutput.cleanupLegacyStages(context)
        val targetFile = LinuxRuntime.logDir(context)
        val target = SecureDiagnosticOutput.open(context, targetFile) ?: run {
            Log.w(TAG, "could not securely open the session log directory")
            return
        }
        target.use { output ->
            if (output.containsRegular(MARKER)) return
            val validV2Completion = output.readSmallRegular(LEGACY_V2_MARKER, 128)
                ?.toString(Charsets.UTF_8)?.let(legacyGlobalCompletion::matches) == true
            if (validV2Completion) {
                attemptAtomicPublication {
                    output.writeAtomic(MARKER) { it.write("upgraded from v2\n".toByteArray()) }
                }
                return
            }
            val recoverLegacyMigration = output.containsRegular(LEGACY_MARKER) ||
                output.contains(LEGACY_V2_MARKER)
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
                    val pendingMarker = "$SESSION_PENDING_PREFIX$text"
                    val completeMarker = "$SESSION_COMPLETE_PREFIX$text"
                    val legacyPendingMarker = "$LEGACY_SESSION_PENDING_PREFIX$text"
                    val legacyCompleteMarker = "$LEGACY_SESSION_COMPLETE_PREFIX$text"
                    var destinationExists = session && output.contains(text)
                    val validLegacyComplete = session &&
                        output.readSmallRegular(legacyCompleteMarker, 32)?.contentEquals("complete\n".toByteArray()) == true
                    if (session && (output.containsRegular(completeMarker) || validLegacyComplete)) continue
                    val hasPendingMarker = session && (output.containsRegular(pendingMarker) ||
                        output.readSmallRegular(legacyPendingMarker, 32)?.contentEquals("pending\n".toByteArray()) == true)
                    if (session && destinationExists && !hasPendingMarker && !recoverLegacyMigration) {
                        val empty = runCatching { output.child(text)?.use { it.deleteIfEmpty() } }
                            .getOrNull()
                        if (empty != true) continue
                        destinationExists = false
                    }
                    var migrationOwned = hasPendingMarker
                    if (session && !destinationExists && !hasPendingMarker) {
                        val markedPending = attemptAtomicPublication {
                            output.writeAtomic(pendingMarker) { it.write("pending\n".toByteArray()) }
                        }.isSuccess
                        if (!markedPending) {
                            failed = true
                            continue
                        }
                        migrationOwned = true
                    }
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
                            val completed = !session || output.containsRegular(completeMarker) ||
                                attemptAtomicPublication {
                                    output.writeAtomic(completeMarker) { it.write("complete\n".toByteArray()) }
                                }.isSuccess
                            if (completed) copied++ else failed = true
                        } else {
                            failed = true
                            if (session && migrationOwned) {
                                runCatching {
                                    child.clear()
                                    child.deleteIfEmpty()
                                }.onFailure {
                                    Log.w(TAG, "could not clear the partial migration for $text: ${it.message}")
                                }
                                // The root-level pending marker remains even when partial cleanup fails.
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
            if (!failed) attemptAtomicPublication {
                output.writeAtomic(MARKER) { it.write("copied $copied item(s)\n".toByteArray()) }
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
        return child.use directoryUse@ { directory ->
            val entries = names(directory, budget) ?: return@directoryUse false
            for (entry in entries) {
                val text = entry.toString()
                if (!safeName(text)) return@directoryUse false
                if (oldScrubMarker.matches(text) || text == SessionArtifacts.SCRUBBED_TREE_MARKER) continue
                val attrs = attributes(directory, entry) ?: return@directoryUse false
                if (attrs.isSymbolicLink) return@directoryUse false
                if (attrs.isDirectory) {
                    val output = runCatching { destination.child(text) }.getOrNull() ?: return@directoryUse false
                    val copied = output.use { copyDirectory(directory, entry, it, budget, depth + 1) }
                    if (!copied) return@directoryUse false
                } else if (attrs.isRegularFile) {
                    if (!destination.contains(text) && !copyFile(directory, entry, destination, text, budget)) {
                        return@directoryUse false
                    }
                } else return@directoryUse false
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
        return try {
            source.newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { input ->
                destination.writeAtomic(outputName) { output ->
                    copyExact(input, output, before.size())
                    val after = attributes(source, name)
                    check(after?.fileKey() == before.fileKey() && after.size() == before.size())
                }
            }
            budget.bytes += before.size()
            true
        } catch (error: AtomicPublicationUnsupportedException) {
            throw error
        } catch (_: Throwable) {
            false
        }
    }

    private inline fun <T> attemptAtomicPublication(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: AtomicPublicationUnsupportedException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
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
