package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReadoutTest {
    private val meminfo = """
        MemTotal:       11379920 kB
        MemFree:          527864 kB
        MemAvailable:    3235768 kB
        Buffers:            1024 kB
        SwapTotal:      16777212 kB
        SwapFree:       10358080 kB
    """.trimIndent()

    @Test fun memoryAndSwapInUseComeFromMeminfo() {
        val device = MemoryReadout.parseMeminfo(meminfo)!!
        assertEquals(11379920L - 3235768L, device.usedKb)
        assertEquals(16777212L - 10358080L, device.swapUsedKb)
        assertEquals("7.8", MemoryReadout.gb(device.usedKb))
        assertEquals("10.9", MemoryReadout.gb(device.totalKb))
        assertEquals(0L, MemoryReadout.parseMeminfo("MemTotal: 100 kB\nMemAvailable: 40 kB\n")!!.swapUsedKb)
        assertNull(MemoryReadout.parseMeminfo("MemTotal: 100 kB\n"))
    }

    @Test fun aProcessCountsItsResidentAnonymousAndSharedMemory() {
        val status = """
            Name:	Diablo IV.exe
            VmRSS:	 2000000 kB
            RssAnon:	 1500000 kB
            RssFile:	  400000 kB
            RssShmem:	  100000 kB
            VmSwap:	  300000 kB
        """.trimIndent()
        assertEquals(1600000L, MemoryReadout.residentKb(status))
        assertEquals(0L, MemoryReadout.residentKb("Name:\tkthreadd\n"))
    }

    @Test fun theGameIsWhatRunsInAWinePrefix() {
        fun env(vararg vars: String) = vars.joinToString("\u0000", postfix = "\u0000").toByteArray()
        assertTrue(MemoryReadout.inWinePrefix(env("HOME=/root", "WINEPREFIX=/root/pfx", "DISPLAY=:0")))
        assertTrue(MemoryReadout.inWinePrefix("WINEPREFIX=/x".toByteArray()))
        assertFalse(MemoryReadout.inWinePrefix(env("HOME=/root", "STEAM_COMPAT_DATA_PATH=/x", "MY_WINEPREFIX=/y")))
        assertFalse(MemoryReadout.inWinePrefix(ByteArray(0)))
    }

    @Test fun androidIsWhatIsLeftOfTheMemoryInUse() {
        val device = MemoryReadout.Device(totalKb = 10_000, availableKb = 3_000, swapTotalKb = 0, swapFreeKb = 0)
        assertEquals(3_000L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 2_000, droiddeckKb = 1_000, gpuKb = 1_000)))
        assertEquals(4_000L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 2_000, droiddeckKb = 1_000, gpuKb = null)))
        assertEquals(0L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 6_000, droiddeckKb = 2_000, gpuKb = 1_000)))
    }

    @Test fun gpuMemoryIsWhatTheGpuNodesMapIntoAProcess() {
        val maps = """
            7a00000000-7a00200000 rw-s 100000000 00:0c 512                          /dev/dri/renderD128
            7a00200000-7a00600000 rw-s 100200000 00:0c 512                          /dev/dri/renderD128
            7b00000000-7b00100000 rw-s 00000000 00:0d 300                           /dev/kgsl-3d0
            7c00000000-7c40000000 rw-p 00000000 00:00 0                             [anon:dalvik-main space]
            7d00000000-7d00400000 rw-s 00000000 00:0e 77                            /dmabuf:
            7e00000000-7e00100000 r--p 00000000 fd:00 1234                          /data/app/lib/arm64/libdroiddeck.so
        """.trimIndent()
        assertEquals(2048L + 4096L + 1024L, MemoryReadout.gpuMappedKb(maps.lineSequence()))
        assertEquals(0L, MemoryReadout.gpuMappedKb(maps.lineSequence().drop(3)))
        assertEquals(0L, MemoryReadout.gpuMappedKb(emptySequence()))
    }

    @Test fun theLogLineSaysEveryPartInMegabytes() {
        val device = MemoryReadout.Device(totalKb = 11_379_920, availableKb = 1_048_576, swapTotalKb = 16_777_212, swapFreeKb = 8_388_606)
        val split = MemoryReadout.Split(gameKb = 2_097_152, droiddeckKb = 524_288, gpuKb = 3_145_728)
        assertEquals(
            "used 10089/11113 MB, available 1024 MB, swap 8191/16383 MB | game 2048, droiddeck 512, android 4457, gpu 3072 MB",
            MemoryLog.line(device, split),
        )
        assertTrue(MemoryLog.line(device, split.copy(gpuKb = null)).endsWith("gpu ? MB"))
    }
}
