package com.droiddeck.launcher.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSetupInstallScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(updateSucceedsOnCall: Int): Pair<Int, List<String>> {
        val sh = listOf("/bin/sh", "/usr/bin/sh").firstOrNull { File(it).canExecute() }
        assumeTrue(sh != null)
        val bin = tmp.newFolder("bin")
        val calls = File(tmp.root, "calls")
        fun tool(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
        tool("dpkg", "exit 0")
        tool("sleep", "exit 0")
        tool("apt-get", """
            echo "${'$'}*" >> '${calls.path}'
            case "${'$'}*" in
              *" update "*) n=${'$'}(grep -c ' update ' '${calls.path}'); [ "${'$'}n" -ge $updateSucceedsOnCall ] ;;
              *) exit 0 ;;
            esac
        """.trimIndent())
        val child = ProcessBuilder(sh, "-c", AgentSetup.installScript("echo harness-installed >> '${calls.path}'"))
            .apply { environment()["PATH"] = bin.path + File.pathSeparator + System.getenv("PATH") }
            .redirectErrorStream(true).start()
        child.inputStream.readBytes()
        val exit = child.waitFor()
        return exit to (if (calls.exists()) calls.readLines() else emptyList())
    }

    @Test fun theInstallStopsWhenEveryUpdateAttemptFails() {
        val (exit, calls) = run(updateSucceedsOnCall = 99)
        assertTrue(exit != 0)
        assertEquals(3, calls.count { " update " in "$it " })
        assertTrue(calls.none { "install" in it })
    }

    @Test fun theInstallContinuesWhenALaterUpdateAttemptSucceeds() {
        val (exit, calls) = run(updateSucceedsOnCall = 3)
        assertEquals(0, exit)
        assertTrue(calls.any { "install -y --no-install-recommends" in it })
        assertTrue("harness-installed" in calls.last())
    }
}
