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

    @Test fun onlyTheCurrentAndThePreviousBuildAndUserSelectionsAreKept() {
        assertEquals(setOf("b.wcp", "a.wcp", "mine.wcp"), ComponentUpdates.keep("b.wcp", "a.wcp", setOf("mine.wcp")))
        assertEquals(setOf("b.wcp"), ComponentUpdates.keep("b.wcp", ComponentUpdates.ORIGINAL, emptySet()))
    }

    private fun proton(dir: String, valve: Boolean = false) = ComponentsManager.Proton(dir, dir, File("/steam/$dir"), "/p", "1", valve)

    @Test fun autoFollowsOnlyDroidDeckProtonAndLeavesEveryOtherProtonAlone() {
        assertTrue(ComponentUpdater.follows(proton("DroidDeck-Proton-11-7.5")))
        assertFalse(ComponentUpdater.follows(proton("GE-Proton10-25")))
        assertFalse(ComponentUpdater.follows(proton("proton-cachyos-11")))
        assertFalse(ComponentUpdater.follows(proton("Proton 11.0 (ARM64)", valve = true)))
        assertFalse(ComponentUpdater.follows(proton("DroidDeck-Proton-11-7.5", valve = true)))
    }

    private fun choice(file: String, auto: Boolean = false) = ComponentsManager.Choice(file, auto, "1")

    @Test fun aBuildTheUserPickedOnAProtonIsNeverReplacedByAuto() {
        val auto = setOf("DD-dxvk-3.1.1+188-nightly.wcp", "DD-dxvk-3.1.1+190-nightly.wcp")
        assertTrue(ComponentUpdates.manual(choice("DD-dxvk-gplasync-3.1.1+190-nightly.wcp"), null, auto))
        assertTrue(ComponentUpdates.manual(choice("DD-dxvk-3.1.1+188-nightly.wcp"), "DD-dxvk-3.1.1+190-nightly.wcp", auto))
        assertFalse(ComponentUpdates.manual(choice("DD-dxvk-3.1.1+190-nightly.wcp"), "DD-dxvk-3.1.1+190-nightly.wcp", auto))
        assertFalse(ComponentUpdates.manual(choice("DD-dxvk-3.1.1+188-nightly.wcp"), null, auto))
        assertFalse(ComponentUpdates.manual(choice("original 11-7.4"), null, auto))
        assertFalse(ComponentUpdates.manual(choice("dxvk-2.6.2-linux.wcp", auto = true), null, auto))
        assertFalse(ComponentUpdates.manual(null, null, auto))
    }

    @Test fun otherProtonsGetTheirOwnBuildBackOnlyWhereAutoHadPutOne() {
        val auto = setOf("DD-dxvk-3.1.1+190-nightly.wcp")
        assertTrue(ComponentUpdates.released(choice("DD-dxvk-3.1.1+190-nightly.wcp"), auto))
        assertFalse(ComponentUpdates.released(choice("DD-dxvk-gplasync-3.1.1+190-nightly.wcp"), auto))
        assertFalse(ComponentUpdates.released(choice("dxvk-2.6.2-linux.wcp", auto = true), auto + "dxvk-2.6.2-linux.wcp"))
        assertFalse(ComponentUpdates.released(null, auto))
    }

    @Test fun oldBuildsOfAnAutoLineAreDeletedButOtherFlavoursAndKeptBuildsStay() {
        val line = ComponentUpdates.line("dxvk", false)
        val stored = listOf(
            "DD-dxvk-3.1.1+186-nightly.wcp", "DD-dxvk-3.1.1+188-nightly.wcp", "DD-dxvk-3.1.1+190-nightly.wcp",
            "DD-dxvk-arm64ec-3.1.1+188-nightly.wcp", "DD-dxvk-gplasync-3.1.1+190-nightly.wcp", "dxvk-2.6.2-linux.wcp",
        )
        val keep = ComponentUpdates.keep("DD-dxvk-3.1.1+190-nightly.wcp", "DD-dxvk-3.1.1+188-nightly.wcp", emptySet())
        assertEquals(listOf("DD-dxvk-3.1.1+186-nightly.wcp"), ComponentUpdates.stale(line, emptySet(), stored, keep))
        assertEquals(listOf("DD-dxvk-3.1.1+184-nightly.wcp", "DD-dxvk-3.1.1+186-nightly.wcp"),
            ComponentUpdates.stale(line, setOf("DD-dxvk-3.1.1+184-nightly.wcp", "DD-dxvk-3.1.1+190-nightly.wcp"), stored, keep))
        assertEquals(emptyList<String>(), ComponentUpdates.stale(line, emptySet(), stored, keep + "DD-dxvk-3.1.1+186-nightly.wcp"))
        assertEquals(emptyList<String>(), ComponentUpdates.stale(null, emptySet(), stored, keep))
    }

    @Test fun aPackageLeftSelectedOnARemovedProtonIsNoLongerKept() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "components/state.json").apply { parentFile?.mkdirs() }.writeText(JSONObject()
            .put("active", JSONObject().put("DroidDeck-Proton-11-7.4", JSONObject()
                .put("dxvk", JSONObject().put("file", "DD-dxvk-3.1.1+186-nightly.wcp").put("protonVersion", "11-7.4"))))
            .put("profiles", JSONObject().put("app:1", JSONObject().put("dxvk", "mine.wcp"))).toString())
        assertEquals(setOf("mine.wcp"), ComponentsManager.selectedPackages(context))
    }

    @Test fun pruningNeverDeletesAPackageAGameProfileSelected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "components").apply { mkdirs() }
        File(dir, "state.json").writeText(JSONObject()
            .put("profiles", JSONObject().put("app:1", JSONObject().put("dxvk", "DD-dxvk-3.1.1+186-nightly.wcp"))).toString())
        val packages = File(dir, "packages").apply { mkdirs() }
        val kept = File(packages, "DD-dxvk-3.1.1+186-nightly.wcp").apply { writeText("x") }
        val old = File(packages, "DD-dxvk-3.1.1+184-nightly.wcp").apply { writeText("x") }
        assertFalse(ComponentsManager.deleteUnused(context, kept.name))
        assertTrue(kept.isFile)
        assertEquals("DD-dxvk-3.1.1+186-nightly.wcp", JSONObject(File(dir, "state.json").readText())
            .getJSONObject("profiles").getJSONObject("app:1").getString("dxvk"))
        assertTrue(ComponentsManager.deleteUnused(context, old.name))
        assertFalse(old.isFile)
        assertFalse(ComponentsManager.deleteUnused(context, old.name))
    }

    @Test fun aFailedReleaseIsRetriedOnlyWhileTheSameBuildIsStillSelected() {
        val auto = setOf("DD-dxvk-3.1.1+188-nightly.wcp", "DD-dxvk-3.1.1+190-nightly.wcp")
        assertTrue(ComponentUpdates.released(choice("DD-dxvk-3.1.1+190-nightly.wcp"), auto, "DD-dxvk-3.1.1+190-nightly.wcp"))
        assertFalse(ComponentUpdates.released(choice("DD-dxvk-3.1.1+188-nightly.wcp"), auto, "DD-dxvk-3.1.1+190-nightly.wcp"))
        assertTrue(ComponentUpdates.released(choice("DD-dxvk-3.1.1+188-nightly.wcp"), auto, ""))
    }

    @Test fun aSelectionStaysLiveWhenAnotherInstallSharesItsProtonId() {
        fun p(version: String) = ComponentsManager.Proton("GE-Proton", "GE-Proton", File("/steam/$version"), "/p", version, false)
        val state = JSONObject().put("active", JSONObject().put("GE-Proton", JSONObject()
            .put("dxvk", JSONObject().put("file", "DD-dxvk-3.1.1+186-nightly.wcp").put("protonVersion", "2"))))
        assertTrue("DD-dxvk-3.1.1+186-nightly.wcp" in ComponentsManager.packagesInUseByAny(state, listOf(p("1"), p("2"))))
        assertTrue("DD-dxvk-3.1.1+186-nightly.wcp" in ComponentsManager.packagesInUseByAny(state, listOf(p("2"), p("1"))))
        assertFalse("DD-dxvk-3.1.1+186-nightly.wcp" in ComponentsManager.packagesInUseByAny(state, listOf(p("1"))))
    }
}
