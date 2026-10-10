package com.droiddeck.launcher.frontend

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Steam's app info asked anonymously: the answer read, batched, kept, and asked only when needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamPicsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** Dragon's Dogma 2's app info as Steam's anonymous PICS answer carried it (recorded 2026-10-10). */
    private val recorded: String get() = javaClass.classLoader!!.getResource("steam-pics/app_2054970.vdf")!!.readText()

    @After fun tearDown() { SteamAppInfo.pics = { SteamPics.lookup(it) } }

    @Test fun theRecordedAnswerGivesTheSharedRedistAndTheName() {
        val info = SteamPics.infoFromKeyValues(recorded)!!
        assertEquals("Dragon's Dogma 2", info.name)
        assertEquals(listOf("228988"), info.sharedDepots)
        // VC++ 2019 for the Windows components.
        assertEquals(listOf("vcredist2019_dll" to "VC++ 2019"), WinCompSources.steamDepots(info.sharedDepots))
    }

    private fun message(emsg: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(emsg or 0x80000000.toInt()).putInt(0).put(body).array()

    @Test fun aProductInfoResponseInsideAGzippedMultiIsRead() {
        val appEntry = SteamPics.Proto().varint(1, 2054970).varint(2, 123).bytes(5, recorded.toByteArray() + byteArrayOf(0)).bytes()
        val response = SteamPics.Proto().bytes(1, appEntry).varint(2, 999).bytes()
        val inner = message(8904, response)
        val packed = ByteBuffer.allocate(4 + inner.size).order(ByteOrder.LITTLE_ENDIAN).putInt(inner.size).put(inner).array()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(packed) } }.toByteArray()
        val multi = message(1, SteamPics.Proto().varint(1, packed.size.toLong()).bytes(2, gz).bytes())

        val msgs = SteamPics.messages(multi)
        assertEquals(listOf(8904), msgs.map { it.first })
        val r = SteamPics.parseInfoResponse(msgs.single().third)
        assertEquals(listOf("228988"), r.apps.getValue(2054970).sharedDepots)
        assertEquals(listOf(999), r.unknown)
        assertFalse(r.pending)
    }

    @Test fun requestsAreBatchedByTwentyFive() {
        val ids = (1..60).toList() + listOf(5, 6)
        val batches = SteamPics.batches(ids)
        assertEquals(listOf(25, 25, 10), batches.map { it.size })
        assertEquals((1..60).toList(), batches.flatten())
    }

    @Test fun answersAreKeptAndAMissIsAskedAgainOnlyAfterAWeek() {
        val calls = ArrayList<List<Int>>()
        SteamAppInfo.pics = { ids -> calls.add(ids); mapOf(2054970 to SteamPics.infoFromKeyValues(recorded), 7 to null) }
        assertTrue(SteamAppInfo.lookup(app, listOf(2054970, 7)))
        // One connection for both.
        assertEquals(listOf(listOf(2054970, 7)), calls)
        assertEquals(listOf("228988"), SteamAppInfo.info(app, 2054970)!!.sharedDepots)
        assertNull(SteamAppInfo.info(app, 7))
        // Kept: nothing asked again (the miss waits a week).
        assertFalse(SteamAppInfo.lookup(app, listOf(2054970, 7)))
        assertEquals(1, calls.size)
        assertEquals("hit", SteamAppInfo.state(app, 2054970))
        assertEquals("miss", SteamAppInfo.state(app, 7))
    }

    @Test fun noNetworkWhenTheClientsCacheHasTheApp() {
        // An appinfo.vdf with the app in it (built as in WinCompSourcesTest).
        val file = SteamAppInfo.appinfoFile(app)
        WinCompSourcesTest.writeAppinfo(file, 1145360)
        SteamAppInfo.pics = { fail("asked Steam"); null }
        assertTrue(SteamAppInfo.needLookup(app, listOf(1145360)).isEmpty())
        assertFalse(SteamAppInfo.lookup(app, listOf(1145360)))
        assertEquals(listOf("228986", "228990"), SteamAppInfo.info(app, 1145360)!!.sharedDepots)
    }

    @Test fun anUnreachableSteamKeepsNothingAndTriesAgain() {
        SteamAppInfo.pics = { null }
        assertFalse(SteamAppInfo.lookup(app, listOf(42)))
        assertEquals(listOf(42), SteamAppInfo.needLookup(app, listOf(42)))
    }
}
