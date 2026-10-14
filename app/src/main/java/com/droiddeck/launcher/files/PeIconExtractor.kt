package com.droiddeck.launcher.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

object PeIconExtractor {

    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val MAX_RES_SECTION = 64 * 1024 * 1024
    private const val MAX_ICON_DIM = 1024

    fun extract(file: File): Bitmap? = try {
        RandomAccessFile(file, "r").use { parse(it) }
    } catch (_: Throwable) {
        null
    }

    private fun parse(raf: RandomAccessFile): Bitmap? {
        val dos = read(raf, 0, 0x40) ?: return null
        if (u8(dos, 0) != 0x4D || u8(dos, 1) != 0x5A) return null
        val peOff = u32(dos, 0x3C).toInt()

        val coff = read(raf, peOff.toLong(), 24) ?: return null
        if (u8(coff, 0) != 0x50 || u8(coff, 1) != 0x45 || u8(coff, 2) != 0 || u8(coff, 3) != 0) return null
        val numSections = u16(coff, 6)
        val sizeOpt = u16(coff, 20)
        if (numSections <= 0 || numSections > 96 || sizeOpt <= 0) return null

        val optOff = peOff + 24L
        val opt = read(raf, optOff, sizeOpt) ?: return null
        val magic = u16(opt, 0)
        val ddOff = if (magic == 0x20B) 112 else 96
        val resDirEntry = ddOff + 2 * 8
        if (resDirEntry + 8 > opt.size) return null
        val resRVA = u32(opt, resDirEntry).toInt()
        val resSize = u32(opt, resDirEntry + 4).toInt()
        if (resRVA == 0 || resSize == 0) return null

        val secTabOff = optOff + sizeOpt
        val secTab = read(raf, secTabOff, numSections * 40) ?: return null

        var secVA = 0
        var secPtr = 0L
        var secRaw = 0
        var found = false
        for (i in 0 until numSections) {
            val o = i * 40
            if (o + 40 > secTab.size) break
            val va = u32(secTab, o + 12).toInt()
            val vSize = u32(secTab, o + 8).toInt()
            val raw = u32(secTab, o + 16).toInt()
            val pRaw = u32(secTab, o + 20)
            val span = max(vSize, raw)
            if (resRVA >= va && resRVA < va + span) {
                secVA = va; secPtr = pRaw; secRaw = raw; found = true; break
            }
        }
        if (!found || secRaw <= 0 || secRaw > MAX_RES_SECTION) return null

        val res = read(raf, secPtr, secRaw) ?: return null
        val resDirStart = resRVA - secVA
        if (resDirStart < 0 || resDirStart + 16 > res.size) return null

        val grpTypeDir = findIdSubdir(res, resDirStart, RT_GROUP_ICON, resDirStart) ?: return null
        val grpData = firstDataEntry(res, grpTypeDir, resDirStart) ?: return null
        val grp = dataEntryBytes(res, grpData, secVA) ?: return null
        if (grp.size < 6) return null

        val count = u16(grp, 4)
        if (count <= 0) return null
        var bestId = -1
        var bestScore = -1L
        for (i in 0 until count) {
            val e = 6 + i * 14
            if (e + 14 > grp.size) break
            var w = u8(grp, e); if (w == 0) w = 256
            var h = u8(grp, e + 1); if (h == 0) h = 256
            val bits = u16(grp, e + 6)
            val id = u16(grp, e + 12)
            val score = w.toLong() * h * 1000 + bits
            if (score > bestScore) { bestScore = score; bestId = id }
        }
        if (bestId < 0) return null

        val iconTypeDir = findIdSubdir(res, resDirStart, RT_ICON, resDirStart) ?: return null
        val iconIdDir = findIdSubdir(res, iconTypeDir, bestId, resDirStart) ?: return null
        val iconData = firstDataEntry(res, iconIdDir, resDirStart) ?: return null
        val img = dataEntryBytes(res, iconData, secVA) ?: return null

        return decodeIcon(img)
    }


    private fun findIdSubdir(res: ByteArray, dirIdx: Int, wantId: Int, resDirStart: Int): Int? {
        if (dirIdx + 16 > res.size) return null
        val total = u16(res, dirIdx + 12) + u16(res, dirIdx + 14)
        val start = dirIdx + 16
        for (i in 0 until total) {
            val e = start + i * 8
            if (e + 8 > res.size) break
            val id = u32(res, e)
            val off = u32(res, e + 4)
            if (id and 0x80000000L == 0L && id.toInt() == wantId) {
                if (off and 0x80000000L != 0L) return resDirStart + (off and 0x7FFFFFFFL).toInt()
                return null
            }
        }
        return null
    }

    private fun firstDataEntry(res: ByteArray, dirIdx: Int, resDirStart: Int): Int? {
        if (dirIdx + 16 > res.size) return null
        if (u16(res, dirIdx + 12) + u16(res, dirIdx + 14) == 0) return null
        val e = dirIdx + 16
        if (e + 8 > res.size) return null
        val off = u32(res, e + 4)
        return if (off and 0x80000000L != 0L) {
            firstDataEntry(res, resDirStart + (off and 0x7FFFFFFFL).toInt(), resDirStart)
        } else {
            resDirStart + off.toInt()
        }
    }

    private fun dataEntryBytes(res: ByteArray, entryIdx: Int, secVA: Int): ByteArray? {
        if (entryIdx < 0 || entryIdx + 8 > res.size) return null
        val rva = u32(res, entryIdx).toInt()
        val size = u32(res, entryIdx + 4).toInt()
        val start = rva - secVA
        if (size <= 0 || start < 0 || start + size > res.size) return null
        return res.copyOfRange(start, start + size)
    }


    private fun decodeIcon(b: ByteArray): Bitmap? {
        if (b.size >= 8 && u8(b, 0) == 0x89 && u8(b, 1) == 0x50 && u8(b, 2) == 0x4E && u8(b, 3) == 0x47) {
            return runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull()
        }
        return runCatching { decodeDib(b) }.getOrNull()
    }

    private fun decodeDib(b: ByteArray): Bitmap? {
        if (b.size < 40) return null
        val headerSize = u32(b, 0).toInt()
        val width = s32(b, 4)
        val heightFull = s32(b, 8)
        val height = heightFull / 2
        val bitCount = u16(b, 14)
        if (width <= 0 || height <= 0 || width > MAX_ICON_DIM || height > MAX_ICON_DIM) return null

        val clrUsed = u32(b, 32).toInt()
        val numColors = if (bitCount <= 8) (if (clrUsed != 0) clrUsed else 1 shl bitCount) else 0
        val paletteOff = headerSize
        val palette = IntArray(numColors)
        for (i in 0 until numColors) {
            val o = paletteOff + i * 4
            if (o + 4 > b.size) break
            palette[i] = (0xFF shl 24) or (u8(b, o + 2) shl 16) or (u8(b, o + 1) shl 8) or u8(b, o)
        }

        val xorStart = paletteOff + numColors * 4
        val rowSize = ((bitCount * width + 31) / 32) * 4
        val andRowSize = ((width + 31) / 32) * 4
        val andStart = xorStart + rowSize * height

        var hasAlpha = false
        if (bitCount == 32) {
            var y = 0
            loop@ while (y < height && !hasAlpha) {
                var x = 0
                while (x < width) {
                    val o = xorStart + y * rowSize + x * 4 + 3
                    if (o < b.size && b[o].toInt() != 0) { hasAlpha = true; break@loop }
                    x++
                }
                y++
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val srcRow = height - 1 - y
            for (x in 0 until width) {
                val argb: Int = when (bitCount) {
                    32 -> {
                        val o = xorStart + srcRow * rowSize + x * 4
                        if (o + 4 > b.size) 0 else {
                            val color = (u8(b, o + 2) shl 16) or (u8(b, o + 1) shl 8) or u8(b, o)
                            if (hasAlpha) (u8(b, o + 3) shl 24) or color
                            else maskAlpha(b, andStart, andRowSize, srcRow, x) or color
                        }
                    }
                    24 -> {
                        val o = xorStart + srcRow * rowSize + x * 3
                        if (o + 3 > b.size) 0 else
                            maskAlpha(b, andStart, andRowSize, srcRow, x) or
                                (u8(b, o + 2) shl 16) or (u8(b, o + 1) shl 8) or u8(b, o)
                    }
                    8 -> {
                        val o = xorStart + srcRow * rowSize + x
                        val idx = if (o < b.size) u8(b, o) else 0
                        val col = palette.getOrElse(idx) { 0 } and 0x00FFFFFF
                        maskAlpha(b, andStart, andRowSize, srcRow, x) or col
                    }
                    4 -> {
                        val o = xorStart + srcRow * rowSize + x / 2
                        val byte = if (o < b.size) u8(b, o) else 0
                        val idx = if (x % 2 == 0) byte shr 4 else byte and 0x0F
                        val col = palette.getOrElse(idx) { 0 } and 0x00FFFFFF
                        maskAlpha(b, andStart, andRowSize, srcRow, x) or col
                    }
                    1 -> {
                        val o = xorStart + srcRow * rowSize + x / 8
                        val byte = if (o < b.size) u8(b, o) else 0
                        val idx = (byte shr (7 - x % 8)) and 1
                        val col = palette.getOrElse(idx) { 0 } and 0x00FFFFFF
                        maskAlpha(b, andStart, andRowSize, srcRow, x) or col
                    }
                    else -> return null
                }
                pixels[y * width + x] = argb
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun maskAlpha(b: ByteArray, andStart: Int, andRowSize: Int, srcRow: Int, x: Int): Int {
        val o = andStart + srcRow * andRowSize + x / 8
        if (o >= b.size) return 0xFF shl 24
        val bit = (b[o].toInt() shr (7 - x % 8)) and 1
        return if (bit == 1) 0 else (0xFF shl 24)
    }


    private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    private fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)
    private fun u32(b: ByteArray, off: Int): Long =
        u8(b, off).toLong() or (u8(b, off + 1).toLong() shl 8) or
            (u8(b, off + 2).toLong() shl 16) or (u8(b, off + 3).toLong() shl 24)

    private fun s32(b: ByteArray, off: Int): Int = u32(b, off).toInt()

    private fun read(raf: RandomAccessFile, pos: Long, len: Int): ByteArray? {
        if (len <= 0 || pos < 0) return null
        val fileLen = raf.length()
        if (pos >= fileLen) return null
        val n = minOf(len.toLong(), fileLen - pos).toInt()
        if (n <= 0) return null
        val buf = ByteArray(n)
        raf.seek(pos)
        raf.readFully(buf)
        return buf
    }
}
