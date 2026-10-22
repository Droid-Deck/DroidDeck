package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ForcedPackageTest {
    private val dxvk2 = "dxvk-2.7.1-linux.wcp"
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun unpackedCacheNameCannotEscapeStoreDirectory() {
        assertEquals("dxvk-2.7.1-linux", ComponentsManager.unpackedCacheKey(dxvk2))
        for (filename in listOf("..wcp", "...wcp")) {
            try {
                ComponentsManager.unpackedCacheKey(filename)
                fail("Expected cache traversal name to be rejected: $filename")
            } catch (_: IllegalArgumentException) {
                // Expected: the derived path would be a dot segment.
            }
        }
    }

    @Test fun adreno6xxForcesDxvk2AndNothingElseDoes() {
        val rule = ComponentsManager.FORCED_PACKAGES.single { GpuInfo.Family.A6XX in it.families }
        assertEquals("dxvk", rule.comp)
        assertEquals(dxvk2, rule.file)
        assertEquals("Dxvk-Linux", rule.release)
        assertTrue(rule.models.isEmpty())
        assertTrue(rule.matches(gpu(650, GpuInfo.Family.A6XX)))
        assertTrue(rule.matches(gpu(610, GpuInfo.Family.A6XX)))
        assertTrue(rule.matches(gpu(690, GpuInfo.Family.A6XX)))
        assertFalse(rule.matches(gpu(740, GpuInfo.Family.A7XX)))
        assertFalse(rule.matches(gpu(830, GpuInfo.Family.A8XX)))
        assertTrue(ComponentsManager.FORCED_PACKAGES.none { GpuInfo.Family.A7XX in it.families })
    }

    @Test fun aModelListOnlyMatchesThoseChips() {
        val rule = ComponentsManager.ForcedPackage(
            setOf(GpuInfo.Family.A7XX_LOW), "dxvk", dxvk2, "Dxvk-Linux", setOf(710),
        )
        assertTrue(rule.matches(gpu(710, GpuInfo.Family.A7XX_LOW)))
        assertFalse(rule.matches(gpu(720, GpuInfo.Family.A7XX_LOW)))
        assertFalse(rule.matches(gpu(710, GpuInfo.Family.A6XX)))
        assertFalse(ComponentsManager.ForcedPackage(emptySet(), "dxvk", dxvk2, "Dxvk-Linux").matches(gpu(650, GpuInfo.Family.A6XX)))
    }

    @Test fun aShippedComponentIsReplacedUntilTheUserChoosesOne() {
        assertTrue(ComponentsManager.forcedPackageWanted(null, false))
        assertTrue(ComponentsManager.forcedPackageWanted(null, true))
        assertTrue(ComponentsManager.forcedPackageWanted("", false))
        assertFalse(ComponentsManager.forcedPackageWanted(dxvk2, true))
        assertTrue(ComponentsManager.forcedPackageWanted(dxvk2, false))
        assertFalse(ComponentsManager.forcedPackageWanted("dxvk-3.1-linux.wcp", true))
        assertTrue(ComponentsManager.forcedPackageWanted("dxvk-3.1-linux.wcp", false))
        assertTrue(ComponentsManager.forcedPackageWanted("dxvk-gplasync-2.7.1-1-linux.wcp", false))
    }

    @Test fun catalogMatchPicksTheReleaseAndIgnoresAnother() {
        val wanted = item(dxvk2, "Dxvk-Linux")
        val otherRelease = item(dxvk2, "Dxvk-gplasync-Linux")
        val otherFile = item("dxvk-3.1-linux.wcp", "Dxvk-Linux")
        assertEquals(wanted, ComponentsManager.catalogMatch(listOf(otherFile, otherRelease, wanted), dxvk2, "Dxvk-Linux"))
        assertNull(ComponentsManager.catalogMatch(listOf(otherFile, otherRelease), dxvk2, "Dxvk-Linux"))
        assertNull(ComponentsManager.catalogMatch(emptyList(), dxvk2, "Dxvk-Linux"))
    }

    @Test fun thisMachineIsNotForced() {
        assertNull(ComponentsManager.ensureForcedPackages(context))
    }

    @Test fun anotherGpuIsLeftAlone() {
        proton("proton-exp")
        assertNull(install(gpu(740, GpuInfo.Family.A7XX)))
    }

    @Test fun noInstalledProtonNeedsNothing() {
        assertNull(install(gpu(650, GpuInfo.Family.A6XX)))
    }

    @Test fun aChosenPackageIsNotReplaced() {
        proton("proton-exp")
        writeState("proton-exp", "dxvk-3.1-linux.wcp", "experimental-11")
        assertNull(install(gpu(650, GpuInfo.Family.A6XX)))
    }

    @Test fun gameComponentProfilesReadOnlyKnownComponents() {
        val state = File(context.filesDir, "components/state.json")
        state.parentFile!!.mkdirs()
        state.writeText(JSONObject()
            .put("active", JSONObject())
            .put("queued", JSONObject())
            .put("profiles", JSONObject().put("42", JSONObject()
                .put("fex", "fex.wcp")
                .put("dxvk", "dxvk.wcp")
                .put("unknown", "ignored.wcp")))
            .toString())

        assertEquals(mapOf("fex" to "fex.wcp", "dxvk" to "dxvk.wcp"), ComponentsManager.gameComponents(context, "42"))
        assertTrue(ComponentsManager.gameComponents(context, "43").isEmpty())
    }

    @Test fun removingLastGameOverrideKeepsBundledComponentRestoreRow() {
        val protonName = "proton-exp"
        proton(protonName)
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")

        ComponentsManager.clearGameComponents(context, "42")

        assertFalse(ComponentsManager.gameComponents(context, "42").containsKey("dxvk"))
        assertDefaultDxvkRestoreRow(protonName)
    }

    @Test fun usingDefaultForLastGameOverrideKeepsBundledComponentRestoreRow() {
        val protonName = "proton-exp"
        proton(protonName)
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")

        ComponentsManager.setGameComponent(context, "42", "dxvk", null)

        assertFalse(ComponentsManager.gameComponents(context, "42").containsKey("dxvk"))
        assertDefaultDxvkRestoreRow(protonName)
    }

    @Test fun aNewProtonIsCapturedWhenTheLastOverrideIsReset() {
        proton("existing")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        ComponentsManager.snapshotAll(context)
        proton("new-proton")

        ComponentsManager.clearGameComponents(context, "42")

        assertDefaultDxvkRestoreRow("existing")
        assertDefaultDxvkRestoreRow("new-proton")
    }

    @Test fun anUpdatedBuildIsCapturedEvenAfterTheLastProfileWasRemoved() {
        proton("proton-exp")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        ComponentsManager.clearGameComponents(context, "42")
        proton("proton-exp", "experimental-12")

        ComponentsManager.setGameComponent(context, "43", "dxvk", null)

        assertDefaultDxvkRestoreRow("proton-exp", "experimental-12")
        assertTrue(original("proton-exp", "experimental-11").isFile)
        assertTrue(original("proton-exp", "experimental-12").isFile)
    }

    @Test fun aKnownEmptyComponentHasAnExplicitOriginalBundle() {
        proton("proton-exp", dxvk = false)
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")

        ComponentsManager.clearGameComponents(context, "42")

        assertTrue(original("proton-exp").isFile)
        val rows = launchState().readLines().map { it.split('\t') }
        assertEquals(14, rows.size)
        assertTrue(rows.all { it[3] == "dxvk" && it[5] == "-" && it[6] == "-" })
    }

    @Test fun missingOriginalsCannotBeRecapturedFromPreviouslyProfiledFiles() {
        proton("proton-exp")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        val saved = File(context.filesDir, "components/state.json").readText()
        val prior = "42\tproton-exp\texperimental-11\tdxvk\tfiles/lib/wine/dxvk/aarch64-windows/d3d11.dll\t-\t-\n"
        launchState().apply { parentFile!!.mkdirs(); writeText(prior) }

        assertThrows(IllegalStateException::class.java) {
            ComponentsManager.setGameComponent(context, "42", "dxvk", null)
        }

        assertEquals(saved, File(context.filesDir, "components/state.json").readText())
        assertEquals(prior, launchState().readText())
        assertFalse(original("proton-exp").isFile)
    }

    @Test fun aBrokenOriginalDoesNotPublishDeletionRowsOrRemoveThePreviousStore() {
        proton("proton-exp")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        val state = File(context.filesDir, "components/state.json")
        val saved = state.readText()
        val prior = "*\tother\tbuild\tdxvk\tfiles/old.dll\told-hash\t/old.dll\n"
        launchState().apply { parentFile!!.mkdirs(); writeText(prior) }
        val oldSource = File(launchState().parentFile, "store/old/files/old.dll")
        oldSource.apply { parentFile!!.mkdirs(); writeText("old component") }
        original("proton-exp").apply { parentFile!!.mkdirs(); writeText("not a bundle") }

        assertThrows(java.io.IOException::class.java) {
            ComponentsManager.clearGameComponents(context, "42")
        }

        assertEquals(saved, state.readText())
        assertEquals(prior, launchState().readText())
        assertEquals("old component", oldSource.readText())
    }

    @Test fun anArchiveWithoutAProfileIsNotAKnownEmptyOriginal() {
        proton("proton-exp")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        val bundle = original("proton-exp")
        bundle.parentFile!!.mkdirs()
        TarArchiveOutputStream(ZstdOutputStream(bundle.outputStream())).use { }

        assertThrows(IllegalArgumentException::class.java) {
            ComponentsManager.clearGameComponents(context, "42")
        }

        assertFalse(launchState().exists())
        assertEquals("custom-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
    }

    @Test fun aFailedStateCommitIsRecoveredBeforeSelectionsCanBeRead() {
        proton("proton-exp")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        val state = File(context.filesDir, "components/state.json")
        val saved = state.readText()
        val blocked = File(context.filesDir, "components/state.json.tmp")
        blocked.mkdir()
        val blocker = File(blocked, "blocker").apply { writeText("keep directory nonempty") }
        val oldSource = File(launchState().parentFile, "store/old/files/old.dll").apply {
            parentFile!!.mkdirs()
            writeText("old component")
        }

        assertThrows(java.io.IOException::class.java) {
            ComponentsManager.clearGameComponents(context, "42")
        }

        assertEquals(saved, state.readText())
        assertTrue(pendingState().isFile)
        assertTrue(launchState().isFile)
        assertEquals(Hashes.sha256(launchState()), JSONObject(pendingState().readText()).getString("launchSha256"))
        assertEquals("old component", oldSource.readText())
        assertThrows(java.io.IOException::class.java) {
            ComponentsManager.gameComponents(context, "42")
        }
        assertTrue(pendingState().isFile)

        assertTrue(blocker.delete())
        assertTrue(blocked.delete())
        assertTrue(ComponentsManager.gameComponents(context, "42").isEmpty())
        assertFalse(pendingState().exists())
        assertDefaultDxvkRestoreRow("proton-exp")
        assertTrue(ComponentsManager.gameComponents(context, "42").isEmpty())
        ComponentsManager.snapshotAll(context)
        assertFalse(oldSource.exists())
        assertDefaultDxvkRestoreRow("proton-exp")
    }

    @Test fun aFailedLaunchPublicationRetainsTheOldSelectionsAndStore() {
        proton("proton-exp")
        writeGameProfile("42", "dxvk", "custom-dxvk.wcp")
        val state = File(context.filesDir, "components/state.json")
        val saved = state.readText()
        launchState().mkdirs()
        val blocker = File(launchState(), "blocker").apply { writeText("block rename") }
        val oldSource = File(launchState().parentFile, "store/old/files/old.dll").apply {
            parentFile!!.mkdirs()
            writeText("old component")
        }

        assertThrows(IllegalStateException::class.java) {
            ComponentsManager.clearGameComponents(context, "42")
        }

        assertEquals(saved, state.readText())
        assertTrue(pendingState().isFile)
        assertEquals("custom-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
        assertFalse(pendingState().exists())
        assertEquals("old component", oldSource.readText())
        assertTrue(blocker.delete())
        assertTrue(launchState().delete())

        ComponentsManager.clearGameComponents(context, "42")

        assertTrue(ComponentsManager.gameComponents(context, "42").isEmpty())
        assertFalse(oldSource.exists())
        assertDefaultDxvkRestoreRow("proton-exp")
    }

    @Test fun interruptionBeforePublicationDiscardsOnlyThePendingSelections() {
        writeGameProfile("42", "dxvk", "old-dxvk.wcp")
        val state = File(context.filesDir, "components/state.json")
        val saved = state.readText()
        val next = JSONObject(saved).apply {
            getJSONObject("profiles").getJSONObject("42").put("dxvk", "new-dxvk.wcp")
        }
        val staged = File(launchState().parentFile, "desired.tsv.tmp").apply {
            parentFile!!.mkdirs()
            writeText("new launch table")
        }
        launchState().writeText("old launch table")
        writePendingState(next, staged)

        assertEquals("old-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
        assertEquals(saved, state.readText())
        assertEquals("old launch table", launchState().readText())
        assertFalse(pendingState().exists())
        assertFalse(staged.exists())
    }

    @Test fun identicalLaunchTablesDoNotCommitUnpublishedSettings() {
        writeGameProfile("42", "dxvk", "old-dxvk.wcp")
        val state = File(context.filesDir, "components/state.json")
        val saved = state.readText()
        val next = JSONObject(saved).put("queued", JSONObject().put("proton-exp", JSONObject()))
        val staged = File(launchState().parentFile, "desired.tsv.tmp").apply {
            parentFile!!.mkdirs()
            writeText("unchanged launch table")
        }
        launchState().writeText(staged.readText())
        writePendingState(next, staged)

        assertEquals("old-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
        assertEquals(saved, state.readText())
        assertFalse(pendingState().exists())
        assertFalse(staged.exists())
    }

    @Test fun interruptionAfterPublicationRecoversAllSelectionsAndIsIdempotent() {
        writeGameProfile("42", "dxvk", "old-dxvk.wcp")
        val next = JSONObject()
            .put("active", JSONObject().put("proton-exp", JSONObject().put("dxvk", JSONObject().put("file", dxvk2))))
            .put("queued", JSONObject().put("proton-exp", JSONObject().put("fex", JSONObject().put("file", "fex.wcp"))))
            .put("profiles", JSONObject()
                .put("42", JSONObject().put("dxvk", "new-dxvk.wcp"))
                .put("43", JSONObject().put("fex", "fex.wcp")))
            .put("profiledComponents", JSONArray(listOf("dxvk", "fex")))
        launchState().apply { parentFile!!.mkdirs(); writeText("published launch table") }
        writePendingState(next, launchState())

        assertEquals("new-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
        assertEquals("fex.wcp", ComponentsManager.gameComponents(context, "43")["fex"])
        assertEquals(next.toString(2), File(context.filesDir, "components/state.json").readText())
        assertFalse(pendingState().exists())
        assertEquals("published launch table", launchState().readText())
        assertEquals("new-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
    }

    @Test fun aPendingTransactionAfterTheStateCommitCanBeRecoveredAgain() {
        writeGameProfile("42", "dxvk", "new-dxvk.wcp")
        val state = File(context.filesDir, "components/state.json")
        val next = JSONObject(state.readText())
        launchState().apply { parentFile!!.mkdirs(); writeText("published launch table") }
        writePendingState(next, launchState())

        assertEquals("new-dxvk.wcp", ComponentsManager.gameComponents(context, "42")["dxvk"])
        assertEquals(next.toString(2), state.readText())
        assertFalse(pendingState().exists())
    }

    @Test fun anUnreadableRecoveryRecordDoesNotSilentlyReturnStaleSelections() {
        writeGameProfile("42", "dxvk", "old-dxvk.wcp")
        pendingState().writeText("incomplete recovery record")

        assertThrows(org.json.JSONException::class.java) {
            ComponentsManager.gameComponents(context, "42")
        }

        assertTrue(pendingState().isFile)
    }

    @Test fun theSamePackageOnThisProtonBuildIsNotFetchedAgain() {
        proton("proton-exp")
        writeState("proton-exp", dxvk2, "experimental-11")
        assertNull(install(gpu(650, GpuInfo.Family.A6XX)))
    }

    @Test fun aMissingListingLeavesTheShippedCopy() {
        proton("proton-exp")
        val result = install(gpu(650, GpuInfo.Family.A6XX), lookup = { _, _ -> null })
        assertEquals("$dxvk2 is not in the Nightlies listing; DXVK stays on the Proton's copy", result)
    }

    @Test fun aShippedCopyAndAProtonUpdateAreBothSwapped() {
        proton("kept")
        proton("proton-exp", "experimental-12")
        proton("other", "experimental-12")
        writeState("kept", "dxvk-3.1-linux.wcp", "experimental-11")
        writeState("proton-exp", dxvk2, "experimental-11")
        writeState("other", "dxvk-3.1-linux.wcp", "experimental-11")
        val fetched = mutableListOf<String>()
        val applied = mutableListOf<String>()
        val result = install(
            gpu(650, GpuInfo.Family.A6XX),
            lookup = { file, release -> item(file, release) },
            fetch = { fetched += it.file },
            apply = { id, file -> applied += "$id $file"; "DXVK 2.7.1 is now in $id. It applies the next time a game starts." },
        )
        assertEquals(listOf(dxvk2), fetched)
        assertEquals(listOf("other $dxvk2", "proton-exp $dxvk2"), applied)
        assertEquals(
            "DXVK 2.7.1 is now in other. It applies the next time a game starts.; " +
                "DXVK 2.7.1 is now in proton-exp. It applies the next time a game starts.",
            result,
        )
    }

    @Test fun everyProtonStillOnItsShippedCopyIsSwapped() {
        proton("one")
        proton("two")
        val applied = mutableListOf<String>()
        val result = install(
            gpu(650, GpuInfo.Family.A6XX),
            lookup = { file, release -> item(file, release) },
            fetch = {},
            apply = { id, _ -> applied += id; "DXVK 2.7.1 is now in $id. It applies the next time a game starts." },
        )
        assertEquals(listOf("one", "two"), applied)
        assertEquals(
            "DXVK 2.7.1 is now in one. It applies the next time a game starts.; " +
                "DXVK 2.7.1 is now in two. It applies the next time a game starts.",
            result,
        )
    }

    @Test fun aBrokenStoredPackageIsFetchedAgain() {
        proton("proton-exp")
        File(context.filesDir, "components/packages/$dxvk2").apply { parentFile!!.mkdirs(); writeText("not a package") }
        val fetched = mutableListOf<String>()
        install(
            gpu(650, GpuInfo.Family.A6XX),
            lookup = { file, release -> item(file, release) },
            fetch = { fetched += it.file },
            apply = { _, _ -> "swapped" },
        )
        assertEquals(listOf(dxvk2), fetched)
        assertFalse(File(context.filesDir, "components/packages/$dxvk2").isFile)
    }

    @Test fun aFailedFetchIsLeftForTheSessionToLog() {
        proton("proton-exp")
        val applied = mutableListOf<String>()
        val error = assertThrows(IllegalStateException::class.java) {
            install(
                gpu(650, GpuInfo.Family.A6XX),
                lookup = { file, release -> item(file, release) },
                fetch = { throw IllegalStateException("Download failed") },
                apply = { id, _ -> applied += id; "" },
            )
        }
        assertEquals("Download failed", error.message)
        assertTrue(applied.isEmpty())
    }

    @Test fun theCachedListingIsUsedBeforeRefreshing() {
        writeCatalog()
        assertEquals(cachedUrl, ComponentsManager.catalogItem(context, dxvk2, "Dxvk-Linux")?.url)
    }

    @Test fun aCacheMissReadsTheRefreshedListingOnce() {
        var refreshes = 0
        val refreshed = item(dxvk2, "Dxvk-Linux")
        val found = ComponentsManager.resolveCatalogItem(dxvk2, "Dxvk-Linux", { emptyList() }, { refreshes += 1; listOf(refreshed) })
        assertEquals(refreshed, found)
        assertEquals(1, refreshes)
        assertNull(ComponentsManager.resolveCatalogItem("missing.wcp", "Dxvk-Linux", { emptyList() }, { refreshes += 1; emptyList() }))
        assertEquals(2, refreshes)
        val cached = item(dxvk2, "Dxvk-Linux")
        assertEquals(cached, ComponentsManager.resolveCatalogItem(dxvk2, "Dxvk-Linux", { listOf(cached) }, { error("refresh") }))
    }

    private fun gpu(model: Int, family: GpuInfo.Family) = GpuInfo("Adreno $model", model, family, "", false)

    private fun install(
        gpu: GpuInfo,
        lookup: (String, String) -> ComponentsManager.CatalogItem? = ::refuseLookup,
        fetch: (ComponentsManager.CatalogItem) -> Unit = ::refuseFetch,
        apply: (String, String) -> String = ::refuseApply,
    ) = ComponentsManager.ensureForcedPackages(context, gpu, lookup, fetch, apply)

    private fun refuseLookup(file: String, release: String): ComponentsManager.CatalogItem? = error("catalog $file $release")

    private fun refuseFetch(item: ComponentsManager.CatalogItem) { error("fetch ${item.file}") }

    private fun refuseApply(protonId: String, file: String): String = error("apply $protonId $file")

    private fun writeGameProfile(scope: String, comp: String, file: String) {
        val state = File(context.filesDir, "components/state.json")
        state.parentFile!!.mkdirs()
        state.writeText(
            JSONObject()
                .put("active", JSONObject())
                .put("queued", JSONObject())
                .put("profiles", JSONObject().put(scope, JSONObject().put(comp, file)))
                .toString(),
        )
    }

    private fun launchState() = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components/desired.tsv")

    private fun pendingState() = File(context.filesDir, "components/state.pending.json")

    private fun writePendingState(state: JSONObject, launch: File) {
        pendingState().writeText(JSONObject()
            .put("state", state)
            .put("launchSha256", Hashes.sha256(launch))
            .toString())
    }

    private fun original(protonName: String, version: String = "experimental-11") =
        File(context.filesDir, "components/originals/$protonName/$version/dxvk.wcp")

    private fun assertDefaultDxvkRestoreRow(protonName: String, version: String = "experimental-11") {
        val row = launchState().readLines().map { it.split('\t') }.single {
            it.size == 7 && it[0] == "*" && it[1] == protonName && it[2] == version &&
                it[3] == "dxvk" && it[4].endsWith("/aarch64-windows/d3d11.dll")
        }
        assertNotEquals("-", row[6])
        val source = File(LinuxRuntime.rootDir(context), row[6].removePrefix("/"))
        assertEquals("bundled DXVK $version", source.readText())
        assertEquals(Hashes.sha256(source), row[5])
        assertTrue(original(protonName, version).isFile)
        val state = JSONObject(File(context.filesDir, "components/state.json").readText())
        assertEquals(listOf("dxvk"), state.getJSONArray("profiledComponents").let { values ->
            (0 until values.length()).map(values::getString)
        })
    }

    private fun proton(name: String, version: String = "experimental-11", dxvk: Boolean = true) {
        val dir = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/$name")
        File(dir, "files/lib/wine").mkdirs()
        File(dir, "proton").writeText("#!/bin/sh\n")
        File(dir, "version").writeText("1 $version\n")
        if (dxvk) File(dir, "files/lib/wine/dxvk/aarch64-windows/d3d11.dll").apply {
            parentFile!!.mkdirs()
            writeText("bundled DXVK $version")
        }
    }

    private fun writeState(protonId: String, file: String, protonVersion: String) {
        val state = File(context.filesDir, "components/state.json")
        state.parentFile!!.mkdirs()
        val active = if (state.isFile) JSONObject(state.readText()).getJSONObject("active") else JSONObject()
        active.put(protonId, JSONObject().put("dxvk", JSONObject().put("file", file).put("protonVersion", protonVersion)))
        state.writeText(JSONObject().put("active", active).put("queued", JSONObject()).toString())
    }

    private val cachedUrl = "https://github.com/The412Banner/Nightlies/releases/download/Dxvk-Linux/$dxvk2?cached=1"

    private fun writeCatalog() {
        val cache = File(context.filesDir, "components/catalog.json")
        cache.parentFile!!.mkdirs()
        cache.writeText(JSONObject()
            .put("fetchedAt", 1)
            .put("items", JSONArray().put(JSONObject()
                .put("file", dxvk2).put("comp", "dxvk").put("release", "Dxvk-Linux")
                .put("url", cachedUrl).put("size", 4).put("digest", "sha256:" + "ab".repeat(32))))
            .toString())
    }

    private fun item(file: String, release: String) = ComponentsManager.CatalogItem(
        file, "dxvk", release, "https://github.com/The412Banner/Nightlies/releases/download/$release/$file", 4, "sha256:" + "ab".repeat(32),
    )
}
