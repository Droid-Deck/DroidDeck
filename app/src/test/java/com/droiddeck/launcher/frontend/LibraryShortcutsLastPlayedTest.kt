package com.droiddeck.launcher.frontend

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryShortcutsLastPlayedTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun steamRoot(): File = tmp.newFolder("Steam").also { root ->
        File(root, "config").mkdirs()
        File(root, "config/loginusers.vdf").writeText(
            "\"users\"\n{\n\t\"76561198352563669\"\n\t{\n\t\t\"AccountName\"\t\t\"a\"\n\t\t\"MostRecent\"\t\t\"1\"\n\t}\n}\n")
    }

    private class Vdf {
        val out = ByteArrayOutputStream()
        fun key(type: Int, k: String) { out.write(type); out.write(k.toByteArray()); out.write(0) }
        fun map(k: String, body: Vdf.() -> Unit) { key(0, k); body(); out.write(8) }
        fun str(k: String, v: String) { key(1, k); out.write(v.toByteArray()); out.write(0) }
        fun int(k: String, v: Long) { key(2, k); out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()) }
    }

    @Test fun readsEachShortcutsLastPlayTimeByUnsignedAppId() {
        val root = steamRoot()
        val vdf = Vdf().apply {
            map("shortcuts") {
                map("0") { int("appid", 3388195926L); str("AppName", "Rayman 2"); int("LastPlayTime", 1792931476L) }
                map("1") { int("appid", 2228102537L); str("AppName", "Do Not Disturb"); int("LastPlayTime", 0L) }
                map("2") { int("appid", 1234L); str("appname", "Old"); int("lastplaytime", 1700000000L) }
            }
            out.write(8)
        }
        File(root, "userdata/392297941/config").apply { mkdirs() }.resolve("shortcuts.vdf").writeBytes(vdf.out.toByteArray())
        assertEquals(mapOf(3388195926L to 1792931476L, 1234L to 1700000000L), Library.shortcutsLastPlayed(root))
    }

    @Test fun missingOrBrokenShortcutsGiveNothing() {
        val root = steamRoot()
        assertEquals(emptyMap<Long, Long>(), Library.shortcutsLastPlayed(root))
        File(root, "userdata/392297941/config").apply { mkdirs() }.resolve("shortcuts.vdf").writeBytes(byteArrayOf(0, 's'.code.toByte()))
        assertEquals(emptyMap<Long, Long>(), Library.shortcutsLastPlayed(root))
    }
}
