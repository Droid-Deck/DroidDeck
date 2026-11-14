package com.droiddeck.launcher.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The absolute DT_RUNPATH and DT_RPATH directories of a 64-bit little-endian ELF object. */
object ElfRunpath {
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_STRTAB = 5
    private const val DT_RPATH = 15
    private const val DT_RUNPATH = 29

    fun read(file: File): List<String> {
        if (file.length() < 64) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(header, 0)
            if (header.getInt(0) != 0x464c457f || header.get(4).toInt() != 2 || header.get(5).toInt() != 1) return emptyList()
            val phoff = header.getLong(32)
            val phentsize = header.getShort(54).toInt() and 0xffff
            val phnum = header.getShort(56).toInt() and 0xffff
            if (phnum == 0 || phnum > 128 || phentsize < 56) return emptyList()
            val table = ByteBuffer.allocate(phentsize * phnum).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(table, phoff)
            class Load(val vaddr: Long, val offset: Long, val size: Long)
            val loads = ArrayList<Load>()
            var dynOffset = -1L
            var dynSize = 0L
            for (i in 0 until phnum) {
                val at = i * phentsize
                val type = table.getInt(at)
                val offset = table.getLong(at + 8)
                val vaddr = table.getLong(at + 16)
                val filesz = table.getLong(at + 32)
                if (type == PT_LOAD) loads.add(Load(vaddr, offset, filesz))
                if (type == PT_DYNAMIC) { dynOffset = offset; dynSize = filesz }
            }
            if (dynOffset < 0 || dynSize <= 0 || dynSize > 1 shl 20) return emptyList()
            val dynamic = ByteBuffer.allocate(dynSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(dynamic, dynOffset)
            var strtab = -1L
            val names = ArrayList<Long>()
            var at = 0
            while (at + 16 <= dynamic.capacity()) {
                val tag = dynamic.getLong(at)
                val value = dynamic.getLong(at + 8)
                if (tag == 0L) break
                if (tag == DT_STRTAB.toLong()) strtab = value
                if (tag == DT_RPATH.toLong() || tag == DT_RUNPATH.toLong()) names.add(value)
                at += 16
            }
            if (strtab < 0 || names.isEmpty()) return emptyList()
            val strtabOffset = loads.firstOrNull { strtab >= it.vaddr && strtab < it.vaddr + it.size }
                ?.let { strtab - it.vaddr + it.offset } ?: return emptyList()
            val result = ArrayList<String>()
            for (name in names) {
                val buffer = ByteBuffer.allocate(4096)
                val n = channel.read(buffer, strtabOffset + name)
                if (n <= 0) continue
                val bytes = buffer.array()
                var end = 0
                while (end < n && bytes[end].toInt() != 0) end++
                String(bytes, 0, end, Charsets.UTF_8).split(':').filter { it.startsWith("/") }.forEach { result.add(it.trimEnd('/')) }
            }
            return result
        }
    }
}
