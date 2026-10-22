package com.droiddeck.launcher.core

import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogRedactorStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun utf16AndNulDelimitedSecretsAreWithheld() {
        val token = "synthetic_private_value"
        for (encoding in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE, Charsets.UTF_16)) {
            val src = tmp.newFile()
            src.writeBytes("sessionid=$token".toByteArray(encoding))
            val out = StringWriter()
            LogRedactor.scrubForShare(src, out)
            assertFalse(out.toString().replace("\u0000", "").contains(token))
            assertTrue(out.toString().contains("withheld"))
        }
    }

    @Test fun aNulAfterTheInitialBinaryProbeWithholdsTheWholeLine() {
        val src = tmp.newFile()
        src.writeText(".".repeat(9000) + "\u0000sessionid=synthetic_private_value")
        val out = StringWriter()
        LogRedactor.scrubForShare(src, out)
        assertFalse(out.toString().contains("synthetic_private_value"))
        assertTrue(out.toString().contains("withheld"))
    }

    @Test fun aVeryLongLineIsWithheldAndTheFollowingLineIsScrubbed() {
        val src = tmp.newFile()
        src.bufferedWriter().use { writer ->
            writer.write("sessionid=synthetic_private_value ")
            repeat(128) { writer.write(".".repeat(64 * 1024)) }
            writer.write("\nready=1 token=another_private_value\n")
        }
        val out = StringWriter()
        LogRedactor.scrubForShare(src, out)
        assertTrue("oversized line was retained", out.toString().length < 1024)
        assertTrue(out.toString().contains("withheld"))
        assertTrue(out.toString().contains("ready=1 token=<redacted:token>"))
        assertFalse(out.toString().contains("private_value"))
    }

    @Test fun malformedUtf8WithholdsWithoutReturningTheUnredactedLine() {
        val src = tmp.newFile()
        src.writeBytes("sessionid=synthetic_private_value ".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        val out = StringWriter()
        LogRedactor.scrubForShare(src, out)
        assertFalse(out.toString().contains("synthetic_private_value"))
        assertTrue(out.toString().contains("withheld"))
    }

    @Test fun normalUnicodeAndLineEndingsKeepTheirDiagnosticContent() {
        val src = tmp.newFile()
        src.writeText("hazır=✓ token=synthetic_private_value\r\n\rstatus=ok\nson")
        val out = StringWriter()
        LogRedactor.scrubForShare(src, out)
        assertEquals("hazır=✓ token=<redacted:token>\n\nstatus=ok\nson\n", out.toString())
    }

    @Test fun aLogAboveTheInMemoryLimitIsStillStreamed() {
        val src = tmp.newFile()
        val block = ("status=ok " + ".".repeat(4090) + "\n").repeat(16)
        src.bufferedWriter().use { writer ->
            while (src.length() <= LogRedactor.IN_MEMORY_SCRUB_MAX) {
                writer.write(block)
                writer.flush()
            }
        }
        var chars = 0L
        val out = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) { chars += length }
            override fun flush() {}
            override fun close() {}
        }
        LogRedactor.scrubForShare(src, out)
        assertEquals(src.length(), chars)
    }
}
