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

    @Test fun version2PicksTheVariantsForThisGpu() {
        val text = """{"version":2,"games":{
            "42":{"name":"Game","variants":[
              {"env":{"FEX_X87REDUCEDPRECISION":"1"},"note":"community"},
              {"gpu":["A6XX"],"dxvk":{"file":"dxvk-gplasync-2.7.1-1-linux.wcp","release":"Dxvk-gplasync-Linux"},"env":{"DXVK_ASYNC":"1"},"note":"community"},
              {"gpu":["A7XX","A8XX"],"vkd3d":{"file":"$vkd3d","release":"Vkd3d-proton-Linux"},"env":{"FEX_X87REDUCEDPRECISION":"0"}}]},
            "43":{"name":"Only 6xx","variants":[{"gpu":["A6XX"],"env":{"DXVK_ASYNC":"1"}}]}}}"""
        val a7 = DdProtonRecipes.parse(text, "A7XX")
        assertEquals(listOf("42"), a7.map { it.id })
        assertEquals(listOf("vkd3d"), a7[0].packages.map { it.comp })
        assertEquals(mapOf("FEX_X87REDUCEDPRECISION" to "0"), a7[0].env)
        assertEquals("community", a7[0].note)
        val a6 = DdProtonRecipes.parse(text, "A6XX")
        assertEquals(listOf("42", "43"), a6.map { it.id })
        assertEquals(listOf("dxvk"), a6[0].packages.map { it.comp })
        assertEquals(mapOf("FEX_X87REDUCEDPRECISION" to "1", "DXVK_ASYNC" to "1"), a6[0].env)
        val none = DdProtonRecipes.parse(text, null)
        assertEquals(listOf("42"), none.map { it.id })
        assertTrue(none[0].packages.isEmpty())
    }

    @Test fun theBundledLibraryParsesForEveryFamily() {
        val text = File("src/main/assets/${DdProtonRecipes.ASSET}").readText()
        for (family in listOf("A6XX", "A7XX_LOW", "A7XX", "A8XX", "ADRENO_UNKNOWN", "NOT_ADRENO")) {
            DdProtonRecipes.parse(text, family)
        }
    }

    @Test fun anythingOutsideTheFormatIsRefused() {
        val bad = listOf(
            """{"version":3,"games":{}}""",
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
        assertTrue(DdProtonRecipes.envAllowed("OPENSSL_ia32cap", "~0x20000000"))
        assertFalse(DdProtonRecipes.envAllowed("OPENSSL_ia32cap", "/tmp/x"))
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
            "A7XX",
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
        val second = DdProtonRecipes.ensure(context, "A7XX", { f, r -> item(f, r) }, {}, kind = { "dxvk" }, unpack = ::fakeUnpack)
        assertTrue(second, second.endsWith("$dxvk is not a vkd3d package"))
        assertFalse(store.exists())
        assertFalse(JSONObject(File(root, "root/.config/droiddeck/recipes.json").readText())
            .getJSONObject("games").getJSONObject("42").has("vkd3d"))
    }

    @Test fun anEmptyOverridePublishesAnEmptyFile() {
        File(context.filesDir, DdProtonRecipes.OVERRIDE).writeText("""{"version":1,"games":{}}""")
        val line = DdProtonRecipes.ensure(context, "A7XX", { _, _ -> null }, {}, kind = { null }, unpack = ::fakeUnpack)
        assertEquals("DdProtonRecipes: 0 recipe(s), 0 package(s) ready", line)
        val published = JSONObject(File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/recipes.json").readText())
        assertEquals(0, published.getJSONObject("games").length())
    }

    @Test fun withoutAnOverrideTheBundledLibraryIsPublished() {
        val line = DdProtonRecipes.ensure(context, "A7XX", { _, _ -> null }, {}, kind = { null }, unpack = ::fakeUnpack)
        val count = Regex("(\\d+) recipe").find(line)!!.groupValues[1].toInt()
        assertTrue(line, count > 100)
        val games = JSONObject(File(LinuxRuntime.rootDir(context), "root/.config/droiddeck/recipes.json").readText()).getJSONObject("games")
        assertEquals(count, games.length())
        // Packages could not be fetched here, so every published recipe carries only its variables.
        games.keys().forEach { id -> assertFalse(id, games.getJSONObject(id).has("dxvk")) }
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
