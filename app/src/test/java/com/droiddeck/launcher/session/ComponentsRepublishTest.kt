package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun proton(name: String, version: String) {
        val dir = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/$name")
        File(dir, "files/lib/wine").mkdirs()
        File(dir, "proton").writeText("#!/bin/sh\n")
        File(dir, "version").writeText("1 $version\n")
        File(dir, dll).apply { parentFile!!.mkdirs(); writeText("bundled FEX $version") }
    }

    private fun packageFile() {
        val out = File(context.filesDir, "components/packages/$fex").apply { parentFile!!.mkdirs() }
        val profile = JSONObject().put("type", "FEXCore-Linux").put("versionName", "2610-d2rfix28").toString().toByteArray()
        val body = "d2rfix28 wow64".toByteArray()
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
