package com.droiddeck.launcher.stores.xbox

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class XboxLaunchViews {
    private val recovered = HashSet<String>()

    @Synchronized fun create(files: File): File {
        val parent = File(files, "xbox-runtime/views").canonicalFile
        check(parent == File(files.canonicalFile, "xbox-runtime/views")) { "Xbox launch storage contains a redirected path" }
        check(parent.mkdirs() || parent.isDirectory)
        if (parent.path !in recovered) {
            parent.listFiles()?.filter { it.name.startsWith("launch-") }?.forEach(::remove)
            recovered.add(parent.path)
        }
        return Files.createTempDirectory(parent.toPath(), "launch-").toFile()
    }

    companion object {
        fun remove(directory: File) {
            if (!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
