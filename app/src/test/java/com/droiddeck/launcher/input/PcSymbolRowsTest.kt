package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcSymbolRowsTest {

    private val singleCharKeys = PC_SYMBOL_ROWS.flatten().filter { it.label.length == 1 }

    @Test
    fun everySingleCharacterKeyResolvesToAnEvdevCode() {
        assertTrue(singleCharKeys.isNotEmpty())
        singleCharKeys.forEach { key ->
            assertTrue("symbol '${key.label}' has no evdev code", key.code > 0)
        }
    }

    @Test
    fun shiftedSymbolsCarrySynthShiftAndPlainOnesDoNot() {
        val synthShift = singleCharKeys.filter { it.synthShift }.map { it.label }
        val plain = singleCharKeys.filterNot { it.synthShift }.map { it.label }
        listOf("@", "!", "|", "~", "{", "\"").forEach {
            assertTrue("'$it' should need a synthetic shift", it in synthShift)
        }
        listOf("1", "-", "`", ".").forEach {
            assertTrue("'$it' should not need a synthetic shift", it in plain)
        }
        assertEquals(21, synthShift.size)
        assertEquals(21, plain.size)
    }

    @Test
    fun thePageHoldsThirtyTwoPunctuationKeysAndTenDigits() {
        val digits = singleCharKeys.filter { it.label[0].isDigit() }
        val punctuation = singleCharKeys.filterNot { it.label[0].isDigit() }
        assertEquals(10, digits.size)
        assertEquals(32, punctuation.size)
        assertEquals((2..11).toList(), digits.map { it.code })
    }
}
