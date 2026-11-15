package com.droiddeck.launcher.stores.rockstar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RockstarLaunchCipherTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun decodesSyntheticCbcVectorsWithBothKeySchedules() {
        val outer = bytes("f49a68fe243848826077b14d60b2f43c00000000000000000000000000000000")
        val inner = bytes("864b81362b7d5bb23cd69b9bdb1980c500000000000000000000000000000000")
        assertArrayEquals("launch-test-0000".toByteArray(), RockstarLaunchCipher.decrypt(outer, bytes("b804c12ac4af4d8962636fa63c00dda6"), false))
        assertArrayEquals("launch-test-0000".toByteArray(), RockstarLaunchCipher.decrypt(inner, bytes("50a6ad87e83fcde3593f86ae889cdaef"), true))
    }

    @Test
    fun rejectsBadPaddingAndInvalidCipherSizes() {
        val cipher = bytes("f49a68fe243848826077b14d60b2f43c00000000000000000000000000000000")
        cipher[15] = (cipher[15].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { RockstarLaunchCipher.decrypt(cipher, ByteArray(16), false) }
        for (size in listOf(0, 15, 65552)) {
            assertThrows(IllegalArgumentException::class.java) { RockstarLaunchCipher.decrypt(ByteArray(size), ByteArray(16), false) }
        }
        assertThrows(IllegalArgumentException::class.java) { RockstarLaunchCipher.decode("A".repeat(90001), "") }
    }

    @Test
    fun rejectsExternalDocumentTypesBeforeXmlParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            RockstarRos.parse("<!DOCTYPE Response SYSTEM 'https://example.invalid/external'><Response />")
        }
    }
}
