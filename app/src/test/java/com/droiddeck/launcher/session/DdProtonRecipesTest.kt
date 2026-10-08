package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
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
class DdProtonRecipesTest {
    private val dxvk = "dxvk-2.4-linux.wcp"
    private val vkd3d = "vkd3d-proton-2.14-linux.wcp"
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        File(context.filesDir, DdProtonRecipes.OVERRIDE).delete()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun recipesParseIntoPackagesEnvAndNote() {
        val recipes = DdProtonRecipes.parse(
            """{"version":1,"games":{"42":{"dxvk":{"file":"$dxvk","release":"Dxvk-Linux"},
               "env":{"DXVK_HUD":"version","WINEDLLOVERRIDES":"xinput1_3=n,b;libsentry=d"},"note":"test"},
               "4294967295":{"fex":{"file":"fex-2607-linux.wcp","release":"FexCore-Linux"}}}}""",
        )
        assertEquals(listOf("42", "4294967295"), recipes.map { it.id })
        assertEquals(listOf(DdProtonRecipes.PackageRef("dxvk", dxvk, "Dxvk-Linux")), recipes[0].packages)
        assertEquals(mapOf("DXVK_HUD" to "version", "WINEDLLOVERRIDES" to "xinput1_3=n,b;libsentry=d"), recipes[0].env)
        assertEquals("test", recipes[0].note)
        assertEquals("fex", recipes[1].packages.single().comp)
    }

    @Test fun anythingOutsideTheFormatIsRefused() {
        val bad = listOf(
            """{"version":2,"games":{}}""",
            """{"version":1,"games":{"0":{}}}""",
            """{"version":1,"games":{"steam":{}}}""",
            """{"version":1,"games":{"42":{"dxvk":{"file":"../x.wcp","release":"Dxvk-Linux"}}}}""",
            """{"version":1,"games":{"42":{"dxvk":{"file":"dxvk.tar","release":"Dxvk-Linux"}}}}""",
            """{"version":1,"games":{"42":{"dxvk":{"file":"$dxvk","release":""}}}}""",
            """{"version":1,"games":{"42":{"env":{"LD_PRELOAD":"/x.so"}}}}""",
            """{"version":1,"games":{"42":{"env":{"DXVK_HUD":1}}}}""",
        )
        for (text in bad) {
            assertThrows(text, Exception::class.java) { DdProtonRecipes.parse(text) }
        }
    }

    @Test fun onlyTuningVariablesAreAllowed() {
        for (name in listOf("DXVK_HUD", "VKD3D_CONFIG", "FEX_TSOENABLED", "MESA_SHADER_CACHE_MAX_SIZE", "PROTON_USE_WINED3D", "mesa_glthread")) {
            assertTrue(name, DdProtonRecipes.envAllowed(name, "1"))
        }
        for (name in listOf("LD_PRELOAD", "LD_LIBRARY_PATH", "WINEDLLPATH", "PATH", "HOME", "PROTON_LOG_DIR", "DXVK_CONFIG_FILE",
                "VKD3D_SHADER_CACHE_PATH", "MESA_SHADER_CACHE_DIR", "FEX_ROOTFS", "FEX_THUNKHOSTLIBS", "CUSTOM", "BAD-NAME")) {
            assertFalse(name, DdProtonRecipes.envAllowed(name, "1"))
        }
        assertTrue(DdProtonRecipes.envAllowed("WINEDLLOVERRIDES", "a=n;b=b,n;c=d;d="))
        for (value in listOf("../evil.dll=n", "a=native", "a", "a=n;b=x")) {
            assertFalse(value, DdProtonRecipes.envAllowed("WINEDLLOVERRIDES", value))
        }
        assertFalse(DdProtonRecipes.envAllowed("DXVK_HUD", "a\u0000b"))
    }

    @Test fun thePublishedFileNamesStorePathsAndLeavesOutWhatIsMissing() {
        val recipes = DdProtonRecipes.parse(
            """{"version":1,"games":{"42":{"dxvk":{"file":"$dxvk","release":"Dxvk-Linux"},
               "vkd3d":{"file":"$vkd3d","release":"Vkd3d-proton-Linux"},"env":{"DXVK_HUD":"version"},"note":"n"}}}""",
        )
        val json = DdProtonRecipes.publishJson(recipes, mapOf(dxvk to "/root/.local/share/droiddeck-recipes/store/dxvk-2.4-linux"))
        assertEquals(1, json.getInt("version"))
        val game = json.getJSONObject("games").getJSONObject("42")
        assertEquals("/root/.local/share/droiddeck-recipes/store/dxvk-2.4-linux", game.getString("dxvk"))
        assertFalse(game.has("vkd3d"))
        assertEquals("version", game.getJSONObject("env").getString("DXVK_HUD"))
        assertEquals("n", game.getString("note"))
    }

    @Test fun ensureFetchesUnpacksAndPublishes() {
        File(context.filesDir, DdProtonRecipes.OVERRIDE).writeText(
            """{"version":1,"games":{"42":{"dxvk":{"file":"$dxvk","release":"Dxvk-Linux"},
               "vkd3d":{"file":"$vkd3d","release":"Vkd3d-proton-Linux"}}}}""",
        )
        val fetched = mutableListOf<String>()
        val line = DdProtonRecipes.ensure(
            context,
            { file, release -> if (file == dxvk) item(file, release) else null },
            { fetched += it.file },
            kind = { file -> if (file == dxvk) "dxvk" else null },
            unpack = ::fakeUnpack,
        )
        assertEquals(listOf(dxvk), fetched)
        assertTrue(line, line.startsWith("DdProtonRecipes: 1 recipe(s), 1 package(s) ready; $vkd3d is not in the Nightlies listing"))
        val root = LinuxRuntime.rootDir(context)
        val store = File(root, "root/.local/share/droiddeck-recipes/store/dxvk-2.4-linux")
        assertTrue(File(store, ".complete").isFile)
        assertEquals("d3d11", File(store, "files/lib/wine/dxvk/aarch64-windows/d3d11.dll").readText())
        val published = JSONObject(File(root, "root/.config/droiddeck/recipes.json").readText())
        val game = published.getJSONObject("games").getJSONObject("42")
        assertEquals("/root/.local/share/droiddeck-recipes/store/dxvk-2.4-linux", game.getString("dxvk"))
        assertFalse(game.has("vkd3d"))

        // A recipe that stops naming a package drops its unpacked copy; a package of the wrong kind is refused.
        File(context.filesDir, DdProtonRecipes.OVERRIDE).writeText(
            """{"version":1,"games":{"42":{"vkd3d":{"file":"$dxvk","release":"Dxvk-Linux"}}}}""",
        )
        val second = DdProtonRecipes.ensure(context, { f, r -> item(f, r) }, {}, kind = { "dxvk" }, unpack = ::fakeUnpack)
        assertTrue(second, second.endsWith("$dxvk is not a vkd3d package"))
        assertFalse(store.exists())
        assertFalse(JSONObject(File(root, "root/.config/droiddeck/recipes.json").readText())
            .getJSONObject("games").getJSONObject("42").has("vkd3d"))
    }

    @Test fun noRecipesPublishesAnEmptyFile() {
        val line = DdProtonRecipes.ensure(context, { _, _ -> null }, {}, kind = { null }, unpack = ::fakeUnpack)
        assertEquals("DdProtonRecipes: 0 recipe(s), 0 package(s) ready", line)
        val published = JSONObject(File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/recipes.json").readText())
        assertEquals(0, published.getJSONObject("games").length())
    }

    private fun item(file: String, release: String) =
        ComponentsManager.CatalogItem(file, "dxvk", release, "https://example.invalid/$file", 1, "sha256:00")

    /** Stands in for ComponentsManager.unpack: zstd-jni has no host library for JVM tests. */
    private fun fakeUnpack(file: String, dir: File) {
        File(dir, "files/lib/wine/dxvk/aarch64-windows").mkdirs()
        File(dir, "files/lib/wine/dxvk/aarch64-windows/d3d11.dll").writeText("d3d11")
        File(dir, ".complete").writeText("1\n")
    }
}
