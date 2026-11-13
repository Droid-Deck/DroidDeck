package com.droiddeck.launcher.stores.rockstar

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object RockstarMetadata {
    data class Title(val id: String, val rosId: Int, val exe: String)

    fun find(folder: File): Title? {
        val root = folder.canonicalFile
        var level = listOf(root)
        repeat(4) {
            val entries = level.map { it.listFiles().orEmpty() }
            val metadata = entries.flatMap { files -> files.filter { it.name.equals("title.rgl", true) } }
            for (file in metadata.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalPath.startsWith(root.path + File.separator) }) {
                if (file.length() in 112..1_048_576) runCatching { decode(file.readBytes()) }.getOrNull()?.let {
                    return it.copy(exe = File(file.parentFile, it.exe).relativeTo(root).invariantSeparatorsPath)
                }
            }
            level = entries.flatMap { files -> files.asSequence().filter {
                it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalPath.startsWith(root.path + File.separator)
            }.take(256).toList() }.take(256)
        }
        return null
    }

    fun decode(bytes: ByteArray): Title? = runCatching {
        require(bytes.size in 112..1_048_576 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(82, 71, 76, 77)))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(4) == 1 && header.getInt(8) == bytes.size - 80 && (bytes.size - 80) % 16 == 0)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), IvParameterSpec(bytes.copyOfRange(80, 96)))
        val clear = cipher.doFinal(bytes, 96, bytes.size - 96)
        val pad = clear.last().toInt() and 255
        val size = if (pad in 1..16 && clear.takeLast(pad).all { (it.toInt() and 255) == pad }) clear.size - pad
            else clear.indexOfLast { it != 0.toByte() } + 1
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(clear, 0, size)).toString().removePrefix("\uFEFF")
        val json = JSONObject(text)
        val id = json.getString("titleId")
        val rosId = json.get("rosTitleId").toString().takeIf { Regex("[1-9][0-9]{0,4}").matches(it) }?.toInt() ?: return null
        val exe = json.getString("principalFileName").replace('\\', '/')
        require(Regex("[a-z][a-z0-9_]{0,63}").matches(id) && rosId in 1..65535)
        require(exe.isNotEmpty() && !exe.startsWith('/') && ':' !in exe && '\u0000' !in exe && exe.split('/').none { it == ".." })
        Title(id, rosId, exe)
    }.getOrNull()
}
