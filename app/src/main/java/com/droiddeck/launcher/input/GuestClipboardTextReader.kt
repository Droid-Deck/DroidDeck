package com.droiddeck.launcher.input

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

internal object GuestClipboardTextReader {
    const val MAX_CHARACTERS = 262144
    private const val MAX_BYTES = MAX_CHARACTERS * 4

    fun read(file: File): String? {
        if (file.length() > MAX_BYTES) return null
        val output = ByteArrayOutputStream()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) return null
                output.write(buffer, 0, count)
            }
        }
        val text = String(output.toByteArray(), Charsets.UTF_8)
        return text.takeIf { it.codePointCount(0, it.length) <= MAX_CHARACTERS }
    }
}
