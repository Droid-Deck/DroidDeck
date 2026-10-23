package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
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
class ComponentsArchiveTest {
    private lateinit var context: Context
    private val dll = "files/lib/wine/dxvk/aarch64-windows/d3d11.dll"
    private val payload = "downloaded DXVK"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        LinuxRuntime.rootDir(context).deleteRecursively()
    }

    @Test
    fun storedPackagesWithTarRootAndDirectoryEntriesRemainVisible() {
        val stored = File(context.filesDir, "components/packages/dxvk.wcp")
        writeWcp(stored)

        val pkg = ComponentsManager.packages(context).single()

        assertEquals("dxvk.wcp", pkg.file)
        assertEquals("dxvk", pkg.comp)
        assertEquals("1.10.3", pkg.version)
    }

    @Test
    fun importedDirectoryArchiveCanBeAppliedAndStagedForLaunch() {
        val source = File(context.cacheDir, "dxvk.wcp")
        writeWcp(source)
        val proton = proton()

        val imported = ComponentsManager.importPackage(context, source, "dxvk.wcp")
        ComponentsManager.swap(context, "proton-exp", imported.file)

        assertEquals(payload, File(proton, dll).readText())
        assertEquals(imported, ComponentsManager.packages(context).single())
        val row = launchState().readLines().map { it.split('\t') }.single { it[4] == dll }
        val staged = File(LinuxRuntime.rootDir(context), row[6].removePrefix("/"))
        assertEquals(payload, staged.readText())
        assertEquals(Hashes.sha256(staged), row[5])
    }

    @Test
    fun perGameSelectionStagesDirectoryArchivesWithoutChangingBundledFiles() {
        val source = File(context.cacheDir, "game-dxvk.wcp")
        writeWcp(source)
        val proton = proton()
        val imported = ComponentsManager.importPackage(context, source, "game-dxvk.wcp")

        ComponentsManager.setGameComponent(context, "42", "dxvk", imported.file)

        assertEquals("bundled DXVK", File(proton, dll).readText())
        val row = launchState().readLines().map { it.split('\t') }.single { it[0] == "42" && it[4] == dll }
        assertEquals(payload, File(LinuxRuntime.rootDir(context), row[6].removePrefix("/")).readText())
    }

    @Test
    fun directoryNormalizationKeepsFileAndTraversalChecks() {
        assertEquals("", ComponentsManager.safeArchivePath("./", isDirectory = true))
        assertEquals("", ComponentsManager.safeArchivePath(".", isDirectory = true))
        assertEquals("files/lib", ComponentsManager.safeArchivePath("./files/lib/", isDirectory = true))
        for (path in listOf(".", "./", "files/")) {
            assertThrows(path, IllegalArgumentException::class.java) { ComponentsManager.safeArchivePath(path) }
        }
        for (path in listOf("/", "/files/", "../", "./../", "././", "./files/../", "./files//", "files//lib/", "files\\lib/", "files/\u0000/")) {
            assertThrows(path, IllegalArgumentException::class.java) {
                ComponentsManager.safeArchivePath(path, isDirectory = true)
            }
        }
    }

    @Test
    fun profileReaderRejectsUnsafeDirectoriesAndLinksBeforeTheProfile() {
        for ((index, entry) in unsafeEntries().withIndex()) {
            val source = File(context.cacheDir, "unsafe-$index.wcp")
            writeWcp(source, entry, beforeProfile = true)
            assertThrows(entry.name, IllegalArgumentException::class.java) {
                ComponentsManager.importPackage(context, source, source.name)
            }
        }
        assertTrue(ComponentsManager.packages(context).isEmpty())
    }

    @Test
    fun bothExtractorsRejectUnsafeEntriesAfterTheProfileBeforePublishingFiles() {
        val proton = proton()
        for ((index, entry) in unsafeEntries().withIndex()) {
            val source = File(context.cacheDir, "unsafe-$index.wcp")
            writeWcp(source, entry)
            val imported = ComponentsManager.importPackage(context, source, source.name)

            assertThrows(entry.name, IllegalArgumentException::class.java) {
                ComponentsManager.swap(context, "proton-exp", imported.file)
            }
            assertThrows(entry.name, IllegalArgumentException::class.java) {
                ComponentsManager.setGameComponent(context, "42", "dxvk", imported.file)
            }
            assertEquals("bundled DXVK", File(proton, dll).readText())
            assertFalse(launchState().exists())
        }
    }

    private fun unsafeEntries(): List<TarArchiveEntry> = listOf(
        TarArchiveEntry("../escape/", TarConstants.LF_DIR, true),
        TarArchiveEntry("/files/", TarConstants.LF_DIR, true),
        TarArchiveEntry("./files//", TarConstants.LF_DIR, true),
        TarArchiveEntry("./files/../escape/", TarConstants.LF_DIR, true),
        TarArchiveEntry("./files/alias", TarConstants.LF_SYMLINK).apply { linkName = "../escape" },
        TarArchiveEntry("./files/alias/", TarConstants.LF_SYMLINK).apply { linkName = "../escape" },
        TarArchiveEntry("./files/alias", TarConstants.LF_LINK).apply { linkName = "../escape" },
        TarArchiveEntry("./files/pipe", TarConstants.LF_FIFO),
    )

    private fun writeWcp(file: File, extra: TarArchiveEntry? = null, beforeProfile: Boolean = false) {
        file.parentFile!!.mkdirs()
        val profile = JSONObject().put("type", "DXVK-Linux").put("versionName", "1.10.3")
            .put("description", "Directory archive fixture").toString().toByteArray()
        TarArchiveOutputStream(ZstdOutputStream(file.outputStream())).use { tar ->
            fun entry(header: TarArchiveEntry, bytes: ByteArray = byteArrayOf()) {
                header.size = bytes.size.toLong()
                tar.putArchiveEntry(header)
                if (bytes.isNotEmpty()) tar.write(bytes)
                tar.closeArchiveEntry()
            }
            entry(TarArchiveEntry("./", TarConstants.LF_DIR))
            if (beforeProfile) extra?.let { entry(it) }
            entry(TarArchiveEntry("./profile.json"), profile)
            for (directory in listOf("files", "files/lib", "files/lib/wine", "files/lib/wine/dxvk", "files/lib/wine/dxvk/aarch64-windows")) {
                entry(TarArchiveEntry("./$directory/", TarConstants.LF_DIR))
            }
            entry(TarArchiveEntry("./$dll"), payload.toByteArray())
            if (!beforeProfile) extra?.let { entry(it) }
        }
    }

    private fun proton(): File {
        val dir = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps/common/proton-exp")
        File(dir, dll).apply { parentFile!!.mkdirs(); writeText("bundled DXVK") }
        File(dir, "proton").writeText("#!/bin/sh\n")
        File(dir, "version").writeText("1 experimental-11\n")
        return dir
    }

    private fun launchState() = File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components/desired.tsv")
}
