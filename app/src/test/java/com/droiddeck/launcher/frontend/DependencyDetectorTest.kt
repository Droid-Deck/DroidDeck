package com.droiddeck.launcher.frontend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DependencyDetectorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun foldersAboveTheGameDoNotChangeWhatIsFound() {
        val folder = File(tmp.newFolder("Games 2005", "agility"), "Game")
        File(folder, "_CommonRedist/vcredist/2013").mkdirs()
        File(folder, "_CommonRedist/vcredist/2013/vcredist_x64.exe").writeText("x")
        assertEquals(listOf("vcredist2013"), DependencyDetector.detect(folder).map { it.componentName })
    }
}
