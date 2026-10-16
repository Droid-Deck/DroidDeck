package com.droiddeck.launcher.input

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamTouchConfigFilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val steam = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")
    private val dir = File(steam, "steamapps/common/Steam Controller Configs/123/config").apply { mkdirs() }

    @After fun tearDown() { steam.deleteRecursively() }

    private fun touch(title: String) = "\"controller_mappings\"\n{\n\t\"title\"\t\t\"$title\"\n\t\"controller_type\"\t\t\"controller_mobile_touch\"\n}\n"

    private fun configset(name: String, vararg entries: Pair<String, String>) = File(dir, name).writeText(
        "\"controller_config\"\n{\n" + entries.joinToString("") { (app, line) -> "\t\"$app\"\n\t{\n\t\t$line\n\t}\n" } + "}\n",
    )

    private fun autosave(app: Int, title: String) = File(dir, "$app/controller_mobile_touch.vdf").apply { parentFile!!.mkdirs(); writeText(touch(title)) }

    private fun workshop(id: String, title: String) =
        File(steam, "steamapps/workshop/content/241100/$id/123_legacy.bin").apply { parentFile!!.mkdirs(); writeText(touch(title)) }

    @Test fun theDevicesWorkshopPickWinsOverAnOlderAutosave() {
        autosave(204360, "old autosave")
        val picked = workshop("1764689049", "official")
        configset("configset_controller_mobile_touch.vdf", "204360" to "\"autosave\"\t\t\"1\"")
        configset("configset_MT-DROIDDECK0001.vdf", "204360" to "\"workshop\"\t\t\"1764689049\"")
        assertEquals(picked, SteamTouchConfig.load(context, 204360).file)
    }

    @Test fun aWorkshopPickInTheTypeConfigsetIsRead() {
        val picked = workshop("42", "community")
        configset("configset_controller_mobile_touch.vdf", "413150" to "\"workshop\"\t\t\"42\"")
        assertEquals(picked, SteamTouchConfig.load(context, 413150).file)
    }

    @Test fun savingAnEditDropsTheDevicePickSoTheEditLoads() {
        workshop("1764689049", "official")
        configset("configset_MT-DROIDDECK0001.vdf", "204360" to "\"workshop\"\t\t\"1764689049\"", "8400" to "\"workshop\"\t\t\"7\"")
        val config = SteamTouchConfig.load(context, 204360)
        val saved = SteamTouchConfig.saveLayouts(context, 204360, config, mapOf(1 to emptyList()))
        assertEquals(File(dir, "204360/controller_mobile_touch.vdf"), saved)
        val device = File(dir, "configset_MT-DROIDDECK0001.vdf").readText()
        assertFalse(device.contains("\"204360\""))
        assertTrue(device.contains("\"8400\""))
        assertEquals(saved, SteamTouchConfig.load(context, 204360).file)
    }
}
