package com.droiddeck.launcher.runtime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeckyPluginInstallTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plugin(parent: File, folder: String, name: String): File = File(parent, folder).apply {
        mkdirs(); File(this, "plugin.json").writeText(JSONObject().put("name", name).toString())
    }

    @Test fun renamedFolderReplacesByIdentityAndPreservesDataAndOrder() {
        val home = temporary.newFolder()
        val plugins = File(home, "plugins").apply { mkdirs() }
        val old = plugin(plugins, "old-folder", "Unifideck")
        val data = File(home, "data/old-folder/save").apply { parentFile.mkdirs(); writeText("keep") }
        val settings = File(home, "settings/loader.json").apply {
            parentFile.mkdirs()
            writeText("""{"pluginOrder":["Other","Unifideck"],"hiddenPlugins":["Unifideck","Other"],"disabled_plugins":["Unifideck"],"store":2}""")
        }
        val staged = plugin(temporary.newFolder(), "Unifideck", "Unifideck")
        DeckyPluginInstall.activate(staged, plugins, "Unifideck", settings, ".test")
        assertFalse(old.exists())
        assertTrue(File(plugins, "Unifideck/plugin.json").exists())
        assertEquals("keep", data.readText())
        val state = JSONObject(settings.readText())
        assertEquals("[\"Other\",\"Unifideck\"]", state.getJSONArray("pluginOrder").toString())
        assertEquals("[\"Other\"]", state.getJSONArray("hiddenPlugins").toString())
        assertEquals(0, state.getJSONArray("disabled_plugins").length())
        assertEquals(2, state.getInt("store"))
    }

    @Test fun newImportAppendsOnceAndReimportKeepsItsPosition() {
        val plugins = temporary.newFolder()
        val settings = File(temporary.newFolder(), "loader.json").apply { writeText("""{"pluginOrder":["Other"]}""") }
        repeat(2) {
            DeckyPluginInstall.activate(plugin(temporary.newFolder(), "Unifideck", "Unifideck"), plugins, "Unifideck", settings, ".test")
        }
        assertEquals("[\"Other\",\"Unifideck\"]", JSONObject(settings.readText()).getJSONArray("pluginOrder").toString())
    }

    @Test fun conflictingFolderLeavesInstalledPluginAndSettingsUntouched() {
        val plugins = temporary.newFolder()
        plugin(plugins, "Unifideck", "Other")
        val settings = File(temporary.newFolder(), "loader.json").apply { writeText("{}") }
        try {
            DeckyPluginInstall.activate(plugin(temporary.newFolder(), "Unifideck", "Unifideck"), plugins, "Unifideck", settings, ".test")
            fail("Expected conflicting identity to fail")
        } catch (_: DeckyPluginInstall.Failure) {}
        assertEquals("{}", settings.readText())
        assertEquals("Other", JSONObject(File(plugins, "Unifideck/plugin.json").readText()).getString("name"))
    }
    @Test fun firstLoaderSettingsIncludeExistingPlugins() {
        val plugins = temporary.newFolder()
        plugin(plugins, "Other", "Other")
        val settings = File(temporary.newFolder(), "loader.json")
        DeckyPluginInstall.activate(plugin(temporary.newFolder(), "Unifideck", "Unifideck"), plugins, "Unifideck", settings, ".test")
        assertEquals("[\"Other\",\"Unifideck\"]", JSONObject(settings.readText()).getJSONArray("pluginOrder").toString())
    }

    @Test fun activationFailureRestoresPreviousPlugin() {
        val plugins = temporary.newFolder()
        val old = plugin(plugins, "Unifideck", "Unifideck")
        File(old, "old-version").writeText("keep")
        // An empty staged directory makes the plugin activation fail after backing up the old one.
        val staged = plugin(temporary.newFolder(), "Unifideck", "Unifideck")
        staged.deleteRecursively()
        val settings = File(temporary.newFolder(), "loader.json").apply { writeText("{}") }
        try {
            DeckyPluginInstall.activate(staged, plugins, "Unifideck", settings, ".test")
            fail("Expected activation to fail")
        } catch (_: IllegalStateException) {}
        assertEquals("keep", File(old, "old-version").readText())
        assertEquals("{}", settings.readText())
    }

}
