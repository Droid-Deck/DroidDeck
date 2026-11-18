package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentsRepublishTest {
    private val fex = "FEXCore-2610-d2rfix28-linux.wcp"
    private val scope = "2596113170"
    private val dll = "files/lib/wine/aarch64-windows/libwow64fex.dll"
    private val so = "files/lib/wine/aarch64-unix/libwow64fex.so"
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test fun aGameFexChoiceReachesAProtonInstalledAfterIt() {
        proton("DroidDeck-Proton 11-11.7", "DroidDeck-Proton-11-11.7")
        packageFile()
        ComponentsManager.setGameComponent(context, scope, "fex", fex)
        assertEquals(setOf("DroidDeck-Proton 11-11.7"), scopedTools())

        proton("DroidDeck-Proton 11-11.8", "DroidDeck-Proton-11-11.8")
        assertEquals(setOf("DroidDeck-Proton 11-11.7"), scopedTools())

        assertTrue(ComponentsManager.republish(context))
        assertEquals(setOf("DroidDeck-Proton 11-11.7", "DroidDeck-Proton 11-11.8"), scopedTools())
        val row = rows().single { it[0] == scope && it[1] == "DroidDeck-Proton 11-11.8" && it[4] == dll }
        assertEquals("DroidDeck-Proton-11-11.8", row[2])
        assertEquals("d2rfix28 wow64", File(LinuxRuntime.rootDir(context), row[6].removePrefix("/")).readText())
        assertEquals(fex, ComponentsManager.gameComponents(context, scope)["fex"])
    }

    @Test fun aGlibcGameFexChoiceWithoutItsTwinLeavesDroidDeckBionicOnItsOwnFex() {
        proton("DroidDeck-Proton 11-12.8", "DroidDeck-Proton-11-12.8")
        proton("DroidDeck-Bionic-12.8-r6", "DroidDeck-Bionic-12.8-r6")
        packageFile()
        ComponentsManager.setGameComponent(context, scope, "fex", fex) { false }
        assertEquals(setOf("DroidDeck-Proton 11-12.8"), scopedTools())
        val bionic = rows().filter { it[1] == "DroidDeck-Bionic-12.8-r6" && it[3] == "fex" }
        assertTrue(bionic.isNotEmpty())
        assertTrue(bionic.all { it[0] == "*" && "/store/original-DroidDeck-Bionic-12.8-r6-" in it[6] })
    }

    @Test fun aGameFexChoiceReachesEachProtonAsTheBuildOfItsFlavor() {
        proton("DroidDeck-Proton-11-12.8", "DroidDeck-Proton-11-12.8")
        proton("DroidDeck-Bionic-12.8-r24", "DroidDeck-Bionic-12.8-r24")
        val linux = "FEXCore-2611.1-linux.wcp"
        val bionic = "FEXCore-2611.1-bionic.wcp"
        packageFile(linux, "FEXCore-Linux", "glibc wow64")
        packageFile(bionic, "FEXCore-Bionic", "android wow64")

        for (choice in listOf(linux, bionic)) {
            ComponentsManager.setGameComponent(context, scope, "fex", choice) { false }
            val scoped = rows().filter { it[0] == scope && it[3] == "fex" }
            val own = scoped.single { it[1] == "DroidDeck-Proton-11-12.8" && it[4] == dll }
            val android = scoped.single { it[1] == "DroidDeck-Bionic-12.8-r24" && it[4] == dll }
            assertEquals("glibc wow64", File(LinuxRuntime.rootDir(context), own[6].removePrefix("/")).readText())
            assertEquals("android wow64", File(LinuxRuntime.rootDir(context), android[6].removePrefix("/")).readText())
            assertTrue(scoped.any { it[1] == "DroidDeck-Proton-11-12.8" && it[6] == "-" })
            assertTrue(scoped.none { it[1] == "DroidDeck-Bionic-12.8-r24" && it[6] == "-" })
            assertEquals(choice, ComponentsManager.gameComponents(context, scope)["fex"])
        }
    }

    @Test fun aProtonsFexIsOnlyEverSwappedToABuildOfItsFlavor() {
        proton("DroidDeck-Proton-11-12.8", "DroidDeck-Proton-11-12.8")
        proton("DroidDeck-Bionic-12.8-r24", "DroidDeck-Bionic-12.8-r24")
        val linux = "FEXCore-2611.1-linux.wcp"
        val bionic = "FEXCore-2611.1-bionic.wcp"
        packageFile(linux, "FEXCore-Linux", "glibc wow64")
        packageFile(bionic, "FEXCore-Bionic", "android wow64")
        val bionicDll = File(protonDir("DroidDeck-Bionic-12.8-r24"), dll)
        val ownDll = File(protonDir("DroidDeck-Proton-11-12.8"), dll)

        assertThrows(IllegalStateException::class.java) { ComponentsManager.swap(context, "DroidDeck-Bionic-12.8-r24", linux) }
        assertThrows(IllegalStateException::class.java) { ComponentsManager.swap(context, "DroidDeck-Proton-11-12.8", bionic) }
        assertEquals("bundled FEX DroidDeck-Bionic-12.8-r24", bionicDll.readText())
        assertEquals("bundled FEX DroidDeck-Proton-11-12.8", ownDll.readText())

        ComponentsManager.swap(context, "DroidDeck-Bionic-12.8-r24", bionic)
        ComponentsManager.swap(context, "DroidDeck-Proton-11-12.8", linux)
        assertEquals("android wow64", bionicDll.readText())
        assertEquals("glibc wow64", ownDll.readText())
        assertTrue(File(protonDir("DroidDeck-Bionic-12.8-r24"), so).isFile)
        assertTrue(rows().none { it[1] == "DroidDeck-Bionic-12.8-r24" && it[3] == "fex" && it[6] == "-" })
    }

    @Test fun nothingIsRewrittenWhileTheToolsAreUnchanged() {
        proton("DroidDeck-Proton 11-11.8", "DroidDeck-Proton-11-11.8")
        packageFile()
        ComponentsManager.setGameComponent(context, scope, "fex", fex)
        val published = launchState().readText()
        val modified = launchState().lastModified()

        assertFalse(ComponentsManager.republish(context))
        assertEquals(published, launchState().readText())
        assertEquals(modified, launchState().lastModified())
    }

    @Test fun anUnreadableStateLeavesThePublishedSelectionsAlone() {
        proton("DroidDeck-Proton 11-11.7", "DroidDeck-Proton-11-11.7")
        packageFile()
        ComponentsManager.setGameComponent(context, scope, "fex", fex)
        val published = launchState().readText()
        val state = File(context.filesDir, "components/state.json").apply { writeText("{\"active\": ") }
        proton("DroidDeck-Proton 11-11.8", "DroidDeck-Proton-11-11.8")

        assertFalse(ComponentsManager.republish(context))
        assertEquals(published, launchState().readText())
        assertEquals("{\"active\": ", state.readText())
    }

    @Test fun withNoSelectionsNothingIsPublished() {
        proton("DroidDeck-Proton 11-11.8", "DroidDeck-Proton-11-11.8")

        assertFalse(ComponentsManager.republish(context))
        assertFalse(launchState().exists())
    }

    private fun scopedTools() = rows().filter { it[0] == scope }.map { it[1] }.toSet()

    private fun rows() = launchState().readLines().filter { it.isNotEmpty() }.map { it.split('\t') }

    private fun launchState() = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components/desired.tsv")

    private fun protonDir(name: String) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/$name")

    private fun proton(name: String, version: String) {
        val dir = protonDir(name)
        File(dir, "files/lib/wine").mkdirs()
        File(dir, "proton").writeText("#!/bin/sh\n")
        File(dir, "version").writeText("1 $version\n")
        File(dir, dll).apply { parentFile!!.mkdirs(); writeText("bundled FEX $version") }
        File(dir, so).apply { parentFile!!.mkdirs(); writeText("bundled unix FEX $version") }
    }

    private fun packageFile(name: String = fex, type: String = "FEXCore-Linux", text: String = "d2rfix28 wow64") {
        val out = File(context.filesDir, "components/packages/$name").apply { parentFile!!.mkdirs() }
        val profile = JSONObject().put("type", type).put("versionName", name.removeSuffix(".wcp")).toString().toByteArray()
        val body = text.toByteArray()
        TarArchiveOutputStream(ZstdOutputStream(out.outputStream())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("profile.json").apply { size = profile.size.toLong() })
            tar.write(profile)
            tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry(dll).apply { size = body.size.toLong() })
            tar.write(body)
            tar.closeArchiveEntry()
        }
    }
}
