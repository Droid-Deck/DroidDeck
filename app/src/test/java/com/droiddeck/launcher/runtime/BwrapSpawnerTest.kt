package com.droiddeck.launcher.runtime

import android.util.Base64
import org.robolectric.RuntimeEnvironment
import com.droiddeck.launcher.runtime.BwrapSpawner.Bind
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BwrapSpawnerTest {
    private val session = listOf(
        Bind("/dev", "/dev"),
        Bind("/cache/shm", "/dev/shm"),
        Bind("/proc", "/proc"),
        Bind("/rootfs/etc/droiddeck/proc/stat", "/proc/stat"),
        Bind("/data/files", "/data/files"),
    )

    @Test fun underRespectsPathBoundaries() {
        assertTrue(BwrapSpawner.under("/usr/lib", "/usr"))
        assertTrue(BwrapSpawner.under("/usr", "/usr"))
        assertFalse(BwrapSpawner.under("/usrlocal", "/usr"))
        assertTrue(BwrapSpawner.under("/anything", "/"))
    }

    @Test fun hostPathGoesThroughTheMostSpecificSessionBind() {
        assertEquals("/cache/shm/x", BwrapSpawner.hostPath(session, "/rootfs", "/dev/shm/x"))
        assertEquals("/dev/null", BwrapSpawner.hostPath(session, "/rootfs", "/dev/null"))
        assertEquals("/data/files/.wayland-rt/wayland-1", BwrapSpawner.hostPath(session, "/rootfs", "/data/files/.wayland-rt/wayland-1"))
    }

    @Test fun hostPathFallsBackToTheRootfs() {
        assertEquals("/rootfs/root/.local/share/flatpak", BwrapSpawner.hostPath(session, "/rootfs/", "/root/.local/share/flatpak"))
    }

    @Test fun bindingDevBringsTheSessionsStandInsAlong() {
        val binds = BwrapSpawner.bindsFor(session, "/rootfs", "/dev", "/dev")
        assertEquals(Bind("/dev", "/dev"), binds.first())
        assertTrue(Bind("/cache/shm", "/dev/shm") in binds)
        assertFalse(binds.any { it.guest.startsWith("/proc") })
    }

    @Test fun nestedBindsFollowARenamedDestination() {
        val binds = BwrapSpawner.bindsFor(session, "/rootfs", "/proc", "/newproc")
        assertTrue(Bind("/rootfs/etc/droiddeck/proc/stat", "/newproc/stat") in binds)
    }

    @Test fun normalizeResolvesDotsLexically() {
        assertEquals("/usr/lib/x", BwrapSpawner.normalize("/usr/lib/GL/../x"))
        assertEquals("/", BwrapSpawner.normalize("/../.."))
    }

    @Test fun bindSpecsRoundTrip() {
        assertEquals(Bind("/a", "/b"), Bind.parse("/a:/b"))
        assertEquals(Bind("/a", "/a"), Bind.parse("/a"))
        assertEquals("/a", Bind("/a", "/a").spec)
        assertEquals("/a:/b", Bind("/a", "/b").spec)
    }
}

/** The sandbox a Flatpak request describes, built on disk under a temporary directory. */
@RunWith(RobolectricTestRunner::class)
class BwrapSpawnerPlanTest {
    private lateinit var dir: File
    private lateinit var runtime: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("bwrap-plan").toFile()
        // Flatpak names the runtime by its path inside the rootfs.
        runtime = File(LinuxRuntime.rootDir(RuntimeEnvironment.getApplication()), "runtime-files")
        File(runtime, "lib").mkdirs()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        runtime.deleteRecursively()
    }

    private fun op(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun request(vararg ops: JSONObject) = JSONObject()
        .put("argv", JSONArray().put("/app/bin/game"))
        .put("env", JSONObject().put("FLATPAK_ID", "org.example.Game"))
        .put("cwd", "/app")
        .put("ops", JSONArray().apply { ops.forEach { put(it) } })

    @Test fun buildsTheTreeFlatpakAsksFor() {
        val context = RuntimeEnvironment.getApplication()
        val info = Base64.encodeToString("[Application]\nname=org.example.Game\n".toByteArray(), Base64.NO_WRAP)
        val plan = BwrapSpawner.plan(context, request(
            op("t" to "bind", "src" to "/runtime-files", "dest" to "/usr", "ro" to true),
            op("t" to "symlink", "target" to "usr/lib", "dest" to "/lib"),
            op("t" to "dir", "dest" to "/usr/lib/GL", "empty" to true),
            op("t" to "symlink", "target" to "default/lib/libEGL.so", "dest" to "/usr/lib/GL/libEGL.so"),
            op("t" to "dir", "dest" to "/tmp"),
            op("t" to "file", "dest" to "/.flatpak-info", "data" to info, "perms" to 0x180),
            op("t" to "bind", "src" to "/nonexistent-${System.nanoTime()}", "dest" to "/run/x", "try" to true),
        ), dir)

        // The runtime is bound at /usr, not written into.
        assertTrue(plan.binds.any { it.guest == "/usr" && File(it.host).canonicalPath == runtime.canonicalPath })
        assertFalse(File(runtime, "lib/GL").exists())
        // The tmpfs under /usr is a directory of ours, and the link Flatpak makes inside it lands there.
        val gl = plan.binds.single { it.guest == "/usr/lib/GL" }
        assertTrue(gl.host.startsWith(dir.path))
        assertEquals("default/lib/libEGL.so", Files.readSymbolicLink(File(gl.host, "libEGL.so").toPath()).toString())
        // Top-level links, directories and files are made in the sandbox root itself.
        assertEquals("usr/lib", Files.readSymbolicLink(File(plan.root, "lib").toPath()).toString())
        assertTrue(File(plan.root, "tmp").isDirectory)
        assertEquals("[Application]\nname=org.example.Game\n", File(plan.root, ".flatpak-info").readText())
        // A --bind-try whose source is missing is left out.
        assertFalse(plan.binds.any { it.guest == "/run/x" })
        assertEquals(listOf("/app/bin/game"), plan.argv)
        assertEquals("/app", plan.cwd)
        assertEquals("org.example.Game", plan.env["FLATPAK_ID"])
    }
}
