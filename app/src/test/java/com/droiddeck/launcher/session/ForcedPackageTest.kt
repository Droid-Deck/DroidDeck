package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.runtime.LinuxRuntime
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

    private fun proton(name: String, version: String = "experimental-11") {
        val dir = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/$name")
        File(dir, "files/lib/wine").mkdirs()
        File(dir, "proton").writeText("#!/bin/sh\n")
        File(dir, "version").writeText("1 $version\n")
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
