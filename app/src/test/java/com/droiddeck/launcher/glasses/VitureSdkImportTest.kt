package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VitureSdkImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = File(temporary.root, "sdk")

    @Test fun stagesSupportedAndroidSdkWithoutActivatingCode() {
        val prepared = prepare()
        assertEquals("2.4.0", prepared.version)
        assertTrue(prepared.libraryBytes > 0)
        assertNull(VitureSdkImport.installedDirectory(root))
        assertTrue(File(prepared.directory, "LICENSE").readText().contains("VITURE"))
        assertFalse(File(prepared.directory, "archive.zip").exists())
        prepared.close()
        assertFalse(prepared.directory.exists())
    }

    @Test fun acceptsOneEnclosingReleaseFolderAndPersistsAnActivatedSdk() {
        val prepared = prepare(entries().mapKeys { "VITURE_XR_Glasses_SDK_for_Android/${it.key}" })
        var activated: File? = null
        val installed = prepared.install { directory ->
            activated = directory
            assertTrue(File(directory, "libcarina_vio.so").isFile)
            true
        }
        assertEquals(installed, activated)
        assertEquals(File(root, "current"), installed)
        assertEquals(installed, VitureSdkImport.installedDirectory(root))
        prepared.close()
        assertTrue(installed.exists())
    }

    @Test fun unsupportedVersionsAreRejectedBeforeActivation() {
        val files = entries().apply { put("include/viture_version.h", version("3", "0", "0")) }
        rejected(files)
        assertNull(VitureSdkImport.installedDirectory(root))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun requiresTheCompleteAndroidSdkAndNotAnApkOrAnUnrelatedZip() {
        for (required in listOf("android/arm64-v8a/libcarina_vio.so", "android/LICENSE", "android/NOTICE", "include/viture_version.h")) {
            rejected(entries().apply { remove(required) })
        }
        rejected(linkedMapOf("classes.dex" to ByteArray(32), "AndroidManifest.xml" to ByteArray(4)))
    }

    @Test fun rejectsWrongArchitectureAndLibrariesThatOnlyContainASdkName() {
        rejected(entries().apply {
            getValue("android/arm64-v8a/libglasses.so")[18] = 62
        })
        rejected(entries().apply {
            put("android/arm64-v8a/libglasses.so", "VITURE GetVersionString xr_device_provider_create".toByteArray())
        })
    }

    @Test fun rejectsMissingSpatialExportsAndUndefinedSymbolImports() {
        rejected(entries().apply {
            put("android/arm64-v8a/libglasses.so", elf("libglasses.so", glassesSymbols - "xr_device_provider_get_gl_pose_carina"))
        })
        rejected(entries().apply {
            put("android/arm64-v8a/libcarina_vio.so", elf("libcarina_vio.so", carinaSymbols, defined = false))
        })
    }

    @Test fun rejectsRenamedLibrariesAndTruncatedLoadSegments() {
        rejected(entries().apply { put("android/arm64-v8a/libcloud_protocol.so", elf("libfake.so", listOf("function"))) })
        rejected(entries().apply {
            val library = getValue("android/arm64-v8a/libglasses.so")
            ByteBuffer.wrap(library).order(ByteOrder.LITTLE_ENDIAN).putLong(64 + 32, Long.MAX_VALUE)
        })
    }

    @Test fun refusesUnsafePathsEvenForFilesItWouldOtherwiseIgnore() {
        for (path in listOf("../outside", "/absolute", "C:/absolute", "a/../outside", "a\\outside", "a//outside")) {
            rejected(entries().apply { put(path, byteArrayOf(1)) })
        }
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test fun refusesMixedOrDuplicateSdkRoots() {
        rejected(entries().apply { put("another/android/arm64-v8a/libglasses.so", elf("libglasses.so", glassesSymbols)) })
        rejected(entries().mapKeys { if (it.key == "android/LICENSE") "other/${it.key}" else it.key })
    }

    @Test fun refusesDuplicateZipEntries() {
        val bytes = zip(entries().apply { put("android/LICENSe", "VITURE license".toByteArray()) })
        val old = "android/LICENSe".toByteArray()
        val replacement = "android/LICENSE".toByteArray()
        for (offset in 0..bytes.size - old.size) {
            if (old.indices.all { bytes[offset + it] == old[it] }) replacement.copyInto(bytes, offset)
        }
        assertThrows(IOException::class.java) { VitureSdkImport.prepare(root, ByteArrayInputStream(bytes)) }
    }

    @Test fun boundsBothCompressedAndExpandedInputAndFileCount() {
        val bytes = zip(entries())
        for (limits in listOf(
            VitureSdkImport.Limits(archiveBytes = bytes.size.toLong() - 1),
            VitureSdkImport.Limits(expandedBytes = 100),
            VitureSdkImport.Limits(libraryBytes = 100),
            VitureSdkImport.Limits(entries = 2),
        )) {
            assertThrows(IOException::class.java) { VitureSdkImport.prepare(root, ByteArrayInputStream(bytes), limits) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun rejectsACorruptOrTruncatedArchive() {
        val bytes = zip(entries())
        for (input in listOf(byteArrayOf(1, 2, 3), bytes.copyOf(bytes.size - 22), bytes.copyOf(bytes.size / 2))) {
            assertThrows(IOException::class.java) { VitureSdkImport.prepare(root, ByteArrayInputStream(input)) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun countsPayloadsEvenWhenZipEntriesClaimToBeDirectories() {
        val files = entries()
        val limit = files.values.sumOf { it.size.toLong() } + 100
        files["misleading-directory/"] = ByteArray(4096)
        assertThrows(IOException::class.java) {
            VitureSdkImport.prepare(root, ByteArrayInputStream(zip(files)), VitureSdkImport.Limits(expandedBytes = limit))
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun aClosedPreparedImportCannotBeInstalled() {
        val prepared = prepare()
        prepared.close()
        assertThrows(IllegalStateException::class.java) { prepared.install { true } }
        assertNull(VitureSdkImport.installedDirectory(root))
    }

    @Test fun aFailedReplacementRestoresThePreviouslyInstalledSdk() {
        val original = prepare().install { true }
        val marker = File(original, "LICENSE").readBytes()
        val replacement = prepare(entries().apply { put("android/LICENSE", "VITURE replacement license".toByteArray()) })
        assertThrows(IOException::class.java) { replacement.install { false } }
        assertArrayEquals(marker, File(original, "LICENSE").readBytes())
        assertEquals(original, VitureSdkImport.installedDirectory(root))
        replacement.close()
        assertEquals(listOf("current"), root.listFiles()!!.map { it.name })
    }

    @Test fun aFailedFirstActivationLeavesNoInstalledSdk() {
        val prepared = prepare()
        assertThrows(IOException::class.java) { prepared.install { throw IOException("load failed") } }
        assertNull(VitureSdkImport.installedDirectory(root))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun detectsModifiedStagedAndInstalledLibraries() {
        val prepared = prepare()
        File(prepared.directory, "libglasses.so").appendBytes(byteArrayOf(1))
        var activated = false
        assertThrows(IOException::class.java) { prepared.install { activated = true; true } }
        assertFalse(activated)
        prepared.close()
        val installed = prepare().install { true }
        File(installed, "libglasses.so").appendBytes(byteArrayOf(1))
        assertNull(VitureSdkImport.installedDirectory(root))
    }

    @Test fun recoversAnInterruptedDirectorySwap() {
        val installed = prepare().install { true }
        assertTrue(installed.renameTo(File(root, "previous")))
        assertEquals(installed, VitureSdkImport.installedDirectory(root))
    }

    @Test fun recoveryRemovesAbandonedCopiesAndUnconfirmedCandidatesButPreservesOtherFiles() {
        val installed = prepare().install { true }
        val incomplete = orphanStage().apply { mkdir() }
        File(incomplete, "archive.zip").writeBytes(byteArrayOf(1, 2, 3))
        val complete = orphanStage()
        prepare().use { it.directory.copyRecursively(complete) }
        val unrelated = File(root, "stage-not-an-import").apply { mkdir() }
        val unrelatedFile = File(unrelated, "keep.txt").apply { writeText("keep") }

        assertEquals(installed, VitureSdkImport.installedDirectory(root))
        assertFalse(incomplete.exists())
        assertFalse(complete.exists())
        assertEquals("keep", unrelatedFile.readText())
        assertTrue(File(installed, "libglasses.so").isFile)
    }

    @Test fun recoveryPreservesImportsAwaitingConfirmation() {
        prepare().use { first ->
            prepare().use { second ->
                val abandoned = orphanStage()
                first.directory.copyRecursively(abandoned)
                assertNull(VitureSdkImport.installedDirectory(File(root, ".")))
                assertFalse(abandoned.exists())
                assertTrue(first.directory.isDirectory)
                assertTrue(second.directory.isDirectory)
                first.install { true }
                assertTrue(second.directory.isDirectory)
                second.install { true }
            }
        }
        assertNotNull(VitureSdkImport.installedDirectory(root))
    }

    @Test fun recoveryPreservesAnImportWhileItsArchiveReadIsBlocked() {
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val input = object : ByteArrayInputStream(zip(entries())) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(resume.await(5, TimeUnit.SECONDS)) { "Import read was not resumed" }
                return super.read(buffer, offset, length)
            }
        }
        val imported = executor.submit<VitureSdkImport.Prepared> { VitureSdkImport.prepare(root, input) }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val stage = root.listFiles()!!.single()
            assertTrue(File(stage, "archive.zip").isFile)
            assertNull(VitureSdkImport.installedDirectory(root))
            assertTrue(stage.isDirectory)
        } finally {
            resume.countDown()
            try {
                imported.get(5, TimeUnit.SECONDS).use { it.install { true } }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun closedAndPromotedStagesNoLongerPreventRecoveryCleanup() {
        for (outcome in listOf("closed", "installed", "rejected")) {
            val prepared = prepare()
            val formerStage = prepared.directory
            when (outcome) {
                "closed" -> prepared.close()
                "installed" -> prepared.install { true }
                else -> assertThrows(IOException::class.java) { prepared.install { false } }
            }
            assertTrue(formerStage.mkdir())
            File(formerStage, "leftover").writeText("retry cleanup")
            VitureSdkImport.installedDirectory(root)
            assertFalse("Stage ownership leaked after $outcome", formerStage.exists())
            prepared.close()
        }
    }

    @Test fun failedPreparationDoesNotPreventRecoveryCleanup() {
        var formerStage: File? = null
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                formerStage = root.listFiles()!!.single()
                throw IOException("source failed")
            }
        }
        assertThrows(IOException::class.java) { VitureSdkImport.prepare(root, input) }
        val abandoned = formerStage!!
        assertTrue(abandoned.mkdir())
        File(abandoned, "leftover").writeText("retry cleanup")
        assertNull(VitureSdkImport.installedDirectory(root))
        assertFalse(abandoned.exists())
    }

    @Test fun abandonedStageCleanupDoesNotFollowSymbolicLinks() {
        val outside = temporary.newFolder("outside-sdk")
        val preserved = File(outside, "keep.txt").apply { writeText("keep") }
        assertTrue(root.mkdir())
        val abandoned = orphanStage().apply { mkdir() }
        val linkedStage = orphanStage()
        try {
            Files.createSymbolicLink(File(abandoned, "outside").toPath(), outside.toPath())
            Files.createSymbolicLink(linkedStage.toPath(), outside.toPath())
        } catch (error: IOException) {
            assumeNoException("Symbolic links are unavailable", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable", error)
        }
        assertNull(VitureSdkImport.installedDirectory(root))
        assertEquals("keep", preserved.readText())
        assertFalse(abandoned.exists())
        assertFalse(linkedStage.exists())
    }

    @Test fun activationRemainsPendingUntilTheLoaderSucceeds() {
        prepare().install {
            assertTrue(File(root, "activation.pending").isFile)
            true
        }
        assertFalse(File(root, "activation.pending").exists())
        assertNotNull(VitureSdkImport.installedDirectory(root))
    }

    @Test fun anInterruptedFirstActivationIsDiscardedBeforeStartupCanLoadIt() {
        val prepared = prepare()
        File(root, "activation.pending").writeBytes(byteArrayOf(1))
        assertTrue(prepared.directory.renameTo(File(root, "current")))
        assertNull(VitureSdkImport.installedDirectory(root))
        assertTrue(root.listFiles()!!.isEmpty())
        assertNull(VitureSdkImport.installedDirectory(root))
        prepared.close()
    }

    @Test fun anInterruptedReplacementRestoresThePreviouslyActivatedSdk() {
        val original = prepare().install { true }
        val license = File(original, "LICENSE").readBytes()
        val replacement = prepare(entries().apply { put("android/LICENSE", "VITURE replacement".toByteArray()) })
        assertTrue(original.renameTo(File(root, "previous")))
        File(root, "activation.pending").writeBytes(byteArrayOf(1))
        assertTrue(replacement.directory.renameTo(original))
        assertEquals(original, VitureSdkImport.installedDirectory(root))
        assertArrayEquals(license, File(original, "LICENSE").readBytes())
        assertEquals(original, VitureSdkImport.installedDirectory(root))
        assertEquals(listOf("current"), root.listFiles()!!.map { it.name })
        replacement.close()
    }

    @Test fun recoveryBeforeCandidatePromotionPreservesTheBackup() {
        val original = prepare().install { true }
        val replacement = prepare()
        assertTrue(original.renameTo(File(root, "previous")))
        File(root, "activation.pending").writeBytes(byteArrayOf(1))
        assertEquals(original, VitureSdkImport.installedDirectory(root))
        assertTrue(replacement.directory.isDirectory)
        assertFalse(File(root, "activation.pending").exists())
        replacement.close()
    }

    @Test fun elfWorkBudgetIncludesRepeatedReferencesToTheSameSymbolTable() {
        val limits = VitureSdkImport.Limits(elfMetadataBytes = 2048)
        VitureSdkImport.prepare(root, ByteArrayInputStream(zip(entries())), limits).close()
        val files = entries().apply {
            put("android/arm64-v8a/libglasses.so", elf("libglasses.so", glassesSymbols, symbolTables = 12))
        }
        val error = assertThrows(IOException::class.java) {
            VitureSdkImport.prepare(root, ByteArrayInputStream(zip(files)), limits)
        }
        assertTrue(error.message!!.contains("excessive ELF metadata"))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun elfValidationHonorsCancellationAndClosesItsFile() {
        val library = File(temporary.root, "libglasses.so").apply { writeBytes(elf(name, glassesSymbols)) }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { VitureSdkImport.Elf.validate(library) }
        } finally { Thread.interrupted() }
        assertTrue(library.delete())
    }

    @Test fun cancellationClosesInputAndRemovesStagedFiles() {
        var closed = false
        val input = object : ByteArrayInputStream(zip(entries())) {
            override fun close() { closed = true; super.close() }
        }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { VitureSdkImport.prepare(root, input) }
        } finally { Thread.interrupted() }
        assertTrue(closed)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun prepare(files: Map<String, ByteArray> = entries()) =
        VitureSdkImport.prepare(root, ByteArrayInputStream(zip(files)))

    private fun orphanStage() = File(root, "stage-${UUID.randomUUID()}")

    private fun rejected(files: Map<String, ByteArray>) {
        assertThrows(IOException::class.java) { prepare(files) }
    }

    private fun entries() = linkedMapOf(
        "android/arm64-v8a/libglasses.so" to elf("libglasses.so", glassesSymbols),
        "android/arm64-v8a/libcarina_vio.so" to elf("libcarina_vio.so", carinaSymbols),
        "android/arm64-v8a/libcloud_protocol.so" to elf("libcloud_protocol.so", listOf("cloud_function")),
        "android/LICENSE" to "VITURE SDK license".toByteArray(),
        "android/NOTICE" to "Third-party notices".toByteArray(),
        "include/viture_version.h" to version("2", "4", "0"),
    )

    private fun version(major: String, minor: String, patch: String) =
        "#define VITURE_VERSION_MAJOR $major\n#define VITURE_VERSION_MINOR $minor\n#define VITURE_VERSION_PATCH $patch\n".toByteArray()

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        } }
    }.toByteArray()

    private fun elf(name: String, exports: List<String>, defined: Boolean = true, symbolTables: Int = 1): ByteArray {
        val names = listOf(name) + exports
        val strings = ("\u0000" + names.joinToString("\u0000") + "\u0000").toByteArray()
        val stringOffset = 128
        val symbolOffset = (stringOffset + strings.size + 7) / 8 * 8
        val symbolSize = (exports.size + 1) * 24
        val dynamicOffset = symbolOffset + symbolSize
        val sectionOffset = dynamicOffset + 32
        val sectionCount = 3 + symbolTables
        val data = ByteBuffer.allocate(sectionOffset + sectionCount * 64).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0, 0x464c457f).put(4, 2).put(5, 1).put(6, 1)
        data.putShort(16, 3).putShort(18, 183).putInt(20, 1)
        data.putLong(32, 64).putLong(40, sectionOffset.toLong())
        data.putShort(52, 64).putShort(54, 56).putShort(56, 1).putShort(58, 64).putShort(60, sectionCount.toShort())
        data.putInt(64, 1).putLong(64 + 8, 0).putLong(64 + 32, data.capacity().toLong()).putLong(64 + 40, data.capacity().toLong())
        strings.copyInto(data.array(), stringOffset)
        var nameOffset = 1 + name.toByteArray().size + 1
        for ((index, export) in exports.withIndex()) {
            val offset = symbolOffset + (index + 1) * 24
            data.putInt(offset, nameOffset).put(offset + 4, 0x12).putShort(offset + 6, if (defined) 1 else 0)
            nameOffset += export.toByteArray().size + 1
        }
        data.putLong(dynamicOffset, 14).putLong(dynamicOffset + 8, 1)
        fun section(index: Int, type: Int, offset: Int, size: Int, link: Int, stride: Int) {
            val at = sectionOffset + index * 64
            data.putInt(at + 4, type).putLong(at + 24, offset.toLong()).putLong(at + 32, size.toLong())
                .putInt(at + 40, link).putLong(at + 56, stride.toLong())
        }
        section(1, 3, stringOffset, strings.size, 0, 0)
        section(2, 11, symbolOffset, symbolSize, 1, 24)
        section(3, 6, dynamicOffset, 32, 1, 16)
        for (index in 4 until sectionCount) section(index, 11, symbolOffset, symbolSize, 1, 24)
        return data.array()
    }

    private val glassesSymbols = listOf("GetVersionString", "xr_device_provider_create", "xr_device_provider_initialize",
        "xr_device_provider_destroy", "xr_device_provider_get_gl_pose_carina", "xr_device_provider_register_callbacks_carina")
    private val carinaSymbols = listOf("carina_init", "carina_start", "carina_stop", "carina_vio_get_gl_pose")
}
