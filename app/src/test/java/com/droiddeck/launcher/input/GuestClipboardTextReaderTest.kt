package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GuestClipboardTextReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun acceptsTheCharacterLimitWithFourByteUtf8() {
        val text = "😀".repeat(GuestClipboardTextReader.MAX_CHARACTERS)
        val file = tmp.newFile().apply { writeText(text) }
        assertEquals(text, GuestClipboardTextReader.read(file))
    }

    @Test fun rejectsTextAboveTheCharacterLimit() {
        val file = tmp.newFile().apply { writeText("a".repeat(GuestClipboardTextReader.MAX_CHARACTERS + 1)) }
        assertNull(GuestClipboardTextReader.read(file))
    }

    @Test fun rejectsFilesAboveTheUtf8ByteLimit() {
        val file = tmp.newFile().apply { writeText("a".repeat(GuestClipboardTextReader.MAX_CHARACTERS * 4 + 1)) }
        assertNull(GuestClipboardTextReader.read(file))
    }
}
