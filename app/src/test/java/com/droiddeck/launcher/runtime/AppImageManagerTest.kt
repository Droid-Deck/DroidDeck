package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppImageManagerTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("appimage").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    /** An ELF header with the AppImage magic at byte 8 and [machine] at byte 18. */
    private fun image(machine: Int, type: Int = 2, magic: Boolean = true): File {
        val b = ByteArray(64)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        if (magic) { b[8] = 'A'.code.toByte(); b[9] = 'I'.code.toByte(); b[10] = type.toByte() }
        b[18] = (machine and 0xff).toByte(); b[19] = (machine shr 8).toByte()
        return File(dir, "x.AppImage").apply { writeBytes(b) }
    }

    @Test fun acceptsAnArm64TypeTwoImage() = assertNull(AppImageManager.problem(image(183)))

    @Test fun refusesX86WithAHint() = assertTrue(AppImageManager.problem(image(62))!!.contains("x86_64"))

    @Test fun refusesTypeOne() = assertTrue(AppImageManager.problem(image(183, type = 1))!!.contains("type 2"))

    @Test fun refusesAPlainProgram() = assertTrue(AppImageManager.problem(image(183, magic = false))!!.contains("not an AppImage"))

    @Test fun refusesSomethingElse() {
        val f = File(dir, "notes.AppImage").apply { writeText("hello, this is not a program at all") }
        assertTrue(AppImageManager.problem(f)!!.contains("not an AppImage"))
    }

    @Test fun idsAreTidyAndUnique() {
        assertEquals("some-app-1-2-aarch64", AppImageManager.idFor("Some_App-1.2-aarch64.AppImage", emptySet()))
        assertEquals("some-app-2", AppImageManager.idFor("Some App.AppImage", setOf("some-app")))
    }

    @Test fun readsTheMainDesktopGroup() {
        val entry = "[Desktop Entry]\nName=Duck\nName[de]=Ente\nIcon=duck\n[Desktop Action x]\nName=Other\n"
        assertEquals("Duck", AppImageManager.desktopKey(entry, "Name"))
        assertEquals("duck", AppImageManager.desktopKey(entry, "Icon"))
        assertNull(AppImageManager.desktopKey(entry, "Comment"))
    }
}
