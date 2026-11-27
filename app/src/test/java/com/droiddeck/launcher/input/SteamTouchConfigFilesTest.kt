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

    private val template get() = File(steam, "controller_base/templates/controller_mobile_touch_gamepad_joystick.vdf")

    private fun login() = File(steam, "config/loginusers.vdf").apply { parentFile!!.mkdirs() }
        .writeText("\"users\"\n{\n\t\"76561197960265851\"\n\t{\n\t\t\"MostRecent\"\t\t\"1\"\n\t}\n}\n")

    private fun saveDefault(app: Int): File? {
        val elements = listOf(SteamTouchConfig.Element(SteamTouchConfig.A, true, 0.5f, 0.6f))
        return SteamTouchConfig.saveLayouts(context, app, SteamTouchConfig.load(context, app), mapOf(1 to elements))
    }

    private fun assertSavedA(app: Int, saved: File?) {
        val loaded = SteamTouchConfig.load(context, app)
        assertEquals(saved, loaded.file)
        val a = SteamTouchConfig.elementsFor(loaded, 1, emptyList()).first { it.type == SteamTouchConfig.A }
        assertEquals(0.5f, a.x, 0.002f)
        assertEquals(0.6f, a.y, 0.002f)
    }

    @Test fun savesWhenSteamHasNotCreatedTheAccountsControllerFolder() {
        File(steam, "steamapps/common/Steam Controller Configs").deleteRecursively()
        login()
        val saved = saveDefault(413150)
        assertEquals(File(dir, "413150/controller_mobile_touch.vdf"), saved)
        assertSavedA(413150, saved)
    }

    @Test fun savesIntoTheSignedInAccountNotAnotherAccountsFolder() {
        dir.parentFile!!.deleteRecursively()
        val other = File(steam, "steamapps/common/Steam Controller Configs/456/config").apply { mkdirs() }
        login()
        val saved = saveDefault(413150)
        assertEquals(File(dir, "413150/controller_mobile_touch.vdf"), saved)
        assertFalse(File(other, "413150").exists())
        assertSavedA(413150, saved)
    }

    @Test fun withoutAnAccountOrControllerFolderNothingIsWritten() {
        File(steam, "steamapps/common/Steam Controller Configs").deleteRecursively()
        assertEquals(null, saveDefault(413150))
        assertFalse(File(steam, "steamapps/common/Steam Controller Configs").exists())
    }

    @Test fun anEmptyConfigsetIsRebuiltInsteadOfFailingTheSave() {
        File(dir, "configset_controller_mobile_touch.vdf").writeText("")
        val saved = saveDefault(413150)
        assertEquals(File(dir, "413150/controller_mobile_touch.vdf"), saved)
        assertTrue(File(dir, "configset_controller_mobile_touch.vdf").readText().contains("\"413150\""))
        assertSavedA(413150, saved)
    }

    @Test fun aConfigsetWithOnlyAQuotedBraceIsRebuilt() {
        File(dir, "configset_controller_mobile_touch.vdf").writeText("\"controller_config\" \"{\"\n")
        val saved = saveDefault(413150)
        val root = KeyValues.parse(File(dir, "configset_controller_mobile_touch.vdf").readText()).child("controller_config")
        assertEquals("1", root?.child("413150")?.string("autosave"))
        assertSavedA(413150, saved)
    }

    @Test fun anExistingConfigsetKeepsItsOtherEntries() {
        configset("configset_controller_mobile_touch.vdf", "8400" to "\"workshop\"\t\t\"7\"")
        saveDefault(413150)
        val text = File(dir, "configset_controller_mobile_touch.vdf").readText()
        assertTrue(text.contains("\"8400\""))
        assertTrue(text.contains("\"413150\""))
    }

    private fun withBom(name: String) = File(dir, name).apply { writeText("\uFEFF" + readText()) }

    @Test fun aConfigsetWithAByteOrderMarkKeepsItsOtherEntries() {
        configset("configset_controller_mobile_touch.vdf", "8400" to "\"workshop\"\t\t\"7\"")
        withBom("configset_controller_mobile_touch.vdf")
        val saved = saveDefault(413150)
        val root = KeyValues.parse(File(dir, "configset_controller_mobile_touch.vdf").readText()).child("controller_config")
        assertEquals("1", root?.child("413150")?.string("autosave"))
        assertEquals("7", root?.child("8400")?.string("workshop"))
        assertSavedA(413150, saved)
    }

    @Test fun aWorkshopPickInAConfigsetWithAByteOrderMarkIsRead() {
        val picked = workshop("42", "community")
        configset("configset_controller_mobile_touch.vdf", "413150" to "\"workshop\"\t\t\"42\"")
        withBom("configset_controller_mobile_touch.vdf")
        assertEquals(picked, SteamTouchConfig.load(context, 413150).file)
    }

    @Test fun aConfigsetMissingItsRootBraceGetsTheEntryAtTheRoot() {
        File(dir, "configset_controller_mobile_touch.vdf")
            .writeText("\"controller_config\"\n{\n\t\"8400\"\n\t{\n\t\t\"workshop\"\t\t\"7\"\n\t}\n")
        val saved = saveDefault(413150)
        val root = KeyValues.parse(File(dir, "configset_controller_mobile_touch.vdf").readText()).child("controller_config")
        assertEquals("1", root?.child("413150")?.string("autosave"))
        assertEquals("7", root?.child("8400")?.string("workshop"))
        assertEquals(null, root?.child("8400")?.child("413150"))
        assertSavedA(413150, saved)
    }

    @Test fun aConfigsetCutOffAfterAKeyGetsTheEntryAtTheRoot() {
        File(dir, "configset_controller_mobile_touch.vdf").writeText("\"controller_config\"\n{\n\t\"8400\"\n\t{\n\t\t\"workshop\"")
        val saved = saveDefault(413150)
        val root = KeyValues.parse(File(dir, "configset_controller_mobile_touch.vdf").readText()).child("controller_config")
        assertEquals("1", root?.child("413150")?.string("autosave"))
        assertEquals(null, root?.child("8400")?.child("413150"))
        assertSavedA(413150, saved)
    }

    @Test fun aConfigsetWhoseRootIsAQuotedValueIsRebuilt() {
        File(dir, "configset_controller_mobile_touch.vdf").writeText("\"controller_config\" \"{}\"\n")
        val saved = saveDefault(413150)
        val root = KeyValues.parse(File(dir, "configset_controller_mobile_touch.vdf").readText()).child("controller_config")
        assertEquals("1", root?.child("413150")?.string("autosave"))
        assertSavedA(413150, saved)
    }

    private fun shortcut(name: String): Int {
        val shortcut = 2702387189L.toInt()
        template.apply { parentFile!!.mkdirs(); writeText(touch("Gamepad with Joystick")) }
        File(steam, "logs/console_log.txt").apply { parentFile!!.mkdirs() }.writeText(
            "!! Controller 0 attributes:\n  Type: 43\n" +
                "Loaded Config for Last Resort Path for App ID 2702387189, Controller 0: /root/.local/share/Steam//controller_base/templates/controller_mobile_touch_gamepad_joystick.vdf\n",
        )
        login()
        val vdf = java.io.ByteArrayOutputStream().apply {
            write("\u0000shortcuts\u0000\u00000\u0000\u0002appid\u0000".toByteArray())
            write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(shortcut).array())
            write("\u0001AppName\u0000$name\u0000\u0008\u0008\u0008".toByteArray())
        }
        File(steam, "userdata/123/config/shortcuts.vdf").apply { parentFile!!.mkdirs() }.writeBytes(vdf.toByteArray())
        return shortcut
    }

    @Test fun aShortcutFindsSteamsConfigAndSavesUnderItsName() {
        val shortcut = shortcut("Epic Games Launcher")
        val config = SteamTouchConfig.load(context, shortcut)
        assertEquals(template, config.file)
        assertEquals("Epic Games Launcher", SteamTouchConfig.gameName(context, shortcut))
        val saved = SteamTouchConfig.saveLayouts(context, shortcut, config, mapOf(1 to emptyList()))
        assertEquals(File(dir, "epic games launcher/controller_mobile_touch.vdf"), saved)
        assertTrue(File(dir, "configset_controller_mobile_touch.vdf").readText().contains("\"epic games launcher\""))
        assertEquals(saved, SteamTouchConfig.load(context, shortcut).file)
    }

    @Test fun aSavedShortcutNameWithBracesDoesNotHideTheNextAppsEntry() {
        val shortcut = shortcut("Game {")
        SteamTouchConfig.saveLayouts(context, shortcut, SteamTouchConfig.load(context, shortcut), mapOf(1 to emptyList()))
        val saved = saveDefault(413150)
        val root = KeyValues.parse(File(dir, "configset_controller_mobile_touch.vdf").readText()).child("controller_config")
        assertEquals("1", root?.child("game {")?.string("autosave"))
        assertEquals("1", root?.child("413150")?.string("autosave"))
        assertSavedA(413150, saved)
    }

    @Test fun aShortcutNameThatIsNoPlainFolderNameUsesTheAppId() {
        for (name in listOf("../escape", "a/b", "..", "Tab\tName")) {
            assertEquals(name, "2702387189", SteamTouchConfig.steamKey(context, shortcut(name)))
        }
    }

    @Test fun aShortcutNameWithADollarSavesAgain() {
        val shortcut = shortcut("Game $1")
        val config = SteamTouchConfig.load(context, shortcut)
        SteamTouchConfig.saveLayouts(context, shortcut, config, mapOf(1 to emptyList()))
        val saved = SteamTouchConfig.saveLayouts(context, shortcut, SteamTouchConfig.load(context, shortcut), mapOf(1 to emptyList()))
        assertEquals(File(dir, "game $1/controller_mobile_touch.vdf"), saved)
        assertEquals(1, Regex("\"game \\$1\"").findAll(File(dir, "configset_controller_mobile_touch.vdf").readText()).count())
    }

    @Test fun aMixedCaseEntryOfTheShortcutIsReplacedNotDuplicated() {
        val shortcut = shortcut("Epic Games Launcher")
        configset("configset_controller_mobile_touch.vdf", "Epic Games Launcher" to "\"template\"\t\t\"controller_mobile_touch_gamepad_joystick.vdf\"")
        configset("configset_MT-DROIDDECK0001.vdf", "Epic Games Launcher" to "\"workshop\"\t\t\"1\"")
        SteamTouchConfig.saveLayouts(context, shortcut, SteamTouchConfig.load(context, shortcut), mapOf(1 to emptyList()))
        val entries = Regex("\"epic games launcher\"", RegexOption.IGNORE_CASE)
        assertEquals(1, entries.findAll(File(dir, "configset_controller_mobile_touch.vdf").readText()).count())
        assertEquals(0, entries.findAll(File(dir, "configset_MT-DROIDDECK0001.vdf").readText()).count())
    }

    @Test fun savesLayoutWhenNoTouchConfigExistsOnDisk() {
        val config = SteamTouchConfig.load(context, 413150)
        assertEquals(null, config.file)
        assertEquals(null, config.text)
        val elements = listOf(SteamTouchConfig.Element(SteamTouchConfig.A, true, 0.5f, 0.6f))
        val saved = SteamTouchConfig.saveLayouts(context, 413150, config, mapOf(1 to elements))
        assertEquals(File(dir, "413150/controller_mobile_touch.vdf"), saved)
        assertTrue(saved!!.readText().contains("\"touch_layout\""))
        val loaded = SteamTouchConfig.load(context, 413150)
        assertEquals(saved, loaded.file)
        val a = SteamTouchConfig.elementsFor(loaded, 1, emptyList()).first { it.type == SteamTouchConfig.A }
        assertEquals(0.5f, a.x, 0.002f)
        assertEquals(0.6f, a.y, 0.002f)
    }

    @Test fun savingDoesNotOverwriteUnreadableExistingTouchConfig() {
        configset("configset_controller_mobile_touch.vdf", "413150" to "\"autosave\"\t\t\"1\"")
        val file = File(dir, "413150/controller_mobile_touch.vdf").apply {
            parentFile!!.mkdirs()
            writeText("\"controller_mappings\"\n{\n\t\"controller_type\"\t\t\"controller_mobile_touch\"\n\t\"touch_layout\"\t\t\"zz\"\n}\n")
        }
        val original = file.readBytes()
        val config = SteamTouchConfig.load(context, 413150)
        assertEquals(file, config.file)
        assertEquals(null, config.text)
        val saved = SteamTouchConfig.saveLayouts(context, 413150, config, emptyMap())
        assertEquals(null, saved)
        assertTrue(original.contentEquals(file.readBytes()))
    }
}
