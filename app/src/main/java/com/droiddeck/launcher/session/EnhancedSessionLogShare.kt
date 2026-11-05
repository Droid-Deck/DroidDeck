package com.droiddeck.launcher.session

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.BattleNet
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class EnhancedShareLimits(
    val maxFilesPerArchive: Int = 256,
    val maxDiscoveredFiles: Int = 768,
    val maxBytesPerFile: Long = 8L * 1024 * 1024,
    val maxBytesTotal: Long = 64L * 1024 * 1024,
)

internal interface SecureSourceOpener {
    fun <T> withChannel(root: Path, relative: Path, block: (SeekableByteChannel) -> T): T?
}

internal object EnhancedSessionLogShare {
    private enum class Category(val suffix: String) { SESSION("session"), GRAPHICS("graphics"), GAMES("games") }

    internal class DiagnosticStageLease internal constructor(
        val directory: File,
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : Closeable {
        override fun close() {
            try {
                lock.release()
            } finally {
                try {
                    channel.close()
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }

    private data class Candidate(
        val file: File,
        val anchor: File,
        val entry: String,
        val origin: String,
        val category: Category,
        val linePrefix: String? = null,
    )

    private data class Record(
        val entry: String,
        val origin: String,
        val status: String,
        val sourceBytes: Long = 0,
        val capturedBytes: Long = 0,
        val reason: String? = null,
    )

    private data class Snapshot(val file: File, val sourceBytes: Long, val truncated: Boolean)

    private class StageUnavailableException(cause: Throwable) : IOException("Diagnostic staging directory is unavailable", cause)

    private class SourceReadException(cause: Throwable) : IOException("Could not read diagnostic source", cause)

    private data class CollectionResult(
        val candidates: List<Candidate>,
        val truncated: Boolean,
        val partial: Boolean,
    )

    private data class ScanResult(
        val files: List<File>,
        val truncated: Boolean,
        val partial: Boolean = false,
    )

    private data class LegacySource(val file: File, val root: File, val entry: String)

    private data class LegacyDiscovery(
        val sources: List<LegacySource>,
        val truncated: Boolean,
        val partial: Boolean,
    )

    private class Budget(val limit: Long, var remaining: Long = limit)

    private val sourceOpenerOverride = object : ThreadLocal<SecureSourceOpener?>() {
        override fun initialValue(): SecureSourceOpener? = null
    }
    private val stageLifecycleMonitor = Any()

    internal fun acquireDiagnosticStage(context: Context, outputName: String): DiagnosticStageLease {
        val cache = context.cacheDir
        require(outputName.isNotBlank() && File(outputName).name == outputName)
        return withStageLifecycleLock(cache) {
            val stage = File(cache, "$outputName-${UUID.randomUUID()}.diagnostic-stage")
            if (!stage.mkdir()) throw IOException("Could not create the diagnostic staging directory")
            val channel = try {
                FileChannel.open(File(stage, ".active").toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (error: Exception) {
                stage.deleteRecursively()
                throw IOException("Could not create the diagnostic staging lock", error)
            }
            try {
                val lock = channel.tryLock()
                    ?: throw IOException("Could not lock the diagnostic staging directory")
                DiagnosticStageLease(stage, channel, lock)
            } catch (error: Exception) {
                channel.close()
                stage.deleteRecursively()
                throw IOException("Could not lock the diagnostic staging directory", error)
            }
        }
    }

    internal fun cleanupAbandonedStages(context: Context) {
        val cache = context.cacheDir
        val canonicalCache = cache.canonicalFile
        withStageLifecycleLock(cache) {
            cache.listFiles()?.forEach { stage ->
                if (!stage.isDirectory || Files.isSymbolicLink(stage.toPath()) ||
                    stage.canonicalFile.parentFile != canonicalCache || !isDiagnosticStage(stage.name)) return@forEach
                val active = File(stage, ".active")
                if (Files.isSymbolicLink(active.toPath())) return@forEach
                val channel = runCatching {
                    FileChannel.open(active.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                }.getOrNull() ?: return@forEach
                val abandoned = try {
                    channel.tryLock()?.let { lock -> lock.release(); true } ?: false
                } catch (_: OverlappingFileLockException) {
                    false
                } finally {
                    channel.close()
                }
                if (abandoned) stage.deleteRecursively()
            }
        }
    }

    private fun isDiagnosticStage(name: String): Boolean {
        val suffix = ".diagnostic-stage"
        if (!name.endsWith(suffix)) return false
        val base = name.removeSuffix(suffix)
        val uuidStart = base.length - 36
        if (uuidStart < 2 || base[uuidStart - 1] != '-') return false
        return runCatching { UUID.fromString(base.substring(uuidStart)).toString() == base.substring(uuidStart) }
            .getOrDefault(false)
    }

    private fun <T> withStageLifecycleLock(cache: File, block: () -> T): T = synchronized(stageLifecycleMonitor) {
        FileChannel.open(File(cache, ".diagnostic-stages.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel -> channel.lock().use { block() } }
    }

    internal fun setSourceOpenerForTest(opener: SecureSourceOpener?) {
        if (opener == null) sourceOpenerOverride.remove() else sourceOpenerOverride.set(opener)
    }

    internal fun hasSourceOpenerForTest(): Boolean = sourceOpenerOverride.get() != null

    fun create(
        context: Context,
        folder: File,
        onProgress: ((Float) -> Unit)? = null,
        limits: EnhancedShareLimits = EnhancedShareLimits(),
    ): List<File> {
        if (!folder.isDirectory || Files.isSymbolicLink(folder.toPath()) ||
            limits.maxFilesPerArchive < 1 || limits.maxBytesPerFile < 1 || limits.maxBytesTotal < 1) {
            return emptyList()
        }
        val out = SessionLogShare.shareDir(context).apply { deleteRecursively(); mkdirs() }
        val stageLease = acquireDiagnosticStage(context, out.name)
        val stage = stageLease.directory
        return try {
            val redactionLearningComplete = LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
            val discovery = collect(context, folder, limits.maxDiscoveredFiles, legacy = false)
            val candidates = discovery.candidates
            val totalCandidates = candidates.size.coerceAtLeast(1)
            val share = limits.maxBytesTotal / Category.values().size
            val budgets = Category.values().associateWith { category ->
                Budget(if (category == Category.GAMES) limits.maxBytesTotal - share * 2 else share)
            }
            var completed = 0
            Category.values().map { category ->
                val budget = budgets.getValue(category)
                val records = ArrayList<Record>()
                val selected = candidates.filter { it.category == category }
                val zip = File(out, "DroidDeck-${folder.name}-${category.suffix}.zip")
                ZipOutputStream(zip.outputStream().buffered()).use { archive ->
                    val names = HashSet<String>()
                    selected.forEachIndexed { index, candidate ->
                        val safeEntry = safeEntry(candidate.entry)
                        val record = when {
                            index >= limits.maxFilesPerArchive -> Record(
                                safeEntry ?: "<invalid>", candidate.origin, "omitted", reason = "file-count-limit",
                            )
                            safeEntry == null -> Record("<invalid>", candidate.origin, "omitted", reason = "unsafe-entry-name")
                            !names.add(safeEntry) -> Record(safeEntry, candidate.origin, "omitted", reason = "duplicate-entry")
                            else -> addCandidate(archive, candidate.copy(entry = safeEntry), stage, budget, limits)
                        }
                        records += record
                        completed++
                        onProgress?.invoke((completed.toFloat() / totalCandidates).coerceIn(0f, 1f))
                    }
                    if (category == Category.SESSION) addTrustedActivation(context, folder, archive)?.let(records::add)
                    if (selected.isEmpty()) records += Record("", category.suffix, "unavailable", reason = "no-matching-artifacts")
                    if (discovery.truncated) records += Record("", "discovery", "truncated", reason = "candidate-limit")
                    if (discovery.partial) records += Record("", "discovery", "truncated", reason = "filesystem-error")
                    addManifest(
                        archive, folder.name, category.suffix, records, limits, budget.limit, redactionLearningComplete,
                    )
                }
                zip
            }
        } finally {
            stageLease.close()
        }
    }

    fun createLegacy(
        context: Context,
        folder: File,
        onProgress: ((Float) -> Unit)? = null,
        outputDirectory: File = SessionLogShare.shareDir(context),
        limits: EnhancedShareLimits = EnhancedShareLimits(maxFilesPerArchive = 512),
    ): File? {
        if (!folder.isDirectory || Files.isSymbolicLink(folder.toPath()) ||
            limits.maxFilesPerArchive < 1 || limits.maxBytesPerFile < 1 || limits.maxBytesTotal < 1) {
            return null
        }
        val out = outputDirectory.apply { deleteRecursively(); mkdirs() }
        val redactionLearningComplete = LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val discovery = collect(context, folder, limits.maxDiscoveredFiles, legacy = true)
        val candidates = discovery.candidates.filter { it.linePrefix == null }
        if (candidates.isEmpty()) {
            out.deleteRecursively()
            return null
        }
        val stageLease = acquireDiagnosticStage(context, out.name)
        val stage = stageLease.directory
        val records = ArrayList<Record>()
        val budget = Budget(limits.maxBytesTotal)
        val zip = File(out, "DroidDeck-${folder.name}.zip")
        return try {
            ZipOutputStream(zip.outputStream().buffered()).use { archive ->
                val names = HashSet<String>()
                candidates.forEachIndexed { index, candidate ->
                    val safeEntry = safeEntry(candidate.entry)
                    records += when {
                        index >= limits.maxFilesPerArchive -> Record(
                            safeEntry ?: "<invalid>", candidate.origin, "omitted", reason = "file-count-limit",
                        )
                        safeEntry == null -> Record("<invalid>", candidate.origin, "omitted", reason = "unsafe-entry-name")
                        !names.add(safeEntry) -> Record(safeEntry, candidate.origin, "omitted", reason = "duplicate-entry")
                        else -> addCandidate(archive, candidate.copy(entry = safeEntry), stage, budget, limits)
                    }
                    onProgress?.invoke((index + 1f) / candidates.size)
                }
                addTrustedActivation(context, folder, archive)?.let(records::add)
                if (discovery.truncated) records += Record("", "discovery", "truncated", reason = "candidate-limit")
                if (discovery.partial) records += Record("", "discovery", "truncated", reason = "filesystem-error")
                addManifest(archive, folder.name, "all", records, limits, budget.limit, redactionLearningComplete)
            }
            zip
        } finally {
            stageLease.close()
        }
    }

    private fun addTrustedActivation(
        context: Context,
        folder: File,
        archive: ZipOutputStream,
    ): Record? {
        val manifest = EnhancedDiagnostics.manifest(context, folder) ?: return null
        val bytes = (manifest.toString(2) + "\n").toByteArray()
        val entry = "${folder.name}/${EnhancedDiagnostics.MANIFEST_FILE}"
        archive.putNextEntry(ZipEntry(entry))
        archive.write(bytes)
        archive.closeEntry()
        return Record(entry, "app-private-activation-state", "captured", bytes.size.toLong(), bytes.size.toLong())
    }

    private fun addCandidate(
        archive: ZipOutputStream,
        candidate: Candidate,
        stage: File,
        budget: Budget,
        limits: EnhancedShareLimits,
    ): Record {
        if (budget.remaining <= 0) {
            return Record(candidate.entry, candidate.origin, "omitted", reason = "bundle-byte-limit")
        }
        if (!stage.isDirectory || !stage.canWrite()) throw StageUnavailableException(IOException("staging path is not writable"))
        val snapshot = try {
            snapshot(candidate, stage, minOf(limits.maxBytesPerFile, budget.remaining))
        } catch (error: StageUnavailableException) {
            throw error
        } catch (error: Exception) {
            if (!stage.isDirectory || !stage.canWrite()) throw StageUnavailableException(error)
            val reportedError = (error as? SourceReadException)?.cause ?: error
            return Record(candidate.entry, candidate.origin, "error", reason = reportedError.javaClass.simpleName)
        } ?: return Record(candidate.entry, candidate.origin, "omitted", reason = "unsafe-or-unavailable-source")
        if (snapshot.file.length() == 0L && candidate.linePrefix != null) {
            snapshot.file.delete()
            return Record(
                candidate.entry,
                candidate.origin,
                if (snapshot.truncated) "truncated" else "unavailable",
                snapshot.sourceBytes,
                reason = if (snapshot.truncated) "no-matching-lines-in-bounded-scan" else "no-matching-lines",
            )
        }
        return try {
            budget.remaining -= snapshot.file.length()
            archive.putNextEntry(ZipEntry(candidate.entry))
            val writer = archive.bufferedWriter()
            LogRedactor.scrubForShare(snapshot.file, writer)
            writer.flush()
            archive.closeEntry()
            Record(
                candidate.entry,
                candidate.origin,
                if (snapshot.truncated) "truncated" else "captured",
                snapshot.sourceBytes,
                snapshot.file.length(),
                if (snapshot.truncated) "per-file-or-bundle-byte-limit" else null,
            )
        } finally {
            snapshot.file.delete()
        }
    }

    private fun snapshot(candidate: Candidate, stage: File, limit: Long): Snapshot? {
        val rootPath = candidate.anchor.toPath().toAbsolutePath().normalize()
        val sourcePath = candidate.file.toPath().toAbsolutePath().normalize()
        if (!sourcePath.startsWith(rootPath) || sourcePath == rootPath || limit < 1) return null
        val target = try {
            File.createTempFile("diagnostic-", ".log", stage)
        } catch (error: Exception) {
            throw StageUnavailableException(error)
        }
        try {
            val relative = rootPath.relativize(sourcePath)
            return withSecureChannel(rootPath, relative) { input ->
                val size = readSource { input.size() }
                candidate.linePrefix?.let { prefix ->
                    val truncated = filterChannel(input, target, prefix, size, TRACE_SCAN_BYTES, limit)
                    return@withSecureChannel Snapshot(target, size, truncated)
                }
                writeStage {
                    FileOutputStream(target).channel.use { output ->
                        if (size <= limit) {
                            copy(input, output, 0, size)
                        } else {
                            val marker = "\n<diagnostic file truncated: beginning and tail retained>\n".toByteArray()
                            val content = (limit - marker.size).coerceAtLeast(0)
                            copyCompleteHead(input, output, content / 2)
                            val markerBytes = minOf(marker.size.toLong(), limit - output.position()).toInt()
                            output.write(ByteBuffer.wrap(marker, 0, markerBytes))
                            copyCompleteTail(input, output, size, (limit - output.position()).coerceAtLeast(0))
                        }
                        output.force(false)
                    }
                }
                Snapshot(target, size, size > limit)
            } ?: target.delete().let { null }
        } catch (e: Exception) {
            if (e is StageUnavailableException) {
                target.delete()
                throw e
            }
            val targetWasPresent = target.exists()
            target.delete()
            if (!stage.isDirectory || !stage.canWrite() || !targetWasPresent) throw StageUnavailableException(e)
            throw e
        }
    }

    private fun filterChannel(
        channel: SeekableByteChannel,
        target: File,
        prefix: String,
        sourceSize: Long,
        scanLimit: Long,
        outputLimit: Long,
    ): Boolean {
        val wanted = prefix.toByteArray()
        var written = 0L
        var truncated = sourceSize > scanLimit
        writeStage {
            target.outputStream().buffered().use { output ->
                fun scan(start: Long, count: Long, discardPartialFirstLine: Boolean) {
                    readSource { channel.position(start) }
                    val input = BufferedInputStream(Channels.newInputStream(channel), COPY_BUFFER_BYTES)
                    val line = ByteArrayOutputStream(1024)
                    var oversized = false
                    var scanned = 0L
                    var discard = discardPartialFirstLine
                    fun emit() {
                        val bytes = line.toByteArray()
                        if (!discard && !oversized && bytes.size >= wanted.size && wanted.indices.all { bytes[it] == wanted[it] }) {
                            if (written + bytes.size + 1 <= outputLimit) {
                                output.write(bytes)
                                output.write('\n'.code)
                                written += bytes.size + 1
                            } else truncated = true
                        }
                        line.reset()
                        oversized = false
                        discard = false
                    }
                    while (scanned < count) {
                        val value = readSource { input.read() }
                        if (value < 0) break
                        scanned++
                        if (value == '\n'.code) emit()
                        else if (!oversized && !discard) {
                            if (line.size() >= LogRedactor.MAX_LINE_LENGTH) {
                                oversized = true
                                line.reset()
                            } else line.write(value)
                        }
                    }
                    if (start + scanned >= sourceSize && (line.size() > 0 || oversized)) emit()
                }

                if (sourceSize <= scanLimit) {
                    scan(0, sourceSize, false)
                } else {
                    val head = scanLimit / 2
                    val tail = scanLimit - head
                    scan(0, head, false)
                    scan((sourceSize - tail).coerceAtLeast(0), tail, true)
                }
            }
        }
        return truncated
    }

    private fun <T> withSecureChannel(root: Path, relative: Path, block: (SeekableByteChannel) -> T): T? {
        sourceOpenerOverride.get()?.let { return it.withChannel(root, relative, block) }
        return withAndroidSecureChannel(root, relative, block)
    }

    private fun <T> withAndroidSecureChannel(root: Path, relative: Path, block: (SeekableByteChannel) -> T): T? {
        val opened = ArrayList<ParcelFileDescriptor>()
        var invokingBlock = false
        return try {
            val parts = relative.map { it.toString() }
            if (parts.isEmpty()) return null
            opened += openDirectoryDescriptor(root.toString()) ?: return null
            for (part in parts.dropLast(1)) {
                opened += openDirectoryDescriptor(procChild(opened.last(), part)) ?: return null
            }
            val leafFd = openDescriptor(procChild(opened.last(), parts.last())) ?: return null
            try {
                val stat = Os.fstat(leafFd)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L) return null
                // Android's FileInputStream(FileDescriptor) is deliberately not the descriptor owner.
                FileInputStream(leafFd).use { stream ->
                    invokingBlock = true
                    block(stream.channel)
                }
            } finally {
                runCatching { Os.close(leafFd) }
            }
        } catch (error: Exception) {
            if (invokingBlock) throw error
            null
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun openDescriptor(path: String): java.io.FileDescriptor? = runCatching {
        Os.open(
            path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
            0,
        )
    }.getOrNull()

    private fun openDirectoryDescriptor(path: String): ParcelFileDescriptor? {
        val fd = openDescriptor(path) ?: return null
        return try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) return null
            ParcelFileDescriptor.dup(fd)
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun procChild(directory: ParcelFileDescriptor, name: String): String =
        "/proc/self/fd/${directory.fd}/$name"

    private fun copy(input: SeekableByteChannel, output: FileChannel, start: Long, count: Long) {
        readSource { input.position(start) }
        var left = count
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (left > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), left).toInt())
            val read = readSource { input.read(buffer) }
            if (read < 0) break
            buffer.flip()
            while (buffer.hasRemaining()) writeStage { output.write(buffer) }
            left -= read
        }
    }

    private fun copyCompleteHead(input: SeekableByteChannel, output: FileChannel, limit: Long) {
        if (limit <= 0) return
        copy(input, output, 0, limit)
        val copied = output.position()
        readSource { input.position(0) }
        val buffer = ByteBuffer.allocate(64 * 1024)
        var scanned = 0L
        var lastComplete = 0L
        while (scanned < copied) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), copied - scanned).toInt())
            val read = readSource { input.read(buffer) }
            if (read < 0) break
            buffer.flip()
            repeat(read) { index ->
                if (buffer.get(index) == '\n'.code.toByte()) lastComplete = scanned + index + 1
            }
            scanned += read
        }
        writeStage { output.truncate(lastComplete) }
        writeStage { output.position(lastComplete) }
    }

    private fun copyCompleteTail(
        input: SeekableByteChannel,
        output: FileChannel,
        sourceSize: Long,
        limit: Long,
    ) {
        if (limit <= 0 || sourceSize <= 0) return
        var start = (sourceSize - limit).coerceAtLeast(0)
        if (start > 0) {
            readSource { input.position(start) }
            val buffered = BufferedInputStream(Channels.newInputStream(input), COPY_BUFFER_BYTES)
            while (start < sourceSize) {
                val value = readSource { buffered.read() }
                if (value < 0) return
                start++
                if (value == '\n'.code) break
            }
        }
        copy(input, output, start, (sourceSize - start).coerceAtMost(limit))
    }

    private inline fun <T> readSource(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw SourceReadException(error)
    }

    private inline fun <T> writeStage(block: () -> T): T = try {
        block()
    } catch (error: SourceReadException) {
        throw error
    } catch (error: StageUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw StageUnavailableException(error)
    }

    private fun addManifest(
        archive: ZipOutputStream,
        sessionId: String,
        category: String,
        records: List<Record>,
        limits: EnhancedShareLimits,
        categoryBudget: Long,
        redactionLearningComplete: Boolean,
    ) {
        val entries = JSONArray()
        records.take(MAX_MANIFEST_RECORDS).forEach { record ->
            entries.put(JSONObject().apply {
                put("entry", record.entry)
                put("origin", record.origin)
                put("status", record.status)
                put("sourceBytes", record.sourceBytes)
                put("capturedBytes", record.capturedBytes)
                record.reason?.let { put("reason", it) }
            })
        }
        if (records.size > MAX_MANIFEST_RECORDS) {
            entries.put(JSONObject().apply {
                put("entry", "")
                put("origin", "manifest")
                put("status", "omitted")
                put("reason", "${records.size - MAX_MANIFEST_RECORDS} additional records")
            })
        }
        val manifest = JSONObject().apply {
            put("schema", 1)
            put("sessionId", sessionId)
            put("category", category)
            put("generatedAtMillis", System.currentTimeMillis())
            put("redactionLearning", if (redactionLearningComplete) "complete" else "incomplete")
            put("limits", JSONObject().apply {
                put("maxFilesPerArchive", limits.maxFilesPerArchive)
                put("maxDiscoveredFiles", limits.maxDiscoveredFiles)
                put("maxBytesPerFile", limits.maxBytesPerFile)
                put("budgetBasis", "raw-input-snapshot-bytes")
                put("maxRawInputBytesTotal", limits.maxBytesTotal)
                put("categoryRawInputBudgetBytes", categoryBudget)
            })
            put("entries", entries)
        }.toString(2) + "\n"
        archive.putNextEntry(ZipEntry("diagnostics-manifest.json"))
        archive.write(manifest.toByteArray())
        archive.closeEntry()
    }

    private fun collect(context: Context, folder: File, maxFiles: Int, legacy: Boolean): CollectionResult {
        val out = LinkedHashMap<String, Candidate>()
        val categoryCounts = HashMap<Category, Int>()
        val perCategory = (maxFiles / Category.values().size).coerceAtLeast(1)
        var truncated = false
        var partial = false
        fun add(
            file: File,
            root: File,
            entry: String,
            origin: String,
            forcedCategory: Category? = null,
            linePrefix: String? = null,
        ) {
            val safe = safeEntry(entry) ?: return
            val category = forcedCategory ?: category(entry.removePrefix("${folder.name}/"))
            if (out.size >= maxFiles || (categoryCounts[category] ?: 0) >= perCategory) {
                truncated = true
                return
            }
            val anchor = anchorFor(context, root)
            if (out.putIfAbsent(safe, Candidate(file, anchor, safe, origin, category, linePrefix)) == null) {
                categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
            }
        }
        CRITICAL_SESSION_FILES.forEach { name ->
            File(folder, name).takeIf { it.exists() }?.let { add(it, folder, "${folder.name}/$name", "session-critical") }
        }
        File(folder, "session.log").takeIf { it.exists() }?.let { file ->
            add(
                file, folder, "${folder.name}/graphics/frame-trace.log", "guest-frame-trace",
                Category.GRAPHICS, "DROIDDECK_FRAME_TRACE ",
            )
            add(
                file, folder, "${folder.name}/graphics/process-trace.log", "guest-process-trace",
                Category.GRAPHICS, "DROIDDECK_PROCESS_TRACE ",
            )
            add(
                file, folder, "${folder.name}/games/game-lifecycle.log", "guest-game-lifecycle",
                Category.GAMES, "DROIDDECK_DIAGNOSTICS event=game_",
            )
        }
        if (SessionPaths.current()?.canonicalFile == folder.canonicalFile && !File(folder, "steam").exists()) {
            val steam = File(SessionPrefs.activeSteamRoot(context), "logs")
            val scan = directFiles(steam, remaining(maxFiles, out.size))
            truncated = truncated || scan.truncated
            partial = partial || scan.partial
            scan.files.forEach {
                add(it, steam, "${folder.name}/steam/${it.name}", "live-steam")
            }
        }
        val logs = LinuxRuntime.logDir(context)
        for ((name, root) in listOf(SessionLogShare.STORES_DIR to File(logs, SessionLogShare.STORES_DIR), SessionPaths.TOOLS_DIR to File(logs, SessionPaths.TOOLS_DIR))) {
            val scan = directFiles(root, remaining(maxFiles, out.size))
            truncated = truncated || scan.truncated
            partial = partial || scan.partial
            scan.files.asSequence().filter { recent(it) }
                .forEach { add(it, root, "$name/${it.name}", name) }
        }
        if (legacy) {
            val scan = legacyBattleNet(context)
            truncated = truncated || scan.truncated
            partial = partial || scan.partial
            scan.sources.forEach { source ->
                add(source.file, source.root, source.entry, "battlenet")
            }
        } else {
            battleNetRoots(context).forEach { (prefix, root) ->
                val scan = walk(root, remaining(maxFiles, out.size))
                truncated = truncated || scan.truncated
                partial = partial || scan.partial
                scan.files.asSequence().filter { recent(it) && it.name.endsWith(".log", true) }
                    .forEach { file ->
                        val relative = root.toPath().toAbsolutePath().normalize().relativize(file.toPath().toAbsolutePath().normalize()).joinToString("/")
                        add(file, root, "battlenet/$prefix/$relative", "battlenet")
                    }
            }
        }
        val sessionScan = walk(folder, remaining(maxFiles, out.size))
        truncated = truncated || sessionScan.truncated
        partial = partial || sessionScan.partial
        sessionScan.files.forEach { file ->
            val relative = file.toPath().toAbsolutePath().normalize()
                .let { folder.toPath().toAbsolutePath().normalize().relativize(it).joinToString("/") }
            if (relative == EnhancedDiagnostics.MANIFEST_FILE) return@forEach
            add(file, folder, "${folder.name}/$relative", "session")
        }
        return CollectionResult(out.values.toList(), truncated, partial)
    }

    private fun remaining(maxFiles: Int, found: Int): Int = (maxFiles - found + 1).coerceAtLeast(0)

    private fun walk(root: File, limit: Int): ScanResult {
        if (limit <= 0 || !root.isDirectory || Files.isSymbolicLink(root.toPath())) return ScanResult(emptyList(), false)
        val files = ArrayList<File>(limit)
        var truncated = false
        var partial = false
        var visited = 0
        val rootPath = root.toPath()
        val visitLimit = (limit * 4).coerceAtLeast(limit).coerceAtMost(MAX_DISCOVERY_VISITS)
        try {
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                private fun countVisit(): FileVisitResult {
                    visited++
                    return if (visited > visitLimit) {
                        truncated = true
                        FileVisitResult.TERMINATE
                    } else FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir == rootPath) FileVisitResult.CONTINUE else countVisit()

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (countVisit() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE
                    if (files.size >= limit) {
                        truncated = true
                        return FileVisitResult.TERMINATE
                    }
                    if (attrs.isRegularFile || Files.isSymbolicLink(file)) files += file.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    partial = true
                    return countVisit()
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) partial = true
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {
            partial = true
        }
        return ScanResult(files, truncated, partial)
    }

    private fun directFiles(root: File, limit: Int): ScanResult {
        if (limit <= 0 || !root.isDirectory || Files.isSymbolicLink(root.toPath())) return ScanResult(emptyList(), false)
        val files = ArrayList<File>(limit)
        var truncated = false
        var partial = false
        try {
            Files.newDirectoryStream(root.toPath()).use { entries ->
                val iterator = entries.iterator()
                while (iterator.hasNext() && files.size < limit) {
                    val path = iterator.next()
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                        files += path.toFile()
                    }
                }
                truncated = iterator.hasNext()
            }
        } catch (_: Exception) {
            partial = true
        }
        return ScanResult(files, truncated, partial)
    }

    private fun battleNetRoots(context: Context): List<Pair<String, File>> {
        val drive = File(BattleNet.prefix(context), "pfx/drive_c")
        val data = File(drive, "ProgramData/Battle.net")
        return listOf(
            "droiddeck" to File(LinuxRuntime.rootDir(context), "root/.cache/droiddeck/battlenet"),
            "client" to File(drive, "users/steamuser/AppData/Local/Battle.net/Logs"),
            "agent" to File(data, "Agent"),
            "setup" to File(data, "Setup"),
        )
    }

    private fun legacyBattleNet(context: Context): LegacyDiscovery {
        val drive = File(BattleNet.prefix(context), "pfx/drive_c")
        val data = File(drive, "ProgramData/Battle.net")
        val agent = File(data, "Agent")
        val setup = File(data, "Setup")
        val agentChildren = childDirectories(agent, "Agent.")
        val setupChildren = childDirectories(setup, "")
        val scans = listOf(
            legacyNewest(
                "droiddeck",
                listOf(File(LinuxRuntime.rootDir(context), "root/.cache/droiddeck/battlenet")),
                2,
                setOf("battlenet.log", "spawned.log"),
            ),
            legacyNewest("client", listOf(File(drive, "users/steamuser/AppData/Local/Battle.net/Logs")), 6),
            legacyNewest(
                "agent", listOf(File(agent, "Logs")) + agentChildren.files.map { File(it, "Logs") }, 10,
                byDir = true,
            ),
            legacyNewest("setup", setupChildren.files.map { File(it, "Logs") }, 3, byDir = true),
        )
        return LegacyDiscovery(
            scans.flatMap { it.sources },
            scans.any { it.truncated } || agentChildren.truncated || setupChildren.truncated,
            scans.any { it.partial } || agentChildren.partial || setupChildren.partial,
        )
    }

    private fun legacyNewest(
        folder: String,
        roots: List<File>,
        count: Int,
        names: Set<String>? = null,
        byDir: Boolean = false,
    ): LegacyDiscovery {
        val found = ArrayList<Pair<File, File>>()
        var truncated = false
        var partial = false
        roots.forEach { root ->
            val scan = directFiles(root, MAX_BATTLENET_SCAN)
            truncated = truncated || scan.truncated
            partial = partial || scan.partial
            found += scan.files.asSequence()
                .filter { recent(it) && it.name.endsWith(".log") && it.length() <= LEGACY_LOG_MAX_BYTES }
                .filter { names == null || it.name in names }
                .map { it to root }
                .toList()
        }
        val ordered = found.sortedByDescending { it.first.lastModified() }
        return LegacyDiscovery(
            ordered.take(count).map { (file, root) ->
                val relative = if (byDir) "$folder/${file.parentFile?.parentFile?.name}/${file.name}" else "$folder/${file.name}"
                LegacySource(file, root, "battlenet/$relative")
            },
            truncated || ordered.size > count,
            partial,
        )
    }

    private fun childDirectories(root: File, prefix: String): ScanResult {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return ScanResult(emptyList(), false)
        val found = ArrayList<File>()
        var truncated = false
        var partial = false
        try {
            Files.newDirectoryStream(root.toPath()).use { entries ->
                val iterator = entries.iterator()
                while (iterator.hasNext() && found.size < MAX_BATTLENET_DIRECTORIES) {
                    val path = iterator.next()
                    if (path.fileName.toString().startsWith(prefix) &&
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) found += path.toFile()
                }
                truncated = iterator.hasNext()
            }
        } catch (_: Exception) {
            partial = true
        }
        return ScanResult(found, truncated, partial)
    }

    private fun recent(file: File): Boolean = file.lastModified() >= System.currentTimeMillis() - RECENT_MILLIS

    private fun anchorFor(context: Context, root: File): File {
        val path = root.toPath().toAbsolutePath().normalize()
        for (anchor in listOf(context.filesDir, context.cacheDir)) {
            if (path.startsWith(anchor.toPath().toAbsolutePath().normalize())) return anchor
        }
        return root.parentFile ?: root
    }

    private fun safeEntry(name: String): String? {
        if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
        val normalized = name.replace('\\', '/').trimStart('/')
        val parts = normalized.split('/')
        if (normalized.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/") { safeComponent(it) }
    }

    private fun safeComponent(value: String): String {
        if (value in KNOWN_COMPONENTS || KNOWN_COMPONENT_PATTERNS.any { it.matches(value) }) return value
        val suffix = SAFE_SUFFIX.find(value)?.value.orEmpty().lowercase(Locale.US)
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(8).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        return "entry-$digest$suffix"
    }

    private fun category(entry: String): Category {
        val value = entry.lowercase(Locale.US)
        if (value.startsWith("battlenet/") || value.startsWith("stores/") || value.startsWith("tools/") ||
            value.contains("droiddeck-esync/") || GAME_LOG.matches(value.substringAfterLast('/')) ||
            listOf("proton", "wine", "dxvk", "vkd3d", "battle.net", "game-debug").any(value::contains)) return Category.GAMES
        if (listOf("wayland", "gamescope", "xwayland", "vulkan", "gpu", "mesa", "egl", "glx", "drm", "compositor", "frame-trace").any(value::contains)) return Category.GRAPHICS
        return Category.SESSION
    }

    private val GAME_LOG = Regex("steam-[0-9]+\\.(log|txt)")
    private val KNOWN_COMPONENTS = setOf(
        "steam", "graphics", "games", "battlenet", "client", "agent", "setup", "stores", "tools",
        "droiddeck", "droiddeck-esync", "session.log", "app.log", "wayland.log", "audio.log",
        "device.txt", "network.txt", "events.jsonl", "memory.csv", "capture.json", "crash.log",
        "frame-trace.log", "process-trace.log", "game-lifecycle.log", "launches.log", "tools.tsv", "wanted.tsv", "prefixes.json",
        "battlenet.log", "spawned.log", "flatpak-install.log", "cef_log.txt", "cef_log.previous.txt",
        "console_log.txt", "webhelper_js.txt", "controller_support.txt", "systemperfmanager.txt",
    )
    private val KNOWN_COMPONENT_PATTERNS = listOf(
        Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2,}-[a-z0-9-]{1,24}"),
        Regex("steam-[0-9]+\\.(?:log|txt)"),
        Regex("(?:battle\\.net-[0-9]+|battle\\.net-setup-[0-9]+|Agent-[0-9]+)\\.log"),
        Regex("(?:Agent\\.[0-9]+|bna_[0-9]+)"),
        Regex("stores-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.log"),
        Regex("entry-[0-9a-f]{16}(?:\\.[a-z0-9]{1,8})?"),
    )
    private val SAFE_SUFFIX = Regex("\\.[A-Za-z0-9]{1,8}$")
    private val CRITICAL_SESSION_FILES = listOf(
        "session.log", "app.log", "wayland.log", "audio.log", "device.txt", "network.txt",
        "events.jsonl", "memory.csv", "capture.json", "crash.log",
    )
    private const val RECENT_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val TRACE_SCAN_BYTES = 16L * 1024 * 1024
    private const val LEGACY_LOG_MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_BATTLENET_SCAN = 64
    private const val MAX_BATTLENET_DIRECTORIES = 64
    private const val MAX_MANIFEST_RECORDS = 512
    private const val MAX_DISCOVERY_VISITS = 4096
}
