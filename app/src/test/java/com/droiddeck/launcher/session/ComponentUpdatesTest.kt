package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
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
        assertNull(ComponentUpdates.pick(catalog, plain, setOf("DD-dxvk-3.1.1+190-nightly.wcp")))
        assertEquals("DD-dxvk-3.1.1+190-nightly.wcp", ComponentUpdates.pick(catalog, plain, setOf("DD-dxvk-3.1.1+188-nightly.wcp"))?.file)
        assertEquals("DD-dxvk-3.1.1+190-nightly.wcp", ComponentUpdates.pick(catalog, plain, setOf("DD-vkd3d-proton-3.1+40-nightly.wcp"))?.file)
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

    @Test fun onlyAHybridArm64ImageCountsAsArm64x() {
        assertTrue(ComponentUpdates.arm64x(pe(0xaa64, true, true)))
        assertFalse(ComponentUpdates.arm64x(pe(0x8664, true, true)))
        assertFalse(ComponentUpdates.arm64x(pe(0xaa64, true, false)))
        assertFalse(ComponentUpdates.arm64x(ByteArray(16)))
    }

    @Test fun anArm64ecLineNeedsArm64xUnlessTheProtonsOwnBuildIsKnownNotToBe() {
        val ec = ComponentUpdates.line("dxvk", true)
        assertTrue(ComponentUpdates.needsArm64x(ec, listOf(true)))
        assertTrue(ComponentUpdates.needsArm64x(ec, listOf(null)))
        assertFalse(ComponentUpdates.needsArm64x(ec, listOf(false)))
        assertTrue(ComponentUpdates.needsArm64x(ec, listOf(false, true)))
        assertFalse(ComponentUpdates.needsArm64x(ec, listOf(false, false)))
        assertFalse(ComponentUpdates.needsArm64x(ComponentUpdates.line("dxvk", false), listOf(null)))
        assertFalse(ComponentUpdates.needsArm64x(ComponentUpdates.line("fex", false), listOf(null)))
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

    @Test fun aProtonUpdatedInPlaceIsReadFromItsOwnFilesAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "proton").toFile()
        File(dir, "files/lib/wine/dxvk/aarch64-windows").apply { mkdirs() }.resolve("d3d11.dll").writeBytes(pe(0xaa64, true, true))
        val proton = ComponentsManager.Proton("Test-Proton", "Test Proton", dir, "/p", "Test-Proton-2", false)
        val state = File(context.filesDir, "components/state.json").apply { parentFile?.mkdirs() }
        fun active(version: String) = state.writeText(JSONObject().put("active", JSONObject().put("Test-Proton", JSONObject()
            .put("dxvk", JSONObject().put("file", "DD-dxvk-arm64ec-3.1.1+188-nightly.wcp").put("protonVersion", version)))).toString())

        active("Test-Proton-1")
        assertEquals(true, ComponentsManager.arm64ec(context, proton, "dxvk"))
        active("Test-Proton-2")
        assertNull(ComponentsManager.arm64ec(context, proton, "dxvk"))
    }

    @Test fun aFailedSaveKeepsTheLastGoodStateAndTheNextSaveReplacesItWhole() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "components").apply { mkdirs() }
        val state = File(dir, "updates.json")
        val saved = JSONObject().put("checkedAt", System.currentTimeMillis()).put("lines", JSONObject().put("fex", JSONObject()
            .put("current", "DD-FEXCore-2611+3-nightly.wcp").put("bad", JSONArray().put("DD-FEXCore-2611+2-nightly.wcp")))).toString()
        state.writeText(saved)
        state.setWritable(false)
        dir.setWritable(false)
        try {
            org.junit.Assume.assumeFalse(state.canWrite() || dir.canWrite())
            ComponentUpdater.ensure(context, false)
        } finally {
            dir.setWritable(true)
            state.setWritable(true)
        }
        assertEquals(saved, state.readText())

        ComponentUpdater.ensure(context, false)
        val fex = JSONObject(state.readText()).getJSONObject("lines").getJSONObject("fex")
        assertEquals("DD-FEXCore-2611+3-nightly.wcp", fex.getString("current"))
        assertEquals("DD-FEXCore-2611+2-nightly.wcp", fex.getJSONArray("bad").getString(0))
        assertFalse(state.readText() == saved)
        assertTrue(dir.list()!!.none { it.startsWith("updates.json.") })
    }

    @Test fun aLineWithAnUnfinishedRollbackTakesNoNewerBuildUntilItIsApplied() {
        assertTrue(ComponentUpdater.rollingBack(JSONObject().put("restore", JSONObject().put("proton", "DD-dxvk-2.5.wcp"))))
        assertTrue(ComponentUpdater.rollingBack(JSONObject().put("pendingNote", "rolled back")))
        assertFalse(ComponentUpdater.rollingBack(JSONObject().put("restore", JSONObject()).put("current", "DD-dxvk-2.4.wcp")))
    }

    @Test fun aRemovedProtonNoLongerHoldsItsLineInARollback() {
        val line = JSONObject().put("restore", JSONObject().put("gone", "DD-dxvk-2.5.wcp"))
        val state = JSONObject().put("lines", JSONObject().put("dxvk", line))
        ComponentUpdater.dropRemoved(state, emptyList())
        assertFalse(ComponentUpdater.rollingBack(line))
    }

    @Test fun aTwoDigitNightlyBuildIsNewerThanASingleDigitOne() {
        val items = listOf(item("DD-FEXCore-2611+9-nightly.wcp", "fex"), item("DD-FEXCore-2611+10-nightly.wcp", "fex"))
        assertEquals("DD-FEXCore-2611+10-nightly.wcp", ComponentUpdates.pick(items, ComponentUpdates.line("fex", false), emptySet())?.file)
        assertNull(ComponentUpdates.pick(items, ComponentUpdates.line("fex", false), setOf("DD-FEXCore-2611+10-nightly.wcp")))
    }

    @Test fun readsDuringSavesNeverLoseTheState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = File(context.filesDir, "components/updates.json").apply { parentFile?.mkdirs() }
        state.writeText(JSONObject().put("checkedAt", System.currentTimeMillis()).put("lines", JSONObject().put("fex", JSONObject()
            .put("current", "DD-FEXCore-2611+3-nightly.wcp").put("bad", JSONArray().put("DD-FEXCore-2611+2-nightly.wcp")))).toString())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            while (!done.get()) {
                ComponentUpdater.settle(context)
                ComponentUpdater.status(context, "fex")
            }
        }.apply { start() }
        try {
            repeat(400) { ComponentUpdater.ensure(context, false) }
        } finally {
            done.set(true)
            reader.join()
        }
        val fex = JSONObject(state.readText()).getJSONObject("lines").getJSONObject("fex")
        assertEquals("DD-FEXCore-2611+2-nightly.wcp", fex.getJSONArray("bad").getString(0))
    }

    private fun proton(dir: String, valve: Boolean = false, version: String = "$dir-1") =
        ComponentsManager.Proton(dir, dir, File("/steam/compatibilitytools.d/$dir"), "/p/$dir", version, valve)

    @Test fun autoFollowsOnlyDroidDeckProton() {
        assertTrue(ComponentUpdates.follows(proton("DroidDeck-Proton-10.0-7")))
        assertFalse(ComponentUpdates.follows(proton("DroidDeck-Proton-10.0-7", valve = true)))
        assertFalse(ComponentUpdates.follows(proton("droiddeck-proton-arm64")))
        assertFalse(ComponentUpdates.follows(proton("droiddeck-proton-11-arm64")))
        assertFalse(ComponentUpdates.follows(proton("GE-Proton10-25")))
        assertFalse(ComponentUpdates.follows(proton("proton-cachyos-10.0-20251101-slr-arm64")))
    }

    @Test fun onlyAutosOwnBuildsInOtherProtonsAreHandedBack() {
        val dd = proton("DroidDeck-Proton-10.0-7")
        val ge = proton("GE-Proton10-25")
        val valve = proton("Proton - Experimental", valve = true)
        val choices = mapOf(
            dd.id to mapOf("dxvk" to ComponentsManager.Choice("DD-dxvk-3.1.1+190-nightly.wcp", false, dd.version)),
            ge.id to mapOf(
                "dxvk" to ComponentsManager.Choice("DD-dxvk-3.1.1+190-nightly.wcp", false, ge.version),
                "vkd3d" to ComponentsManager.Choice("vkd3d-proton-3.0-mine.wcp", false, ge.version),
                "fex" to ComponentsManager.Choice("DD-FEXCore-2611+3-nightly.wcp", false, "GE-Proton10-24"),
            ),
            valve.id to mapOf(
                "fex" to ComponentsManager.Choice("DD-FEXCore-2611+3-nightly.wcp", false, valve.version),
                "dxvk" to ComponentsManager.Choice("DD-dxvk-3.1.1+190-nightly.wcp", true, valve.version),
            ),
        )
        val placed = setOf("DD-dxvk-3.1.1+190-nightly.wcp", "DD-FEXCore-2611+3-nightly.wcp")
        assertEquals(
            setOf(ge.id to "dxvk", valve.id to "fex"),
            ComponentUpdates.strays(listOf(dd, ge, valve), choices, placed).map { (p, c) -> p.id to c }.toSet(),
        )
    }

    @Test fun aProtonMissingFromDiscoveryKeepsTheHandBackPending() {
        val dd = proton("DroidDeck-Proton-10.0-7")
        val ge = proton("GE-Proton10-25")
        val placed = setOf("DD-dxvk-3.1.1+190-nightly.wcp")
        val choices = mapOf(ge.id to mapOf("dxvk" to ComponentsManager.Choice("DD-dxvk-3.1.1+190-nightly.wcp", false, ge.version)))
        assertTrue(ComponentUpdates.unseen(listOf(dd), choices, placed))
        assertFalse(ComponentUpdates.unseen(listOf(dd, ge), choices, placed))
        assertFalse(ComponentUpdates.unseen(listOf(dd), choices, emptySet()))
    }

    @Test fun aQueuedSwapIsNotReplacedByTheHandBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = File(context.filesDir, "components/state.json").apply { parentFile?.mkdirs() }
        val queued = JSONObject().put("kind", "package").put("file", "vkd3d-proton-3.0-mine.wcp").put("label", "3.0")
        state.writeText(JSONObject().put("queued", JSONObject().put("GE-Proton10-25", JSONObject().put("vkd3d", queued))).toString())
        assertFalse(ComponentsManager.restoreUnlessQueued(context, "GE-Proton10-25", "vkd3d", "GE-Proton10-25"))
        val after = JSONObject(state.readText()).getJSONObject("queued").getJSONObject("GE-Proton10-25").getJSONObject("vkd3d")
        assertEquals("vkd3d-proton-3.0-mine.wcp", after.getString("file"))
    }

    @Test fun aSourceThatFailsToListKeepsItsPreviousPackages() {
        val previous = JSONArray()
            .put(JSONObject().put("file", "DD-dxvk-arm64ec-3.1.1+198-nightly.wcp").put("release", ComponentsManager.DD_NIGHTLY))
            .put(JSONObject().put("file", "dxvk-3.1.1-linux.wcp").put("release", "Dxvk-Linux"))
        val into = JSONArray()
        ComponentsManager.keepSource(previous, ComponentsManager.DD_NIGHTLY, into)
        assertEquals(1, into.length())
        assertEquals("DD-dxvk-arm64ec-3.1.1+198-nightly.wcp", into.getJSONObject(0).getString("file"))
        ComponentsManager.keepSource(null, ComponentsManager.DD_NIGHTLY, into)
        assertEquals(1, into.length())
    }

    @Test fun everyBuildAutoEverPlacedCountsAsItsOwn() {
        val state = JSONObject().put("lines", JSONObject()
            .put("dxvk", JSONObject().put("current", "b.wcp").put("previous", ComponentUpdates.ORIGINAL)
                .put("owned", JSONArray().put("a.wcp").put("b.wcp")).put("bad", JSONArray().put("c.wcp")))
            .put("fex", JSONObject().put("current", "f2.wcp").put("previous", "f1.wcp")))
        assertEquals(setOf("a.wcp", "b.wcp", "c.wcp", "f1.wcp", "f2.wcp"), ComponentUpdater.placed(state))
        assertEquals(emptySet<String>(), ComponentUpdater.placed(JSONObject()))
    }

    @Test fun onlyTheCurrentAndThePreviousBuildAndUserSelectionsAreKept() {
        assertEquals(setOf("b.wcp", "a.wcp", "mine.wcp"), ComponentUpdates.keep("b.wcp", "a.wcp", setOf("mine.wcp")))
        assertEquals(setOf("b.wcp"), ComponentUpdates.keep("b.wcp", ComponentUpdates.ORIGINAL, emptySet()))
    }
}
