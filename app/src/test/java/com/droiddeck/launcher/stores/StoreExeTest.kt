package com.droiddeck.launcher.stores

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreExeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun elf(path: String, machine: Int = 0x3E, bits: Int = 2, order: Int = 1, size: Int = 64): File {
        val file = File(tmp.root, path).apply { parentFile!!.mkdirs() }
        val bytes = ByteArray(maxOf(size, 20))
        bytes[0] = 0x7F; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = bits.toByte(); bytes[5] = order.toByte(); bytes[6] = 1
        bytes[16] = 2
        bytes[18] = (machine and 0xFF).toByte(); bytes[19] = (machine shr 8).toByte()
        file.writeBytes(if (size < 20) bytes.copyOf(size) else bytes)
        return file
    }

    private fun text(path: String): File = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\n") }

    @Test fun anX86ElfIsRecognisedAndOtherFilesAreNot() {
        assertTrue(StoreExe.isX86Elf(elf("a.x86_64")))
        assertTrue(StoreExe.isX86Elf(elf("b", machine = 0x03, bits = 1)))
        assertFalse(StoreExe.isX86Elf(elf("arm", machine = 0xB7)))
        assertFalse(StoreExe.isX86Elf(elf("big", order = 2)))
        assertFalse(StoreExe.isX86Elf(text("run.sh")))
        assertFalse(StoreExe.isX86Elf(File(tmp.root, "missing")))
        assertFalse(StoreExe.isX86Elf(elf("short", size = 10)))
    }

    @Test fun theProgramIsFoundAndLibrariesAreSkipped() {
        elf("LSDR.x86_64", size = 100)
        elf("UnityPlayer.so", size = 5000)
        elf("LSDR_Data/Plugins/x86_64/libfmod.so.12", size = 9000)
        text("readme.txt")
        assertEquals("LSDR.x86_64", StoreExe.pickLinux(tmp.root, null, "LSDR Revamped"))
    }

    @Test fun theTitleDecidesBetweenTwoPrograms() {
        elf("Other.x86_64", size = 5000)
        elf("Game.x86_64", size = 100)
        assertEquals("Game.x86_64", StoreExe.pickLinux(tmp.root, null, "Game"))
    }

    @Test fun withoutATitleMatchTheShallowestWinsThenTheLargest() {
        elf("tools/deep.x86_64", size = 9000)
        elf("small.x86_64", size = 100)
        elf("big.x86_64", size = 900)
        assertEquals("big.x86_64", StoreExe.pickLinux(tmp.root, null, "Unrelated"))
    }

    @Test fun helperProgramsAndHiddenFoldersAreSkipped() {
        elf("crashhandler", size = 800)
        elf("UnityCrashHandler64", size = 800)
        elf(".hidden/Game.x86_64", size = 800)
        assertEquals("", StoreExe.pickLinux(tmp.root, null, "Game"))
    }

    @Test fun aHintNamingAProgramIsUsedAndAWindowsExeIsNeverPicked() {
        elf("bin/run", size = 100)
        elf("other", size = 5000)
        File(tmp.root, "game.exe").writeText("MZ")
        assertEquals("bin/run", StoreExe.pickLinux(tmp.root, "bin/run", "Whatever"))
        assertEquals("other", StoreExe.pickLinux(tmp.root, "game.exe", "Zzz"))
    }

    @Test fun anEmptyFolderGivesNothing() {
        assertEquals("", StoreExe.pickLinux(tmp.root, null, "Game"))
    }
}
