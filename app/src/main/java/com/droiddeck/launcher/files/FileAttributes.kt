package com.droiddeck.launcher.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File



private const val FILE_ATTRIBUTE_HIDDEN = 0x2

private const val DOSATTRIB_XATTR = "user.DOSATTRIB"

internal data class FileAttrState(
    val readOnly: Boolean,
    val hidden: Boolean,
    val hiddenSupported: Boolean,
)

private fun parseDosAttrib(raw: ByteArray): Int {
    val s = String(raw, Charsets.US_ASCII).trim()
    val hex = if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2) else s
    val digits = hex.takeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return digits.toIntOrNull(16) ?: 0
}

internal fun readFileAttrs(file: File): FileAttrState {
    val path = file.absolutePath
    val readOnly = runCatching {
        (android.system.Os.stat(path).st_mode and android.system.OsConstants.S_IWUSR) == 0
    }.getOrDefault(!file.canWrite())

    var hidden = false
    var supported = true
    try {
        hidden = (parseDosAttrib(android.system.Os.getxattr(path, DOSATTRIB_XATTR)) and FILE_ATTRIBUTE_HIDDEN) != 0
    } catch (e: android.system.ErrnoException) {
        supported = e.errno == android.system.OsConstants.ENODATA
    } catch (e: Exception) {
        supported = false
    }
    return FileAttrState(readOnly, hidden, supported)
}

internal fun setReadOnly(file: File, readOnly: Boolean): Boolean = runCatching {
    val path = file.absolutePath
    val mode = android.system.Os.stat(path).st_mode
    val writeBits = android.system.OsConstants.S_IWUSR or
        android.system.OsConstants.S_IWGRP or android.system.OsConstants.S_IWOTH
    val newMode = if (readOnly) mode and writeBits.inv()
        else mode or android.system.OsConstants.S_IWUSR
    android.system.Os.chmod(path, newMode)
    true
}.getOrElse { file.setWritable(!readOnly, true) }

internal fun setHidden(file: File, hidden: Boolean): Boolean = try {
    val path = file.absolutePath
    var attr = try {
        parseDosAttrib(android.system.Os.getxattr(path, DOSATTRIB_XATTR))
    } catch (e: android.system.ErrnoException) {
        if (e.errno == android.system.OsConstants.ENODATA) 0 else throw e
    }
    attr = if (hidden) attr or FILE_ATTRIBUTE_HIDDEN else attr and FILE_ATTRIBUTE_HIDDEN.inv()
    val value = "0x%x".format(attr).toByteArray(Charsets.US_ASCII)
    android.system.Os.setxattr(path, DOSATTRIB_XATTR, value, 0)
    true
} catch (e: Exception) {
    false
}
