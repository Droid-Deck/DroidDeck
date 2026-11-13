package com.droiddeck.launcher.stores.rockstar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal object RockstarLaunchCipher {
    private val data by lazy {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/rockstar/legacy-cipher.bin")).use { it.readBytes() }
        require(bytes.size == 86852 && bytes.copyOfRange(0, 8).contentEquals("DDRGLC01".toByteArray()))
        bytes
    }
    private val tables by lazy {
        ByteBuffer.wrap(data, 8, 86016).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { buffer -> IntArray(buffer.remaining()).also { buffer.get(it) } }
    }
    private val order = intArrayOf(0, 7, 10, 13, 1, 4, 11, 14, 2, 5, 8, 15, 3, 6, 9, 12)

    internal fun decrypt(cipher: ByteArray, iv: ByteArray, inner: Boolean): ByteArray {
        require(cipher.isNotEmpty() && cipher.size <= 65536 && cipher.size % 16 == 0 && iv.size == 16)
        val map = 86024
        val key = map + 272 + if (inner) 272 else 0
        val keys = ByteBuffer.wrap(data, key, 272).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().let { buffer -> IntArray(68).also { buffer.get(it) } }
        val output = ByteArray(cipher.size)
        var previous = iv
        for (offset in cipher.indices step 16) {
            var state = cipher.copyOfRange(offset, offset + 16)
            for (round in 0..16) {
                val next = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                for (column in 0..3) {
                    var word = keys[round * 4 + column]
                    for (slot in column * 4 until column * 4 + 4) {
                        val index = if (round == 0 || round == 1 || round == 16) slot else order[slot]
                        val table = data[map + round * 16 + index].toInt() and 255
                        require(table < 84)
                        word = word xor tables[table * 256 + (state[index].toInt() and 255)]
                    }
                    next.putInt(word)
                }
                state = next.array()
            }
            for (i in 0..15) output[offset + i] = (state[i].toInt() xor previous[i].toInt()).toByte()
            previous = cipher.copyOfRange(offset, offset + 16)
        }
        val padding = output.last().toInt() and 255
        require(padding in 1..16 && output.takeLast(padding).all { (it.toInt() and 255) == padding })
        return output.copyOf(output.size - padding)
    }

    fun decode(encoded: String, endpointIv: String): String {
        require(encoded.length <= 90000 && endpointIv.length <= 32)
        val clear = decrypt(Base64.getDecoder().decode(encoded), Base64.getDecoder().decode(endpointIv), false)
        require(clear.size > 128)
        val xml = clear.copyOf(clear.size - 128).toString(Charsets.UTF_8)
        val root = RockstarRos.parse(xml)
        require(root.tagName == "EncryptedResponse")
        val date = requireNotNull(RockstarRos.text(root, "CreationDate")).toLong()
        require(date in 0..0xffffffffL)
        val text = requireNotNull(RockstarRos.text(root, "Data"))
        require(text.length == 672 && text.all { it.digitToIntOrNull(16) != null })
        val cipher = ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val iv = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(data, data.size - 12, 12).putInt(date.toInt()).array()
        val material = decrypt(cipher, iv, true)
        require(material.size == 320)
        return material.joinToString("") { "%02X".format(it) }
    }
}
