package com.droiddeck.launcher.stores.itch

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

internal object ItchArchives {
    private val PACKED = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz")

    fun kind(file: File, filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        if (extension == "exe") return "exe"
        return signature(file) ?: extension
    }

    private fun signature(file: File): String? {
        val head = runCatching { FileInputStream(file).use { input -> ByteArray(300).also { input.read(it) } } }.getOrNull()
            ?: return null
        fun at(i: Int, vararg bytes: Int) = bytes.indices.all { head.getOrNull(i + it) == bytes[it].toByte() }
        return when {
            at(0, 0x50, 0x4B) -> "zip"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
            at(0, 0x52, 0x61, 0x72, 0x21) -> "rar"
            at(0, 0x1F, 0x8B) -> "gz"
            at(0, 0x42, 0x5A, 0x68) -> "bz2"
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "xz"
            at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> "tar"
            else -> null
        }
    }

    fun inner(folder: File): File? {
        val found = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (Files.isSymbolicLink(file.toPath())) continue
                if (file.isFile && signature(file) in PACKED) found.add(file)
                else if (file.isDirectory && depth < 2 && !file.name.startsWith(".")) walk(file, depth + 1)
            }
        }
        walk(folder, 0)
        return found.maxByOrNull { it.length() }
    }
}
