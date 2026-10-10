package com.droiddeck.launcher.agent

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentSecurityTest {
    private lateinit var context: Context

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        AgentAccess.setCommandsEnabled(context, false)
        ShadowBinder.setCallingUid(Process.myUid())
        ShadowLog.clear()
    }

    @After fun cleanup() {
        ShadowBinder.setCallingUid(Process.myUid())
        AgentAccess.setCommandsEnabled(context, false)
    }

    private fun refused(code: String, action: () -> Unit): AgentException {
        try {
            action()
            throw AssertionError("Expected $code")
        } catch (e: AgentException) {
            assertEquals(code, e.code)
            return e
        }
    }

    private fun call(provider: AgentBridgeProvider, method: String, request: JSONObject = JSONObject()): JSONObject {
        val extras = Bundle().apply {
            putString("request", Base64.encodeToString(request.toString().toByteArray(), Base64.NO_WRAP))
        }
        return JSONObject(provider.call(method, null, extras).getString("json")!!)
    }

    @Test fun commandsRequireTheToggleInEveryBuild() {
        assertFalse(AgentAccess.commandsAllowed(context))
        refused("AGENT_COMMANDS_DISABLED") { AgentAccess.requireCommands(context) }
        AgentAccess.setCommandsEnabled(context, true)
        assertTrue(AgentAccess.commandsAllowed(context))
        AgentAccess.requireCommands(context)
        AgentAccess.setCommandsEnabled(context, false)
        assertFalse(AgentAccess.commandsAllowed(context))
    }

    @Test fun runCannotBypassTheToggle() {
        refused("AGENT_COMMANDS_DISABLED") { AgentAccess.requireStart(context, JSONObject().put("mode", "run")) }
        AgentAccess.requireStart(context, JSONObject().put("mode", "steam"))
        AgentAccess.requireStart(context, JSONObject().put("mode", "desktop"))
        val blocked = Robolectric.buildActivity(AgentStartActivity::class.java).get()
        val start = AgentStartActivity::class.java.getDeclaredMethod("startSession", JSONObject::class.java).apply { isAccessible = true }
        val request = JSONObject().put("mode", "run").put("program", "/usr/bin/sh")
        start.invoke(blocked, request)
        assertTrue(blocked.isFinishing)
        assertNull(shadowOf(blocked).nextStartedActivity)
        AgentAccess.setCommandsEnabled(context, true)
        AgentAccess.requireStart(context, JSONObject().put("mode", "run"))
        val allowed = Robolectric.buildActivity(AgentStartActivity::class.java).get()
        start.invoke(allowed, request)
        assertEquals("/usr/bin/sh", shadowOf(allowed).nextStartedActivity.getStringExtra("program"))
    }

    @Test fun providerRefusesPrivilegedMethodsBeforePerformingThem() {
        val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
        for (method in listOf("guest", "cdp", "override", "prefs", "env")) {
            assertEquals(method, "AGENT_COMMANDS_DISABLED", call(provider, method).getJSONObject("error").getString("code"))
        }
        assertFalse(call(provider, "access").getBoolean("commands"))
        assertTrue(call(provider, "state").getBoolean("ok"))
    }

    @Test fun providerChecksCallerPermissionBeforeDecoding() {
        val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
        ShadowBinder.setCallingUid(Process.myUid() + 10001)
        try {
            provider.call("access", null, Bundle().apply { putString("request", "invalid") })
            throw AssertionError("Expected permission refusal")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("DUMP"))
        }
    }

    @Test fun oversizedRequestsAreRefused() {
        val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
        val response = provider.call("access", null, Bundle().apply { putString("request", "A".repeat(256 * 1024 + 1)) })
        assertEquals("INVALID_REQUEST", JSONObject(response.getString("json")!!).getJSONObject("error").getString("code"))
    }

    @Test fun oversizedResponsesReturnAnErrorInsteadOfOverflowingBinder() {
        AgentAccess.setCommandsEnabled(context, true)
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("large", "x".repeat(400 * 1024)).commit()
        val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
        val response = call(provider, "prefs", JSONObject().put("file", "session"))
        assertEquals("RESPONSE_TOO_LARGE", response.getJSONObject("error").getString("code"))
    }

    @Test fun invalidTimeoutsAreRefusedBeforeDispatchingToTheGuest() {
        AgentAccess.setCommandsEnabled(context, true)
        val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
        for (value in listOf("NaN", "Infinity", "invalid", "0", "601")) {
            val response = call(provider, "guest", JSONObject().put("timeout", value))
            assertEquals(value, "INVALID_REQUEST", response.getJSONObject("error").getString("code"))
        }
    }

    @Test fun directEnvironmentAndPreferenceCallsAlsoRequireConsent() {
        refused("AGENT_COMMANDS_DISABLED") { AgentEnv.list(context) }
        refused("AGENT_COMMANDS_DISABLED") { AgentEnv.set(context, listOf("TEST=value"), false) }
        refused("AGENT_COMMANDS_DISABLED") { AgentEnv.clear(context, "all") }
        refused("AGENT_COMMANDS_DISABLED") { AgentEnv.override(context, "gamescope") }
        refused("AGENT_COMMANDS_DISABLED") { AgentPrefs.get(context, "session") }
        refused("AGENT_COMMANDS_DISABLED") { AgentPrefs.set(context, "session", "test", "bool", "true") }
    }

    @Test fun disablingDiscardsEnvironmentAndBinaryOverrides() {
        AgentAccess.setCommandsEnabled(context, true)
        AgentEnv.set(context, listOf("NEXT=one"), false)
        AgentEnv.set(context, listOf("PERSISTENT=two"), true)
        File(AgentEnv.inbox(context).apply { mkdirs() }, "gamescope").writeText("test binary")
        AgentEnv.override(context, "gamescope")
        val installed = File(LinuxRuntime.rootDir(context), "opt/droiddeck-agent/bin/gamescope")
        assertTrue(installed.isFile)
        AgentAccess.setCommandsEnabled(context, false)
        assertFalse(installed.exists())
        assertTrue(AgentEnv.beginSession(context).isEmpty())
        AgentAccess.setCommandsEnabled(context, true)
        val state = AgentEnv.list(context)
        for (key in listOf("pending", "persistent", "active", "pendingOverrides", "activeOverrides")) {
            assertEquals(key, 0, state.getJSONArray(key).length())
        }
    }

    @Test fun oldPersistedExperimentsCannotApplyWhileDisabled() {
        File(context.filesDir, "agent-env.json").writeText("""{"pending":["TEST=one"],"persistent":["TEST=two"]}""")
        assertTrue(AgentEnv.beginSession(context).isEmpty())
        AgentAccess.setCommandsEnabled(context, true)
        assertEquals(0, AgentEnv.list(context).getJSONArray("persistent").length())
    }

    @Test fun authorisedExperimentsKeepTheirSessionLifecycle() {
        AgentAccess.setCommandsEnabled(context, true)
        AgentEnv.set(context, listOf("TEST=old", "STICKY=1"), true)
        AgentEnv.set(context, listOf("TEST=new"), false)
        assertEquals(listOf("TEST=old", "STICKY=1", "TEST=new"), AgentEnv.beginSession(context))
        AgentEnv.endSession(context)
        assertEquals(listOf("TEST=old", "STICKY=1"), AgentEnv.beginSession(context))
    }

    @Test fun environmentValuesAreNotLogged() {
        AgentAccess.setCommandsEnabled(context, true)
        AgentEnv.set(context, listOf("CUSTOM=synthetic-private-value"), false)
        AgentEnv.beginSession(context)
        assertFalse(ShadowLog.getLogsForTag("AgentEnv").any { it.msg.contains("synthetic-private-value") })
        assertTrue(ShadowLog.getLogsForTag("AgentEnv").any { it.msg.contains("CUSTOM") })
    }

    @Test fun passwordSemanticsNeverReturnTextOrDescriptions() {
        val config = SemanticsConfiguration().apply {
            this[SemanticsProperties.Password] = Unit
            this[SemanticsProperties.Text] = listOf(AnnotatedString("synthetic-secret"))
            this[SemanticsProperties.EditableText] = AnnotatedString("synthetic-secret")
            this[SemanticsProperties.ContentDescription] = listOf("synthetic-secret")
            this[SemanticsProperties.StateDescription] = "synthetic-secret"
        }
        val result = AgentUi.textFields(config)
        for (key in listOf("text", "editableText", "description", "state")) assertEquals("<redacted>", result.getString(key))
        assertFalse(result.toString().contains("synthetic-secret"))
    }

    @Test fun ordinarySemanticsStayReadableForAgents() {
        val config = SemanticsConfiguration().apply {
            this[SemanticsProperties.Text] = listOf(AnnotatedString("Play Steam"))
            this[SemanticsProperties.StateDescription] = "On"
        }
        val result = AgentUi.textFields(config)
        assertEquals("Play Steam", result.getString("text"))
        assertEquals("On", result.getString("state"))
    }

    @Test fun disablingDoesNotCrashOrLeaveQueuedValuesWhenCleanupIsUnsafe() {
        AgentAccess.setCommandsEnabled(context, true)
        AgentEnv.set(context, listOf("TEST=value"), true)
        val outside = File(context.filesDir, "cleanup-outside").apply { mkdirs() }
        val privateFile = File(outside, "keep").apply { writeText("private") }
        val link = File(LinuxRuntime.rootDir(context).apply { mkdirs() }, "opt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            AgentAccess.setCommandsEnabled(context, false)
            assertFalse(AgentAccess.commandsAllowed(context))
            val state = JSONObject(File(context.filesDir, "agent-env.json").readText())
            assertEquals(0, state.getJSONArray("persistent").length())
            assertEquals("private", privateFile.readText())
        } finally {
            Files.delete(link.toPath())
        }
    }

    @Test fun invalidEnvironmentErrorsDoNotEchoValues() {
        AgentAccess.setCommandsEnabled(context, true)
        for (line in listOf("=synthetic-private-value", "TEST=synthetic-private-value\nOTHER=x", "TEST=synthetic-private-value\u0000")) {
            val error = refused("INVALID_ENV") { AgentEnv.set(context, listOf(line), false) }
            assertFalse(error.message!!.contains("synthetic-private-value"))
        }
    }

    @Test fun preferencesCannotWriteTheGateOrUsePathAliases() {
        AgentAccess.setCommandsEnabled(context, true)
        refused("PROTECTED_PREFS") { AgentPrefs.set(context, "agent", "commands", "bool", "true") }
        for (name in listOf("../agent", "./agent", "/agent", "agent/../agent")) {
            refused("INVALID_PREF") { AgentPrefs.set(context, name, "commands", "bool", "true") }
            refused("INVALID_PREF") { AgentPrefs.get(context, name) }
        }
        assertTrue(AgentPrefs.set(context, "session", "test", "bool", "true").getBoolean("value"))
        val error = refused("INVALID_PREF") { AgentPrefs.set(context, "session", "test", "int", "synthetic-private-value") }
        assertFalse(error.message!!.contains("synthetic-private-value"))
    }

    @Test fun overrideRefusesLinksAndTraversalNames() {
        AgentAccess.setCommandsEnabled(context, true)
        for (name in listOf(".", "..", "../gamescope")) refused("INVALID_NAME") { AgentEnv.override(context, name) }
        val target = File(context.filesDir, "private-source").apply { writeText("private") }
        val link = File(AgentEnv.inbox(context).apply { mkdirs() }, "linked")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        refused("INVALID_NAME") { AgentEnv.override(context, "linked") }
    }

    @Test fun overrideNeverFollowsItsInstallDirectoryLink() {
        AgentAccess.setCommandsEnabled(context, true)
        val outside = File(context.filesDir, "outside").apply { mkdirs() }
        val root = LinuxRuntime.rootDir(context).apply { mkdirs() }
        val link = File(root, "opt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            File(AgentEnv.inbox(context).apply { mkdirs() }, "gamescope").writeText("test binary")
            refused("OVERRIDE_FAILED") { AgentEnv.override(context, "gamescope") }
            assertTrue(outside.listFiles()!!.isEmpty())
        } finally {
            Files.delete(link.toPath())
        }
    }
}
