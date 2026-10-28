package com.droiddeck.launcher.update

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelfInstallerCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cleaningPreservesCurrentPartialAndValidatorOnly() {
        val dir = temporary.newFolder()
        listOf("current.apk", "current.apk.resume.properties", "old.apk", "old.apk.resume.properties")
            .forEach { File(dir, it).writeText(it) }
        SelfInstaller.cleanDownloads(dir, "current.apk")
        assertEquals(setOf("current.apk", "current.apk.resume.properties"), dir.list()!!.toSet())
        assertEquals("current.apk.resume.properties", File(dir, "current.apk.resume.properties").readText())
    }

    @Test fun invalidCompletedFileAndValidatorAreDiscarded() {
        val context = RuntimeEnvironment.getApplication()
        val file = temporary.newFile("invalid.apk").apply { writeText("bad") }
        val validator = File(file.path + ".resume.properties").apply { writeText("etag=old") }
        val apk = AppUpdates.Apk(file.name, "https://example.com/update.apk", 3, "0".repeat(64), context.packageName, 1, "0".repeat(64))
        assertTrue(runCatching { SelfInstaller.validate(context, file, apk) }.isFailure)
        assertFalse(file.exists())
        assertFalse(validator.exists())
    }
}
