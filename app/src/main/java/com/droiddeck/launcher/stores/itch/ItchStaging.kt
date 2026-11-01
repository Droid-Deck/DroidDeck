package com.droiddeck.launcher.stores.itch

import java.io.File
import java.nio.file.Files

internal object ItchStaging {
    const val DIR = ".droiddeck-update"
    const val RECORD = ".droiddeck-store.json"

    fun merge(from: File, to: File, top: Boolean = true) {
        for (child in from.listFiles().orEmpty()) {
            if (top && child.name == RECORD) continue
            val dest = File(to, child.name)
            val destPath = dest.toPath()
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath()) && dest.isDirectory && !Files.isSymbolicLink(destPath)) {
                merge(child, dest, false)
                continue
            }
            if (Files.isSymbolicLink(destPath) || dest.exists()) {
                delete(dest)
            }
            Files.move(child.toPath(), destPath)
        }
    }

    fun delete(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) file.listFiles().orEmpty().forEach { delete(it) }
        file.delete()
    }
}
