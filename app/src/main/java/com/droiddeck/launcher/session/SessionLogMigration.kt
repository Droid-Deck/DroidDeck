package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal object SessionLogMigration {
    fun run(context: Context) {
        val old = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LinuxRuntime.DEBUG_LOG_DIR)
        val target = LinuxRuntime.debugLogDir(context)
        if (!old.isDirectory || Files.isSymbolicLink(old.toPath())) return
        check(target.isDirectory || target.mkdirs())
        old.listFiles().orEmpty().filter {
            SessionPaths.isSessionFolder(it) || it.name == SessionPaths.TOOLS_DIR ||
                (it.isFile && it.name.endsWith(".log"))
        }.forEach { source ->
            try {
                move(source, target)
            } catch (e: Exception) {
                Log.w("SessionLogMigration", "could not move legacy logs into private storage", e)
            }
        }
    }

    internal fun move(source: File, target: File) {
        check(!Files.isSymbolicLink(target.toPath()))
        val paths = Files.walk(source.toPath()).use { it.iterator().asSequence().toList() }
        check(paths.all { !Files.isSymbolicLink(it) &&
            (Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isDirectory(it, NOFOLLOW_LINKS)) })
        var destination = File(target, source.name)
        var number = 1
        val stem = Regex("^session-\\d{8}-\\d{6}").find(source.name)?.value ?: source.name
        while (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) {
            val name = if (source.isFile) "${source.nameWithoutExtension}-${number++}.${source.extension}"
                else "$stem-${number++}"
            destination = File(target, name)
        }
        if (source.renameTo(destination)) return
        val stage = File.createTempFile(".migrating-", ".tmp", target)
        val versions = paths.associate { it.toFile().let { file -> file to (file.length() to file.lastModified()) } }
        try {
            if (source.isDirectory) { check(stage.delete()); check(stage.mkdir()) }
            paths.forEach { path ->
                val file = path.toFile()
                val out = if (file == source) stage else File(stage, file.relativeTo(source).path)
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    check(out.isDirectory || out.mkdirs())
                } else {
                    check(file == source || SessionLogFiles.safeFile(source, file))
                    Files.newByteChannel(path, java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
                        out.outputStream().use { output -> java.nio.channels.Channels.newInputStream(input).copyTo(output) }
                    }
                    check(out.length() == versions.getValue(file).first)
                }
            }
            check(versions.all { (file, version) -> file.length() == version.first && file.lastModified() == version.second })
            paths.asReversed().forEach { path ->
                val file = path.toFile()
                val out = if (file == source) stage else File(stage, file.relativeTo(source).path)
                check(out.setLastModified(versions.getValue(file).second))
            }
            check(stage.renameTo(destination))
            paths.asReversed().forEach { Files.delete(it) }
        } finally {
            stage.deleteRecursively()
        }
    }
}
