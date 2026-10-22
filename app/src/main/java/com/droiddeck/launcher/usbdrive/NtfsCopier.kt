package com.droiddeck.launcher.usbdrive

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

interface NtfsSource {
    fun list(path: String): List<NtfsEntry>
    fun open(path: String): NtfsReadable
}

interface NtfsReadable : Closeable {
    val size: Long
    fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int
}

data class NtfsItem(val path: String, val entry: NtfsEntry)

class NtfsCopier(private val source: NtfsSource, private val bufferSize: Int = 1 shl 20) {

    data class PlannedFile(val source: String, val relative: String, val size: Long, val modified: Long)

    data class Plan(val files: List<PlannedFile>, val directories: List<String>, val bytes: Long)

    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val filesDone: Int,
        val filesTotal: Int,
        val current: String,
    )

    data class Outcome(val copied: Int, val skipped: Int, val bytes: Long, val cancelled: Boolean)

    fun plan(selection: List<NtfsItem>, cancelled: () -> Boolean = { false }): Plan {
        val files = mutableListOf<PlannedFile>()
        val directories = mutableListOf<String>()
        val pending = ArrayDeque<Pair<String, String>>()
        for (item in selection) {
            if (item.entry.isDirectory) {
                pending.addLast(item.path to item.entry.name)
            } else {
                files += PlannedFile(item.path, item.entry.name, item.entry.size, item.entry.modified)
            }
        }
        while (pending.isNotEmpty()) {
            if (cancelled()) break
            val (path, relative) = pending.removeFirst()
            directories += relative
            for (child in source.list(path).sortedWith(ntfsEntryOrder)) {
                val childPath = NtfsPaths.child(path, child.name)
                val childRelative = "$relative/${child.name}"
                if (child.isDirectory) pending.addLast(childPath to childRelative)
                else files += PlannedFile(childPath, childRelative, child.size, child.modified)
            }
        }
        return Plan(files, directories, files.sumOf { it.size })
    }

    /** Bytes the destination still has to receive; files already copied in full are skipped. */
    fun bytesNeeded(plan: Plan, destination: File): Long =
        plan.files.sumOf { file -> if (alreadyCopied(File(destination, file.relative), file)) 0L else file.size }

    fun copy(plan: Plan, destination: File, cancelled: () -> Boolean, progress: (Progress) -> Unit): Outcome {
        for (dir in plan.directories) File(destination, dir).mkdirs()
        val buffer = ByteArray(bufferSize)
        var bytesDone = 0L
        var copied = 0
        var skipped = 0
        plan.files.forEachIndexed { index, file ->
            if (cancelled()) return Outcome(copied, skipped, bytesDone, cancelled = true)
            progress(Progress(bytesDone, plan.bytes, index, plan.files.size, file.relative))
            val wanted = File(destination, file.relative)
            if (alreadyCopied(wanted, file)) {
                skipped++
                bytesDone += file.size
                return@forEachIndexed
            }
            val target = if (wanted.exists()) unique(wanted) else wanted
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, ".${target.name}.droiddeck-part")
            var finished = false
            try {
                source.open(file.source).use { input ->
                    FileOutputStream(partial).use { output ->
                        var offset = 0L
                        while (true) {
                            if (cancelled()) return Outcome(copied, skipped, bytesDone, cancelled = true)
                            val got = input.read(offset, buffer, 0, buffer.size)
                            if (got <= 0) break
                            output.write(buffer, 0, got)
                            offset += got
                            bytesDone += got
                            progress(Progress(bytesDone, plan.bytes, index, plan.files.size, file.relative))
                        }
                        output.fd.sync()
                    }
                }
                if (!partial.renameTo(target)) throw UsbDriveException("could not finish ${target.name}")
                finished = true
            } finally {
                if (!finished) partial.delete()
            }
            if (file.modified > 0) target.setLastModified(file.modified)
            copied++
        }
        progress(Progress(bytesDone, plan.bytes, plan.files.size, plan.files.size, ""))
        return Outcome(copied, skipped, bytesDone, cancelled = false)
    }

    private fun alreadyCopied(target: File, file: PlannedFile): Boolean = target.isFile && target.length() == file.size

    private fun unique(target: File): File {
        val dot = target.name.lastIndexOf('.').takeIf { it > 0 } ?: target.name.length
        val stem = target.name.substring(0, dot)
        val extension = target.name.substring(dot)
        return generateSequence(1) { it + 1 }
            .map { File(target.parentFile, "$stem ($it)$extension") }
            .first { !it.exists() }
    }
}
