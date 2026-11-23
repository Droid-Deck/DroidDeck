package com.droiddeck.launcher.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.AppImageManager
import com.droiddeck.launcher.runtime.CurseForgeApp
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.runtime.WowAddonCatalog
import com.droiddeck.launcher.runtime.WowAddons
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land", application = android.app.Application::class)
class CurseLinkTabTest {
    @get:Rule val compose = createRobolectricComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val original = CurseForgeApp.guest
    private val url = "ws://127.0.0.1:43117/0f8fad5b-d9cb-469f-a165-70867728950e"
    private val app = UserApps.App("appimage:c", UserApps.Kind.APPIMAGE, "CurseForge", null, null, AppImageManager.LAUNCHER, listOf("/root/apps/curseforge"), feed = CurseForgeApp.FEED)
    private val helper = Collections.synchronizedList(mutableListOf<String>())
    private val methods = Collections.synchronizedList(mutableListOf<String>())
    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun restore() {
        CurseForgeApp.guest = original
    }

    private fun text(id: Int) = context.getString(id)

    private fun wow(): WowAddons.Target {
        val games = temp.newFolder("Games")
        val addOns = File(games, "World of Warcraft/_retail_/Interface/AddOns").apply { mkdirs() }
        File(addOns, "Leatrix_Maps").mkdirs()
        SessionPrefs.setGameFolderBinds(context, listOf(games.absolutePath to "/root/Games"))
        CurseForgeApp.guest = { _, argv, cancelled, onLine ->
            if (argv[0] == CurseForgeApp.HELPER) {
                helper += argv[1]
                if (argv[1] == "start") {
                    onLine(url)
                    while (cancelled?.get() != true) Thread.sleep(10)
                }
                0
            } else {
                val method = Regex("s\\[\\\\\"(\\w+)\\\\\"\\]").find(argv[4])?.groupValues?.get(1)
                method?.let { methods += it }
                val answer: Any = when (method) {
                    "detectGameFromPath" -> JSONObject().put("gameInstanceGuid", "g")
                    "getGameInstances" -> JSONArray().put(JSONObject().put("guid", "g").put("installPath", "/root/Games/World of Warcraft/_retail_"))
                    "getProjects" -> JSONArray().put(JSONObject().put("id", "298842").put("installedModId", "m").put("status", 1).put("locked", false).put("folders", JSONArray().put("Leatrix_Maps")))
                    else -> true
                }
                onLine(JSONObject().put("ok", true).put("value", JSONObject().put("value", answer).toString()).toString())
                0
            }
        }
        return WowAddons.Target("_retail_", addOns)
    }

    @Test
    fun theTabAndItsDialogShareOneCurseForgeThatStopsWhenTheTabLeaves() {
        val target = wow()
        val shown = mutableStateOf(true)
        val seen = Collections.synchronizedList(mutableListOf<List<String>>())
        var opened: CurseLink? = null
        compose.setContent {
            if (shown.value) {
                val link = rememberCurseLink(app, sessionRunning = false)
                opened = link
                LaunchedEffect(link) { withContext(Dispatchers.IO) { link?.let { seen += it.installed(context, target, 0).map { p -> p.id } } } }
                LaunchedEffect(link) { withContext(Dispatchers.IO) { link?.let { seen += it.installed(context, target, 0).map { p -> p.id } } } }
            }
        }
        compose.waitUntil(20_000) { seen.size == 2 }
        assertEquals(listOf(listOf("298842"), listOf("298842")), seen.toList())
        assertEquals(listOf("start"), helper.toList())
        assertEquals(1, methods.count { it == "scanGameInstance" })
        assertTrue(CurseLink.live() != null)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.waitUntil(20_000) { "stop" in helper }
        assertNull(CurseLink.live())
        assertTrue("shutdown" in methods)
        assertEquals(1, helper.count { it == "start" })
        runBlocking { withTimeout(20_000) { opened!!.stopped.join() } }
        compose.waitUntil(5_000) { Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("curseforge") } }
        val calls = helper.size + methods.size
        Thread.sleep(1_500)
        assertEquals(calls, helper.size + methods.size)
    }

    @Test
    fun aNewGenerationRescansButReusesTheRunningCurseForge() {
        val target = wow()
        val link = CurseLink(app)
        try {
            link.installed(context, target, 0)
            link.installed(context, target, 0)
            link.refresh()
            link.installed(context, target, link.generation)
            assertEquals(2, methods.count { it == "scanGameInstance" })
            assertEquals(1, helper.count { it == "start" })
        } finally {
            runBlocking { link.close(context).join() }
        }
    }

    @Test
    fun anInstallHandsCurseForgesSettledListToTheNextReadWithoutARescan() {
        val target = wow()
        val link = CurseLink(app)
        try {
            link.installed(context, target, 0)
            assertTrue(link.change(context, target) { CurseForgeApp.install(context, it.inspector, it.guid, "298842") })
            assertEquals(listOf("298842"), link.installed(context, target, 1).map { it.id })
            assertEquals(1, methods.count { it == "scanGameInstance" })
            link.installed(context, target, 2)
            assertEquals(2, methods.count { it == "scanGameInstance" })
        } finally {
            runBlocking { link.close(context).join() }
        }
    }

    @Test
    fun aFailedInstallLeavesTheNextReadToRescan() {
        val target = wow()
        val link = CurseLink(app)
        try {
            link.installed(context, target, 0)
            assertFalse(link.change(context, target) { null })
            link.installed(context, target, 1)
            assertEquals(2, methods.count { it == "scanGameInstance" })
        } finally {
            runBlocking { link.close(context).join() }
        }
    }

    @Test
    fun aFailedScanKeepsTheLastReportInsteadOfClearingIt() {
        val target = wow()
        val link = CurseLink(app)
        try {
            val reported = link.marks(context, target, 0)
            assertTrue(reported.isNotEmpty())
            runBlocking { link.close(context).join() }
            assertEquals(reported.mapValues { null }, link.marks(context, target, 1))
        } finally {
            runBlocking { link.close(context).join() }
        }
    }

    private fun tocFolder(addOns: File, name: String, curseId: String) =
        File(addOns, name).apply { mkdirs(); File(this, "$name.toc").writeText("## X-Curse-Project-ID: $curseId\n") }

    @Test
    fun anAddonTheManagerDoesNotListIsFoundByItsTocProjectId() {
        val target = WowAddons.Target("_retail_", temp.newFolder("AddOns"))
        tocFolder(target.addOns, "Questie", "334372")
        tocFolder(target.addOns, "Other", "1")
        assertEquals(listOf("Questie"), curseFolders(target, "334372", emptyList()))
        assertEquals(listOf("Listed"), curseFolders(target, "334372", listOf("Listed")))
    }

    @Test
    fun uninstallingByTocProjectIdDeletesOnlyTheMatchedFolders() {
        val target = WowAddons.Target("_retail_", temp.newFolder("AddOns"))
        tocFolder(target.addOns, "Questie", "334372")
        tocFolder(target.addOns, "Other", "1")
        deleteAddons(context, target, curseFolders(target, "334372", emptyList()), null)
        assertEquals(listOf("Other"), WowAddons.installed(target))
    }

    @Test
    fun uninstallingALinkedAddonLeavesTheLinkTargetAlone() {
        val target = WowAddons.Target("_retail_", temp.newFolder("AddOns"))
        val outside = tocFolder(temp.newFolder("outside"), "Questie", "334372")
        File(outside, "keep.lua").writeText("keep")
        Files.createSymbolicLink(File(target.addOns, "Questie").toPath(), outside.toPath())
        deleteAddons(context, target, curseFolders(target, "334372", emptyList()), null)
        assertFalse(Files.exists(File(target.addOns, "Questie").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", File(outside, "keep.lua").readText())
        assertTrue(File(outside, "Questie.toc").isFile)
    }

    @Test
    fun uninstallingWithNothingToDeleteFails() {
        val target = WowAddons.Target("_retail_", temp.newFolder("AddOns"))
        tocFolder(target.addOns, "Other", "1")
        assertThrows(IOException::class.java) { deleteAddons(context, target, curseFolders(target, "334372", emptyList()), null) }
        assertEquals(listOf("Other"), WowAddons.installed(target))
    }

    @Test
    fun noCurseForgeStartsWhileASessionRuns() {
        wow()
        var link: CurseLink? = CurseLink(app)
        compose.setContent { link = rememberCurseLink(app, sessionRunning = true) }
        compose.waitForIdle()
        assertNull(link)
        assertTrue(helper.isEmpty())
    }

    @Test
    fun aStaleEntryWhoseFolderIsGoneIsNotManaged() {
        val target = wow()
        assertTrue(File(target.addOns, "Leatrix_Maps").deleteRecursively())
        val link = CurseLink(app)
        try {
            assertTrue(link.installed(context, target, 0).isEmpty())
        } finally {
            runBlocking { link.close(context).join() }
        }
    }

    private val actions = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
        onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
        onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> },
    )

    private fun row(update: Boolean?, wowi: Boolean) {
        val target = WowAddons.Target("_retail_", temp.newFolder("AddOns"))
        val item = WowAddons.Installed("Leatrix_Maps", "1.60.35", null)
        val newer = WowAddonCatalog.Addon("1", "Leatrix Maps", "Leatrix", "1.60.36", 0, 0, null).takeIf { wowi }
        val curse = update?.let { CurseForgeApp.Project("298842", "m", listOf("Leatrix_Maps"), it) to CurseLink(app) }
        compose.setContent { DroidDeckTheme { AddonRow(item, newer, target, actions, curse) } }
    }

    @Test
    fun aManagedRowWithAnUpdateOffersUpdateAndUninstall() {
        row(update = true, wowi = false)
        compose.onAllNodesWithContentDescription(text(R.string.common_update)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(text(R.string.common_uninstall)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(text(R.string.common_delete)).assertCountEquals(0)
    }

    @Test
    fun aCurrentManagedRowIgnoresOtherSourcesAndOffersOnlyUninstall() {
        row(update = false, wowi = true)
        compose.onAllNodesWithContentDescription(text(R.string.common_update)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.common_uninstall)).assertCountEquals(1)
    }

    @Test
    fun anUnmanagedRowKeepsTheLocalUpdateAndDelete() {
        row(update = null, wowi = true)
        compose.onAllNodesWithContentDescription(text(R.string.common_update)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(text(R.string.common_delete)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(text(R.string.common_uninstall)).assertCountEquals(0)
    }
}
