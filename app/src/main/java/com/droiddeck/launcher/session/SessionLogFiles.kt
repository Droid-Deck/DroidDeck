package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.LogRedactor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Writer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

internal object SessionLogFiles {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val STEAM_MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_LINE_CHARS = 64 * 1024

    val steamNames = setOf(
        "bootstrap_log.txt", "connection_log.txt", "console_log.txt", "content_log.txt",
        "controller.txt", "compat_log.txt", "cef_log.txt", "cef_log.previous.txt",
        "shader_log.txt", "systemaudiomanager.txt", "steamwebhelper.log", "webhelper.txt",
        "steamui_html.txt", "steamui_system.txt", "steamui_library.txt", "steamui_login.txt",
        "remote_connections.txt", "streaming_log.txt", "video.txt", "vrclient_steam.txt"
    )
    private val paths = setOf(
        "session.log", "session.log.1", "app.log", "crash.log", "crash-app.txt",
        "wayland.log", "audio.log", "device.txt", "network.txt", "events.jsonl",
        "desktop.log", "steam-desktop.log", "steam.log", "pad.log", "storage.log",
        "ended-without-teardown.txt", "droiddeck-esync/launches.log",
        "droiddeck-esync/tools.tsv", "droiddeck-esync/wanted.tsv",
        "droiddeck-esync/prefixes.json", "droiddeck-fex/status.json", "droiddeck-fex/Config.json"
    ) + steamNames.map { "steam/$it" }

    fun candidates(folder: File): List<File> = paths.map { File(folder, it) }
        .filter { Files.exists(it.toPath(), NOFOLLOW_LINKS) }

    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(file.toPath()).use { stream ->
            stream.iterator().asSequence().toList().asReversed().forEach { Files.deleteIfExists(it) }
        }
    }

    fun safeFile(root: File, file: File): Boolean {
        val base = root.toPath().toAbsolutePath().normalize()
        var path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(base) || path == base) return false
        while (path != base) {
            if (Files.isSymbolicLink(path)) return false
            path = path.parent ?: return false
        }
        return !Files.isSymbolicLink(base) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
    }

    fun scrubTo(root: File, file: File, out: Writer, maxBytes: Long = MAX_BYTES) {
        if (!safeFile(root, file)) throw IOException("unsafe path")
        Files.newByteChannel(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            var remaining = channel.size()
            if (remaining >= maxBytes) throw IOException("size limit")
            val source = Channels.newInputStream(channel)
            val snapshot = object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0L) return -1
                    val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("truncated snapshot")
                    remaining -= count
                    return count
                }
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val reader = InputStreamReader(snapshot, decoder)
            val line = StringBuilder()
            val buffer = CharArray(4096)
            fun writeLine() {
                out.write(LogRedactor.redact(line.toString().removeSuffix("\r")))
                out.write("\n")
                line.setLength(0)
            }
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val char = buffer[i]
                    if (char == '\u0000') throw IOException("binary data")
                    if (char == '\n') writeLine() else {
                        if (line.length >= MAX_LINE_CHARS) throw IOException("line limit")
                        line.append(char)
                    }
                }
            }
            if (line.isNotEmpty()) {
                if (channel.size() != channel.position()) out.write("<incomplete live line withheld>\n")
                else writeLine()
            }
        }
    }
}
