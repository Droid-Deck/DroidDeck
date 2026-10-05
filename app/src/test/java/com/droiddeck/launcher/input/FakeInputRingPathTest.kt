package com.droiddeck.launcher.input

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The ring paths handed to the guest (FAKE_EVDEV_MEMFD_PATHS) must keep the spelling the session
 * tree is bound under. On ColorOS 16 /data/user/0 resolves to /data/data, the guest only has the
 * first bound, and a canonical export left every pad dead (#166). A symlinked directory stands in
 * for that here.
 */
class FakeInputRingPathTest {
    private lateinit var base: File
    private lateinit var real: File
    private lateinit var link: File

    @Before fun setUp() {
        base = Files.createTempDirectory("ring-path").toFile()
        real = File(base, "data-data").apply { mkdirs() }
        link = File(base, "data-user-0")
        Files.createSymbolicLink(link.toPath(), real.toPath())
    }

    @After fun tearDown() {
        base.deleteRecursively()
    }

    @Test fun ringsSitBesideTheFakeInputNodes() {
        val fakeInputDir = File(link, "files/session/dev/input")
        assertEquals(File(link, "files/session/dev/fakeinput-rings/ring2"), FakeInputRingPaths.ringFile(fakeInputDir, 2))
    }

    @Test fun theExportKeepsTheBoundSpellingThroughALinkedDataDirectory() {
        val fakeInputDir = File(link, "files/session/dev/input").apply { mkdirs() }
        val ring = FakeInputRingPaths.ringFile(fakeInputDir, 0)!!.apply { parentFile!!.mkdirs(); writeText("") }
        // The link really does resolve elsewhere, as /data/user/0 does on the affected devices.
        assertTrue(ring.canonicalPath.startsWith(real.canonicalPath + "/"))
        val exported = FakeInputRingPaths.exportPath(ring)
        assertEquals(File(link, "files/session/dev/fakeinput-rings/ring0").path, exported)
        assertFalse("$exported resolved through the link", exported.startsWith(real.path + "/"))
        assertTrue("$exported does not open", File(exported).isFile)
    }

    @Test fun aRelativeInputDirIsExportedAbsolute() {
        val exported = FakeInputRingPaths.exportPath(FakeInputRingPaths.ringFile(File("session/dev/input"), 1)!!)
        assertTrue(exported, File(exported).isAbsolute)
        assertTrue(exported, exported.endsWith("/session/dev/fakeinput-rings/ring1"))
    }
}
