package com.droiddeck.launcher.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AgentEnvRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var files: File
    private var refuseCompensatingWrite = false

    @Before fun prepare() {
        files = tmp.newFolder("files")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File {
                val state = File(files, "agent-env.json")
                if (refuseCompensatingWrite && state.isFile &&
                    (JSONObject(state.readText()).optJSONArray("active")?.length() ?: 0) > 0) {
                    File(files, "agent-env.json.tmp").mkdir()
                }
                return files
            }
            override fun getApplicationContext(): Context = this
        }
        AgentAccess.setCommandsEnabled(context, true)
    }

    @After fun cleanup() {
        refuseCompensatingWrite = false
        AgentAccess.setCommandsEnabled(context, false)
    }

    @Test fun aFailedSetupDoesNotNeedASecondWriteToKeepTheQueue() {
        AgentEnv.set(context, listOf("NEXT=one-shot"), false)
        AgentEnv.set(context, listOf("STICKY=persistent"), true)
        File(AgentEnv.inbox(context).apply { mkdirs() }, "tool").writeText("test binary")
        AgentEnv.override(context, "tool")
        val agent = File(LinuxRuntime.rootDir(context), "opt/droiddeck-agent")
        val savedAgent = File(agent.parentFile, "saved-agent")
        assertTrue(agent.renameTo(savedAgent))
        Files.createSymbolicLink(agent.toPath(), savedAgent.toPath())
        refuseCompensatingWrite = true
        val failure = try {
            runCatching { AgentEnv.beginSession(context) }.exceptionOrNull()
        } finally {
            refuseCompensatingWrite = false
            Files.deleteIfExists(agent.toPath())
            assertTrue(savedAgent.renameTo(agent))
            File(files, "agent-env.json.tmp").delete()
        }
        assertTrue(failure is AgentException)
        assertEquals("OVERRIDE_FAILED", (failure as AgentException).code)

        AgentEnv.endSession(context)
        val retry = AgentEnv.beginSession(context)
        assertTrue("undelivered one-shot environment was lost", "NEXT=one-shot" in retry)
        assertTrue("STICKY=persistent" in retry)
        val activeSession = AgentEnv.list(context).getString("activeOverrideSession")
        assertTrue("the queued binary override was lost", File(agent, "sessions/$activeSession/bin/tool").isFile)
        assertTrue(retry.any { it.startsWith("PATH=/opt/droiddeck-agent/sessions/") })

        AgentEnv.endSession(context)
        assertEquals(listOf("STICKY=persistent"), AgentEnv.beginSession(context))
        assertFalse(File(agent, "bin/tool").exists())
    }

    @Test fun aFailedStatePublicationLeavesOneShotInputsPending() {
        AgentEnv.set(context, listOf("NEXT=one-shot"), false)
        val staged = File(files, "agent-env.json.tmp").apply { mkdir() }
        try {
            assertTrue(runCatching { AgentEnv.beginSession(context) }.isFailure)
        } finally {
            staged.delete()
        }
        AgentEnv.endSession(context)
        assertEquals(listOf("NEXT=one-shot"), AgentEnv.beginSession(context))
        AgentEnv.endSession(context)
        assertTrue(AgentEnv.beginSession(context).isEmpty())
    }

    @Test fun overridesAreIsolatedUntilEachSessionsTeardownCompletes() {
        File(AgentEnv.inbox(context).apply { mkdirs() }, "old-tool").writeText("old binary")
        AgentEnv.override(context, "old-tool")
        val oldEnvironment = AgentEnv.beginSession(context, "old-session")
        val oldBin = File(LinuxRuntime.rootDir(context), "opt/droiddeck-agent/sessions/old-session/bin")
        assertTrue(oldEnvironment.any { it.startsWith("PATH=/opt/droiddeck-agent/sessions/old-session/bin:") })
        assertTrue(File(oldBin, "old-tool").isFile)

        File(AgentEnv.inbox(context), "new-tool").writeText("new binary")
        AgentEnv.override(context, "new-tool")
        val newEnvironment = AgentEnv.beginSession(context, "new-session")
        val newBin = File(LinuxRuntime.rootDir(context), "opt/droiddeck-agent/sessions/new-session/bin")
        assertTrue(newEnvironment.any { it.startsWith("PATH=/opt/droiddeck-agent/sessions/new-session/bin:") })
        assertFalse(newEnvironment.any { it.contains("old-session") || it.contains("old-tool") })
        assertTrue("the old binary remains available until its teardown", File(oldBin, "old-tool").isFile)
        assertTrue(File(newBin, "new-tool").isFile)

        AgentEnv.endSession(context, "old-session")
        assertFalse(File(oldBin, "old-tool").exists())
        assertTrue("old teardown must not clear new session state", File(newBin, "new-tool").isFile)
        assertEquals("new-session", AgentEnv.list(context).getString("activeOverrideSession"))

        AgentEnv.endSession(context, "new-session")
        assertFalse(File(newBin, "new-tool").exists())
    }
}
