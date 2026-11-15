package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android's app seccomp filter kills a process with SIGSYS for rseq, set_robust_list and clone3. glibc's
 * dynamic linker makes the first two before any preloaded library (so before the hook's SIGSYS
 * handler) exists, so the call sites in the rootfs's ld.so and libc answer by themselves instead: the
 * `svc` after `mov x8, #nr` becomes a no-op for set_robust_list (glibc ignores the result) and
 * `mov x0, #-ENOSYS` for rseq and clone3 (glibc falls back from both). Under proot the same
 * answers are what proot gives.
 */
object ElfSeccompPatch {
    private const val SVC = 0xd4000001.toInt()
    private const val NOP = 0xd503201f.toInt()
    private const val MOV_X0_ENOSYS = 0x928004a0.toInt()
    private const val MOV_X8_SET_ROBUST_LIST = 0xd2800c68.toInt()
    private const val MOV_X8_CLONE3 = 0xd2803668.toInt()
    private const val MOV_X8_RSEQ = 0xd28024a8.toInt()
    private const val WINDOW = 16

    /** Patches [data] in place and returns the number of call sites changed. */
    fun patch(data: ByteArray): Int {
        if (data.size < 64 || data[0] != 0x7f.toByte() || data[1] != 'E'.code.toByte() || data[4].toInt() != 2 || data[5].toInt() != 1) return 0
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getShort(18).toInt() != 183) return 0
        val phoff = buffer.getLong(32).toInt()
        val phentsize = buffer.getShort(54).toInt() and 0xffff
        val phnum = buffer.getShort(56).toInt() and 0xffff
        var patched = 0
        for (i in 0 until phnum) {
            val at = phoff + i * phentsize
            if (at + 56 > data.size) break
            if (buffer.getInt(at) != 1 || (buffer.getInt(at + 4) and 1) == 0) continue
            val start = ((buffer.getLong(at + 8).toInt()) + 3) and 3.inv()
            val end = minOf(data.size, buffer.getLong(at + 8).toInt() + buffer.getLong(at + 32).toInt()) and 3.inv()
            var pos = start
            while (pos + 4 <= end) {
                val replacement = when (buffer.getInt(pos)) {
                    MOV_X8_SET_ROBUST_LIST -> NOP
                    MOV_X8_CLONE3, MOV_X8_RSEQ -> MOV_X0_ENOSYS
                    else -> null
                }
                if (replacement != null) {
                    var next = pos + 4
                    val limit = minOf(end, pos + 4 + WINDOW * 4)
                    while (next < limit) {
                        val insn = buffer.getInt(next)
                        if (insn == SVC) {
                            buffer.putInt(next, replacement)
                            patched++
                            break
                        }
                        // A site patched before, or x8 set again: an svc after it is another call's
                        // (a second run found clone3's thread exit there and turned it into -ENOSYS)
                        if (insn == MOV_X0_ENOSYS || (insn and 0x1f) == 8) break
                        next += 4
                    }
                }
                pos += 4
            }
        }
        return patched
    }

    /** Patches the file behind [path] (symlinks followed) if it still has unpatched call sites. */
    fun patchFile(path: File): Int {
        val target = path.canonicalFile
        if (!target.isFile) return 0
        val data = target.readBytes()
        val count = patch(data)
        if (count == 0) return 0
        val temp = File(target.parentFile, "." + target.name + ".seccomp")
        temp.writeBytes(data)
        temp.setExecutable(true, false)
        temp.setReadable(true, false)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("could not replace ${target.path}")
        }
        return count
    }
}
