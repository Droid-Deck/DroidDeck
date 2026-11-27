package com.droiddeck.launcher.companion

import com.droiddeck.launcher.session.LoopbackWebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StardewCompanionTest {
    @Test
    fun reducesTheModsMessages() {
        var s = StardewCompanion.State()
        assertFalse(s.inGame)
        s = StardewCompanion.reduce(s, """{"type":"game_info","gameId":"stardew","saveName":"Sunny Farm"}""")
        s = StardewCompanion.reduce(s, """{"type":"sdv_day","season":"spring","day":5,"year":1,"weekday":"Fri","timeOfDay":1330,
            "weatherToday":"sunny","weatherTomorrow":"rain","gold":12500,"energy":200,"maxEnergy":270,"health":90,"maxHealth":100,"location":"Farm"}""")
        s = StardewCompanion.reduce(s, """{"type":"sdv_inventory","selected":2,"capacity":36,"items":[
            {"slot":0,"id":"(T)Axe","name":"Axe","count":1,"quality":0},
            {"slot":2,"id":"(O)24","name":"Parsnip","count":15,"quality":1},
            {"slot":3,"id":"","name":"","count":0},
            {"slot":20,"id":"(O)388","name":"Wood","count":99}]}""")
        s = StardewCompanion.reduce(s, """{"type":"sdv_quests","quests":[{"index":0,"title":"Introductions","objective":"Meet 28 people","daysLeft":-1,"complete":"0"}]}""")
        s = StardewCompanion.reduce(s, """{"type":"sdv_villagers","villagers":[
            {"name":"Abigail","npc":"Abigail","birthdayIn":3},{"name":"Haley","npc":"Haley","birthdayIn":0},{"name":"Sam","npc":"Sam","birthdayIn":20}]}""")
        s = StardewCompanion.reduce(s, """{"type":"sdv_sprite","id":"(O)24","png":"iVBORw0KGgo="}""")
        s = StardewCompanion.reduce(s, "not json")
        s = StardewCompanion.reduce(s, """{"type":"sdv_mines","deepest":40}""")

        assertTrue(s.inGame)
        assertEquals("Sunny Farm", s.save)
        assertEquals(1330, s.day!!.time)
        assertEquals(12500L, s.day!!.gold)
        assertEquals(2, s.selected)
        assertEquals(12, s.hotbar.size)
        assertEquals("Axe", s.hotbar[0]!!.name)
        assertNull(s.hotbar[1])
        assertEquals(15, s.hotbar[2]!!.count)
        assertNull(s.hotbar[3])
        assertEquals(listOf("Haley", "Abigail"), s.birthdays.map { it.name })
        assertFalse(s.quests.single().complete)
        assertEquals("iVBORw0KGgo=", s.sprites["(O)24"])

        assertFalse(StardewCompanion.reduce(s, """{"type":"game_info","saveName":""}""").inGame)
    }

    @Test
    fun commandsAndClock() {
        assertEquals("""{"type":"select_slot","slot":4}""", StardewCompanion.selectSlot(4))
        assertEquals("6:10 am", StardewCompanion.clock(610, true))
        assertEquals("12:00 pm", StardewCompanion.clock(1200, true))
        assertEquals("1:30 am", StardewCompanion.clock(2530, true))
        assertEquals("18:40", StardewCompanion.clock(1840, false))
    }

    @Test
    fun talksToALoopbackWebSocketServer() {
        val server = ServerSocket(0)
        var received: String? = null
        val worker = thread {
            server.accept().use { client ->
                val input = DataInputStream(client.getInputStream())
                val headers = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                val key = headers.first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val out = client.getOutputStream()
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                val big = "x".repeat(300)
                for (text in listOf("""{"type":"game_info","saveName":"A"}""", big)) {
                    val bytes = text.toByteArray()
                    out.write(0x81)
                    if (bytes.size < 126) out.write(bytes.size) else { out.write(126); out.write(bytes.size shr 8); out.write(bytes.size and 0xFF) }
                    out.write(bytes)
                }
                out.flush()
                val b0 = input.read()
                val len = input.read() and 0x7F
                val mask = ByteArray(4).also(input::readFully)
                val payload = ByteArray(len).also(input::readFully)
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                if (b0 == 0x81) received = String(payload)
            }
        }
        LoopbackWebSocket(server.localPort).use { ws ->
            assertEquals("""{"type":"game_info","saveName":"A"}""", ws.receive())
            assertEquals(300, ws.receive()!!.length)
            ws.send(StardewCompanion.refresh())
        }
        worker.join(5_000)
        server.close()
        assertEquals("""{"type":"refresh"}""", received)
    }

    private fun readLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
