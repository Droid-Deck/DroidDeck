package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ComponentUpdatesTest {
    private fun item(file: String, comp: String, release: String = ComponentsManager.DD_NIGHTLY) =
        ComponentsManager.CatalogItem(file, comp, release, "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/x/$file", 1, "sha256:" + "0".repeat(64))

    private val catalog = listOf(
        item("DD-dxvk-3.1.1+188-nightly.wcp", "dxvk"),
        item("DD-dxvk-3.1.1+190-nightly.wcp", "dxvk"),
        item("DD-dxvk-arm64ec-3.1.1+188-nightly.wcp", "dxvk"),
        item("DD-dxvk-gplasync-3.1.1+190-nightly.wcp", "dxvk"),
        item("dxvk-3.1.1-linux.wcp", "dxvk", "Dxvk-Linux"),
        item("DD-vkd3d-proton-3.1+30-nightly.wcp", "vkd3d"),
        item("DD-vkd3d-proton-arm64ec-3.1+30-nightly.wcp", "vkd3d"),
        item("DD-FEXCore-2611+3-nightly.wcp", "fex"),
    )

    @Test fun eachLineFollowsOnlyItsOwnPublishedFlavour() {
        assertEquals("DD-dxvk-3.1.1+190-nightly.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.line("dxvk", false), emptySet())?.file)
        assertEquals("DD-dxvk-arm64ec-3.1.1+188-nightly.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.line("dxvk", true), emptySet())?.file)
        assertEquals("DD-vkd3d-proton-arm64ec-3.1+30-nightly.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.line("vkd3d", true), emptySet())?.file)
        assertEquals("DD-vkd3d-proton-3.1+30-nightly.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.line("vkd3d", false), emptySet())?.file)
        assertEquals("DD-FEXCore-2611+3-nightly.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.line("fex", true), emptySet())?.file)
        assertEquals("fex", ComponentUpdates.line("fex", false).key)
    }

    @Test fun aBadBuildIsSkippedAndNothingElseIsPickedInItsPlace() {
        val plain = ComponentUpdates.line("dxvk", false)
        assertEquals("DD-dxvk-3.1.1+188-nightly.wcp", ComponentUpdates.pick(catalog, plain, setOf("DD-dxvk-3.1.1+190-nightly.wcp"))?.file)
        assertNull(ComponentUpdates.pick(catalog, ComponentUpdates.line("fex", false), setOf("DD-FEXCore-2611+3-nightly.wcp")))
        assertNull(ComponentUpdates.pick(catalog.filter { it.release != ComponentsManager.DD_NIGHTLY }, plain, emptySet()))
    }

    private fun pe(machine: Int, pe32plus: Boolean, hybrid: Boolean?): ByteArray {
        val b = ByteBuffer.allocate(0x800).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte()).putInt(0x3c, 0x80)
        b.putInt(0x80, 0x4550).putShort(0x84, machine.toShort()).putShort(0x86, 1)
        val optional = 0x98
        val optionalSize = if (pe32plus) 240 else 224
        b.putShort(0x94, optionalSize.toShort())
        b.putShort(optional, if (pe32plus) 0x20b.toShort() else 0x10b.toShort())
        val section = optional + optionalSize
        b.putInt(section + 8, 0x1000).putInt(section + 12, 0x1000).putInt(section + 20, 0x400)
        if (hybrid != null && pe32plus) {
            b.putInt(optional + 112 + 10 * 8, 0x1100)
            b.putInt(0x500, 0x140)
            if (hybrid) b.putLong(0x500 + 0xC8, 0x180001000L)
        }
        return b.array()
    }

    @Test fun arm64ecAndArm64xImagesAreToldApartFromPlainX64ByTheirHybridMetadata() {
        assertEquals(true, ComponentUpdates.arm64ec(pe(0xaa64, true, true)))
        assertEquals(true, ComponentUpdates.arm64ec(pe(0x8664, true, true)))
        assertEquals(false, ComponentUpdates.arm64ec(pe(0x8664, true, false)))
        assertEquals(false, ComponentUpdates.arm64ec(pe(0x8664, true, null)))
        assertEquals(false, ComponentUpdates.arm64ec(pe(0x14c, false, null)))
        assertNull(ComponentUpdates.arm64ec(ByteArray(16)))
    }

    private val log = """
        [2026-11-05 20:00:00] Game process added : AppID 49520 "/root/.local/share/Steam/linuxarm64/steam-launch-wrapper -- /root/.local/share/Steam/steamrtarm64/reaper SteamLaunch AppId=49520 -- '/root/.local/share/Steam/compatibilitytools.d/DroidDeck-Proton 11-12.1'/droiddeck-proton-wrap waitforexitandrun 'Borderlands2.exe'"
        [2026-11-05 20:00:05] Game process updated : AppID 49520 "x"
        [2026-11-05 20:10:00] Game process removed: AppID 49520 "x"
        [2026-11-05 20:11:00] Game process added : AppID 413150 "/root/.local/share/Steam/steamapps/common/Proton - Experimental/proton waitforexitandrun 'Stardew Valley.exe'"
        [2026-11-05 20:11:12] Game process removed: AppID 413150 "x"
        [2026-11-05 20:12:00] Game process added : AppID 22380 "/usr/bin/true no tool here"
        [2026-11-05 20:12:02] Game process removed: AppID 22380 "x"
    """.trimIndent().lines().asSequence()

    @Test fun launchesComeFromSteamsOwnProcessLinesWithTheirProtonAndDuration() {
        val runs = ComponentUpdates.launches(log, "")
        assertEquals(listOf(
            ComponentUpdates.Launch("49520", "DroidDeck-Proton 11-12.1", "2026-11-05 20:00:00", 600),
            ComponentUpdates.Launch("413150", "Proton - Experimental", "2026-11-05 20:11:00", 12),
        ), runs)
        assertEquals(listOf("413150"), ComponentUpdates.launches(log, "2026-11-05 20:10:30").map { it.app })
    }

    private fun run(app: String, start: String, seconds: Long) = ComponentUpdates.Launch(app, "DroidDeck-Proton 11-12.1", start, seconds)

    private val trial = ComponentUpdates.Trial("new.wcp", ComponentUpdates.ORIGINAL, "2026-11-05 12:00:00", emptyMap(), emptyMap())

    @Test fun aGameThatBootedOnThePreviousBuildAndTwiceFailsOnTheNewOneRollsTheLineBack() {
        val verdict = ComponentUpdates.judge(trial, listOf(
            run("100", "2026-11-05 10:00:00", 900),
            run("100", "2026-11-05 13:00:00", 4),
            run("100", "2026-11-05 13:05:00", 6),
        ))
        assertTrue(verdict.rollBack)
        assertEquals(2, verdict.failed["100"])
    }

    @Test fun oneFailedBootOrAGameThatNeverBootedBeforeDoesNotRollBack() {
        assertFalse(ComponentUpdates.judge(trial, listOf(run("100", "2026-11-05 10:00:00", 900), run("100", "2026-11-05 13:00:00", 4))).rollBack)
        assertFalse(ComponentUpdates.judge(trial, listOf(run("200", "2026-11-05 13:00:00", 4), run("200", "2026-11-05 13:05:00", 4))).rollBack)
    }

    @Test fun aGameThatBootsOnTheNewBuildStopsCountingShortRunsAsFailures() {
        val verdict = ComponentUpdates.judge(trial, listOf(
            run("100", "2026-11-05 10:00:00", 900),
            run("100", "2026-11-05 13:00:00", 4),
            run("100", "2026-11-05 13:10:00", 1200),
            run("100", "2026-11-05 14:00:00", 4),
        ))
        assertFalse(verdict.rollBack)
        assertEquals("new.wcp", verdict.booted["100"])
        assertNull(verdict.failed["100"])
    }

    @Test fun bootsOnTheOriginalBeforeTheFirstUpdateLetTheFirstBuildRollBack() {
        val original = ComponentUpdates.Trial(ComponentUpdates.ORIGINAL, ComponentUpdates.ORIGINAL, "", emptyMap(), emptyMap())
        val before = ComponentUpdates.judge(original, listOf(run("100", "2026-11-05 10:00:00", 900), run("200", "2026-11-05 10:30:00", 3)))
        assertFalse(before.rollBack)
        assertEquals(mapOf("100" to ComponentUpdates.ORIGINAL), before.booted)
        val after = ComponentUpdates.judge(trial.copy(booted = before.booted), listOf(run("100", "2026-11-05 13:00:00", 4), run("100", "2026-11-05 13:05:00", 6)))
        assertTrue(after.rollBack)
    }

    @Test fun onlyTheCurrentAndThePreviousBuildAndUserSelectionsAreKept() {
        assertEquals(setOf("b.wcp", "a.wcp", "mine.wcp"), ComponentUpdates.keep("b.wcp", "a.wcp", setOf("mine.wcp")))
        assertEquals(setOf("b.wcp"), ComponentUpdates.keep("b.wcp", ComponentUpdates.ORIGINAL, emptySet()))
    }
}
