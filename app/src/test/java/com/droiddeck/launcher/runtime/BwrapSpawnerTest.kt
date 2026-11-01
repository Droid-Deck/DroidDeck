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

@RunWith(RobolectricTestRunner::class)
class BwrapSpawnerPlanTest {
    private lateinit var dir: File
    private lateinit var runtime: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("bwrap-plan").toFile()
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

        assertTrue(plan.binds.any { it.guest == "/usr" && File(it.host).canonicalPath == runtime.canonicalPath })
        assertFalse(File(runtime, "lib/GL").exists())
        val gl = plan.binds.single { it.guest == "/usr/lib/GL" }
        assertTrue(gl.host.startsWith(dir.path))
        assertEquals("default/lib/libEGL.so", Files.readSymbolicLink(File(gl.host, "libEGL.so").toPath()).toString())
        assertEquals("usr/lib", Files.readSymbolicLink(File(plan.root, "lib").toPath()).toString())
        assertTrue(File(plan.root, "tmp").isDirectory)
        assertEquals("[Application]\nname=org.example.Game\n", File(plan.root, ".flatpak-info").readText())
        assertFalse(plan.binds.any { it.guest == "/run/x" })
        assertEquals(listOf("/app/bin/game"), plan.argv)
        assertEquals("/app", plan.cwd)
        assertEquals("org.example.Game", plan.env["FLATPAK_ID"])
    }

    @Test fun sandboxesStartThroughTheirOwnEnvByDefault() {
        val plan = BwrapSpawner.plan(RuntimeEnvironment.getApplication(), request(op("t" to "dir", "dest" to "/tmp")), dir)
        assertEquals("/usr/bin/env", plan.envTool)
    }

    @Test fun fexSandboxesStartThroughTheEnvTheyBringAndSkipArmPreloads() {
        val context = RuntimeEnvironment.getApplication()
        val rootfs = LinuxRuntime.rootDir(context)
        val lib = File(rootfs, "usr/local/lib/libblsession.so").apply { parentFile?.mkdirs(); writeText("") }
        val preload = File(rootfs, "etc/ld.so.preload").apply { parentFile?.mkdirs(); writeText("/usr/local/lib/libblsession.so\n") }
        try {
            val plan = BwrapSpawner.plan(context, request(op("t" to "dir", "dest" to "/tmp"))
                .put("envTool", "/run/droiddeck-fex/bin/env"), dir)
            assertEquals("/run/droiddeck-fex/bin/env", plan.envTool)
            assertFalse(plan.env.containsKey("LD_PRELOAD"))
            assertFalse(plan.binds.any { it.guest.startsWith("/run/droiddeck-preload") })
        } finally {
            listOf(lib, preload).forEach { it.delete() }
        }
    }

    @Test fun sandboxedAppsSeeTheSessionsNetworkLink() {
        val context = RuntimeEnvironment.getApplication()
        val rootfs = LinuxRuntime.rootDir(context)
        val lib = File(rootfs, "usr/local/lib/libblsession.so").apply { parentFile?.mkdirs(); writeText("") }
        val preload = File(rootfs, "etc/ld.so.preload").apply { parentFile?.mkdirs(); writeText("/usr/local/lib/libblsession.so\n") }
        val link = File(rootfs, "etc/droiddeck-net").apply { writeText("if wlan0 2 1500\n") }
        try {
            val plan = BwrapSpawner.plan(context, request(op("t" to "dir", "dest" to "/tmp")), dir)
            assertEquals("/run/droiddeck-preload/libblsession.so", plan.env["LD_PRELOAD"])
            assertEquals(link.path, plan.binds.single { it.guest == "/etc/droiddeck-net" }.host)
        } finally {
            listOf(lib, preload, link).forEach { it.delete() }
        }
    }

    @Test fun zypakLaunchesTheBrowserWithoutItsSandbox() {
        val context = RuntimeEnvironment.getApplication()
        val app = File(runtime, "app").apply { File(this, "bin").mkdirs() }
        File(app, "bin/zypak-wrapper.sh").writeText("#!/usr/bin/bash\nexec zypak-helper host - \"\$@\"\n")
        Files.createSymbolicLink(File(app, "bin/zypak-wrapper").toPath(), java.nio.file.Paths.get("/app/bin/zypak-wrapper.sh"))
        val plan = BwrapSpawner.plan(context, request(
            op("t" to "bind", "src" to "/runtime-files/app", "dest" to "/app", "ro" to true),
        ), dir)

        val shim = plan.binds.single { it.guest == "/app/bin/zypak-wrapper.sh" }
        assertTrue(shim.host.startsWith(dir.path))
        assertTrue(File(shim.host).readText().contains("exec \"\$program\" --no-sandbox \"\$@\""))
        assertFalse(plan.binds.any { it.guest == "/app/bin/zypak-wrapper" })
        assertTrue(File(app, "bin/zypak-wrapper.sh").readText().contains("zypak-helper"))
    }

    @Test fun appsWithoutZypakKeepTheirFiles() {
        val context = RuntimeEnvironment.getApplication()
        File(runtime, "app/bin").mkdirs()
        val plan = BwrapSpawner.plan(context, request(
            op("t" to "bind", "src" to "/runtime-files/app", "dest" to "/app", "ro" to true),
        ), dir)

        assertFalse(plan.binds.any { it.guest.startsWith("/app/bin/") })
        assertFalse(File(dir, "zypak-wrapper").exists())
    }

    @Test fun preloadsStayOutOfSandboxesWithAnOlderGlibc() {
        val base = Files.createTempDirectory("glibc").toFile()
        try {
            val rootfs = File(base, "rootfs")
            File(rootfs, "usr/local/lib").mkdirs()
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/ld.so.preload").writeText("/usr/local/lib/libone.so\n/usr/local/lib/libtwo.so\n")
            File(rootfs, "usr/local/lib/libone.so").writeText("x GLIBC_2.17 y GLIBC_2.34 z")
            File(rootfs, "usr/local/lib/libtwo.so").writeText("GLIBC_2.38 GLIBC_2.25")
            val old = File(base, "old-libc").apply { writeText("GNU C Library (Ubuntu GLIBC 2.23-0ubuntu11) stable release version 2.23, by Roland") }
            val new = File(base, "new-libc").apply { writeText("GNU C Library (Ubuntu GLIBC 2.43-0ubuntu1) stable release version 2.43.") }
            val same = File(base, "same-libc").apply { writeText("stable release version 2.38.") }
            assertFalse(BwrapSpawner.preloadFits(rootfs.path, old.path))
            assertTrue(BwrapSpawner.preloadFits(rootfs.path, new.path))
            assertTrue(BwrapSpawner.preloadFits(rootfs.path, same.path))
            assertTrue(BwrapSpawner.preloadFits(rootfs.path, File(base, "missing").path))
        } finally {
            base.deleteRecursively()
        }
    }
}
