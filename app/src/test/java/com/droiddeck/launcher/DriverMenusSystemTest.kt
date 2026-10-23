package com.droiddeck.launcher

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SystemVulkanComponentStore
import com.droiddeck.launcher.gpu.SystemVulkanManifest
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.DriverRow
import com.droiddeck.launcher.ui.GPU_MODE_PAIR
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DriverMenusSystemTest {
    private lateinit var activity: Activity
    private val gpu = GpuInfo("Mali-G715", 0, GpuInfo.Family.MALI, "Tensor", false)

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setLinuxDriver(activity, "")
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
    }

    @After fun tearDown() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        File(activity.filesDir, "system_vulkan").deleteRecursively()
        File(activity.filesDir, "components/system-vulkan").deleteRecursively()
        File(activity.filesDir, "repository-sources").deleteRecursively()
        File(activity.cacheDir, "repository-metadata").deleteRecursively()
        activity.finish()
    }

    @Test fun legacyRuntimeHasAnHonestOriginAndIsSelectedBeforeFirstLaunch() {
        val digest = "a".repeat(64)
        legacy(digest)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)

        menus.refreshDrivers()

        val state = menus.state()
        val row = state.systemRows.single()
        assertEquals(SystemVulkanComponentStore.rowId(digest), state.linuxSelected)
        assertEquals(activity.getString(R.string.gpu_system_runtime_title, "Mali"), row.name)
        assertEquals(DriverRow.LEGACY, row.tag)
        assertTrue(row.detail.contains(activity.getString(R.string.gpu_system_legacy_detail)))
        assertFalse(row.removable)
        assertEquals(row.name, state.runtime.name)
        assertEquals(listOf(TurnipDriver.AUTO), state.androidRows.map { it.id })
        assertEquals(activity.getString(R.string.gpu_android_system_display), state.display.name)
        assertEquals(activity.getString(R.string.gpu_provided_by_android), state.display.status)
    }

    @Test fun selectingAnInstalledSystemBundleKeepsMatchedPairMode() {
        val digest = "b".repeat(64)
        legacy(digest)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.chooseMode(GPU_MODE_PAIR)

        menus.selectDriver(SystemVulkanComponentStore.rowId(digest), linux = true)

        assertEquals(GPU_MODE_PAIR, menus.state().mode)
        assertEquals(SystemVulkanComponentStore.rowId(digest), menus.state().linuxSelected)
        val reopened = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        reopened.refreshDrivers()
        assertEquals(GPU_MODE_PAIR, reopened.state().mode)
    }

    @Test fun matchedSystemModeUsesAndroidDisplayAndPersistsAcrossRecreation() {
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.BUNDLED.first())
        SessionPrefs.setLinuxDriver(activity, "manual-runtime")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)

        menus.chooseMode(GPU_MODE_PAIR)
        val reopened = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        reopened.refreshDrivers()

        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
        assertEquals(GPU_MODE_PAIR, reopened.state().mode)
        assertEquals(TurnipDriver.AUTO, reopened.state().androidSelected)
    }

    @Test fun refreshingMaliMigratesBundledTurnipButRetainsManualImportedSelection() {
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, TurnipDriver.BUNDLED.first())
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.refreshDrivers()
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))

        SessionPrefs.setAndroidDriver(activity, "imported-driver")
        menus.refreshDrivers()
        assertEquals("imported-driver", SessionPrefs.androidDriver(activity))
    }

    @Test fun automaticSystemPolicyRefreshesReadyBuildsAndOnlyBootstrapsWhenMissing() {
        assertEquals(SystemAutoAction.REFRESH_AVAILABLE,
            systemAutoAction(true, true, true, false, true, false))
        assertEquals(SystemAutoAction.REFRESH_AVAILABLE,
            systemAutoAction(true, true, true, true, true, true))
        assertEquals(SystemAutoAction.USE_INSTALLED,
            systemAutoAction(true, true, false, false, true, false))
        assertEquals(SystemAutoAction.INSTALL_PINNED,
            systemAutoAction(true, false, true, false, true, false))
        assertEquals(SystemAutoAction.WAIT_FOR_NETWORK,
            systemAutoAction(true, false, true, true, true, false))
    }

    @Test fun anEmptySystemInstallIsNotPresentedAsAnInstalledBundle() {
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.refreshDrivers()

        assertTrue(menus.state().systemRows.isEmpty())
        assertEquals(activity.getString(R.string.gpu_system_missing), menus.state().runtime.status)
        assertTrue(menus.state().androidDownloads.isEmpty())
    }

    @Test fun refreshFailureSurvivesReloadingTheCachedRows() {
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        val catalog = DriverMenus::class.java.getDeclaredField("systemCatalog").apply { isAccessible = true }
        catalog.set(menus, SystemVulkanComponentStore.Catalog(emptyList(), listOf("droid-deck/droiddeck"), 0))

        menus.refreshReleaseRows()
        menus.refreshReleaseRows()

        assertEquals(activity.getString(R.string.upd_couldnt_check, "droid-deck/droiddeck"), menus.releaseStatus)
    }

    @Test fun componentLabelsDistinguishVersionsFromCommitBuilds() {
        assertEquals("Mali runtime component · Build 2eec15a5",
            systemComponentTitle(activity, "Mali", "2eec15a54924"))
        assertEquals("Mali runtime component · Version 2026.10.18",
            systemComponentTitle(activity, "Mali", "2026.10.18"))
        assertNull(systemComponentPublished(activity, "not-a-date"))
        assertTrue(systemComponentPublished(activity, "2026-10-18T09:48:00Z")!!.startsWith("Published "))
    }

    @Test fun systemRowsShowGithubChannelsAndKeepUnknownCachedBuildsUnlabelled() {
        val profile = com.droiddeck.launcher.gpu.SystemVulkanDriver.Profile.MALI
        val scope = SystemVulkanComponentStore.scope(profile)
        val defaults = SystemVulkanComponentStore.sourceBuiltIns()
        val initial = SystemVulkanComponentStore.initializeSources(activity, profile).single()
        val source = RepositorySources.bind(activity, scope, defaults, initial, 12345L,
            RepositorySources.generation(activity, scope, defaults))
        val candidates = JSONArray()
        listOf(true, false, null).forEachIndexed { index, channel ->
            val hash = (index + 1).toString().repeat(64)
            val tag = "system-vulkan-mali-v0.$index.0"
            candidates.put(JSONObject().put("key", "${source.id}:$hash").put("sourceId", source.id)
                .put("repository", source.repository).put("repositoryId", source.repositoryId)
                .put("releaseId", index + 1).put("tag", tag).put("publishedAt", "2026-10-18T00:00:00Z")
                .put("version", "v0.$index.0").put("variant", profile.id).put("minSdk", 26)
                .put("manifestSha256", "8".repeat(64)).put("assetId", index + 10)
                .put("assetName", "system-vulkan-mali.tar.zst")
                .put("assetUrl", "https://github.com/${source.repository}/releases/download/$tag/system-vulkan-mali.tar.zst")
                .put("size", 100).put("sha256", hash).put("treeSha256", "9".repeat(64))
                .put("producerCommit", "a".repeat(40))
                .put("buildUrl", "https://github.com/${source.repository}/actions/runs/1").put("prerelease", channel))
        }
        val cache = File(RepositorySources.cacheDirectory(activity, scope, source), "system-vulkan-candidates.json")
        cache.parentFile!!.mkdirs()
        val contents = JSONObject().put("schema", 1).put("sourceId", source.id)
            .put("repository", source.repository).put("repositoryId", source.repositoryId).put("candidates", candidates)
        cache.writeText(contents.toString())
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()), gpu)
        menus.refreshReleaseRows()

        val rows = menus.state().systemDownloads.associateBy { it.key }
        val preview = activity.getString(R.string.upd_preview)
        val stable = activity.getString(R.string.upd_stable)
        assertEquals(3, rows.size)
        assertTrue(rows.getValue("${source.id}:${"1".repeat(64)}").detail.contains(preview))
        assertTrue(rows.getValue("${source.id}:${"2".repeat(64)}").detail.contains(stable))
        val old = rows.getValue("${source.id}:${"3".repeat(64)}").detail
        assertFalse(old.contains(preview))
        assertFalse(old.contains(stable))

        val digest = "1".repeat(64)
        val directory = File(activity.filesDir, "components/system-vulkan/$digest").apply { mkdirs() }
        SystemVulkanManifest.required(profile.id).forEach { path ->
            File(directory, path).apply { parentFile!!.mkdirs(); writeText("payload") }
        }
        File(directory, "run-proton.py").setExecutable(true)
        File(directory, "icd.json").writeText("{}")
        File(directory, "READY").writeText(digest)
        File(directory, ".droiddeck-receipt.json").writeText(JSONObject()
            .put("schema", 1).put("component", "system-vulkan").put("variant", profile.id)
            .put("version", "v0.0.0").put("sha256", digest).put("repository", source.repository)
            .put("tag", candidates.getJSONObject(0).getString("tag")).put("prerelease", true).toString())
        menus.refreshDrivers()
        assertTrue(menus.state().systemRows.single().detail.contains(preview))

        candidates.getJSONObject(0).put("prerelease", false)
        cache.writeText(contents.toString())
        menus.refreshReleaseRows()
        menus.refreshDrivers()
        val promoted = menus.state().systemRows.single().detail
        assertTrue(promoted.contains(stable))
        assertFalse(promoted.contains(preview))
    }

    private fun legacy(digest: String) {
        val directory = File(activity.filesDir, "system_vulkan/$digest").apply { mkdirs() }
        File(directory, "icd.json").writeText("{}")
        SystemVulkanManifest.required("mali").forEach { path ->
            File(directory, path).apply { parentFile!!.mkdirs(); writeText("payload") }
        }
        File(directory, "run-proton.py").setExecutable(true)
    }
}
