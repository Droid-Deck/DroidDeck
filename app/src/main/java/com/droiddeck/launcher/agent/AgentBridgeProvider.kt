package com.droiddeck.launcher.agent

import com.droiddeck.launcher.session.SessionPaths
import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.frontend.GameLaunchLink
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The shell's control surface (docs/agent-control.md), in every build: the provider and its start
 * Activity need android.permission.DUMP, held by the shell and privileged or explicitly granted callers. Commands that
 * reach into the app's sandbox are further gated by [AgentAccess].
 *
 * A method's request is JSON, base64-encoded in the "request" extra; every answer is JSON in the
 * "json" key of the returned Bundle, {"ok": true, ...} or {"ok": false, "error": {code, message}}.
 */
class AgentBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        Thread({
            try {
                SessionArtifacts.finishAbandoned(appContext)
            } finally {
                recoveryComplete.countDown()
            }
        }, "agent-recover-artifacts").start()
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        if (Binder.getCallingUid() != Process.myUid() &&
            context.checkCallingPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("android.permission.DUMP is required")
        }

        val response = try {
            val request = decode(extras?.getString(EXTRA_REQUEST))
            // Binder identity is the shell's; everything below acts as the app.
            val token = Binder.clearCallingIdentity()
            try {
                dispatch(context, method, request)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: AgentException) {
            error(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            error("COMMAND_FAILED", e.message ?: e.javaClass.simpleName)
        }
        val json = response.toString().let {
            if (it.length <= 384 * 1024) it else error("RESPONSE_TOO_LARGE", "Reduce the command output or select fewer fields").toString()
        }
        return Bundle().apply { putString(RESULT_JSON, json) }
    }

    private fun dispatch(context: Context, method: String, request: JSONObject): JSONObject = when (method) {
        "state" -> state(context).put("ok", true)
        "start" -> error("USE_DROIDDECKCTL", "Start sessions with tools/droiddeckctl so Android launches a visible Activity")
        "stop" -> stop(context)
        "resume" -> resume(context)
        "launch" -> launch(context, request)
        "focus" -> guest(context, JSONObject().put("kind", "focus"), 15_000)
        "quit" -> guest(context, JSONObject().put("kind", "quit").put("appId", request.opt("appId"))
            .put("timeout", request.optDouble("timeout", 15.0)), timeoutMs(request, 15.0, 30_000))
        "input" -> AgentInput.handle(request)
        "ui" -> AgentUi.handle(request)
        "guest" -> {
            AgentAccess.requireCommands(context)
            guest(context, JSONObject(request.toString()).put("kind", "exec"), timeoutMs(request, 30.0, 10_000))
        }
        "cdp" -> {
            AgentAccess.requireCommands(context)
            guest(context, JSONObject(request.toString()).put("kind", "cdp"), timeoutMs(request, 15.0, 5_000))
        }
        "env" -> env(context, request)
        "override" -> {
            AgentAccess.requireCommands(context)
            AgentEnv.override(context, request.optString("name")).put("ok", true).put("command", "override")
        }
        "prefs" -> prefs(context, request)
        "access" -> JSONObject().put("ok", true).put("commands", AgentAccess.commandsAllowed(context))
            .put("debugBuild", BuildConfig.DEBUG).put("inbox", AgentEnv.inbox(context).absolutePath)
        else -> error("UNKNOWN_COMMAND", "Unknown agent command '$method'")
    }

    private fun state(context: Context): JSONObject {
        val runtimeVersion = LinuxRuntimeInstaller.installedVersion(context)
        val dir = SessionState.logDirectory ?: SessionState.logFile?.parentFile ?: SessionPaths.sessionFolders(context).lastOrNull()
        val session = JSONObject()
            .put("id", SessionState.sessionId ?: dir?.name ?: JSONObject.NULL)
            .put("phase", SessionState.phase.name)
            .put("running", SessionState.running)
            .put("mode", SessionState.mode)
            .put("program", SessionState.program ?: JSONObject.NULL)
            .put("steamUi", SessionState.steamUi ?: JSONObject.NULL)
            .put("steamUrl", SessionState.steamUrl ?: JSONObject.NULL)
            .put("suspended", SessionState.suspended)
            .put("pip", SessionState.pipActive)
            .put("firstFrame", SessionState.firstFrameSeen)
            .put("output", JSONArray().put(SessionState.outputSize.first).put(SessionState.outputSize.second))
            .put("refreshHz", SessionState.refreshHz.toDouble())
            .put("lastTransitionAt", SessionState.lastTransitionAt)
            .put("guestPid", SessionState.guestPid.takeIf { it > 1 } ?: JSONObject.NULL)
            .put("installing", SessionState.installing ?: JSONObject.NULL)
            .put("logDir", dir?.absolutePath ?: JSONObject.NULL)
            .put("eventsFile", dir?.let { File(it, "events.jsonl").absolutePath } ?: JSONObject.NULL)
            .put("artifactsAvailable", dir?.isDirectory ?: false)
            .put("artifactsComplete", dir?.let { File(it, ".complete").isFile } ?: false)

        val failure = if (SessionState.failureCode == null) JSONObject.NULL else JSONObject()
            .put("code", SessionState.failureCode)
            .put("message", SessionState.failureMessage ?: JSONObject.NULL)
            .put("status", SessionState.failureStatus ?: JSONObject.NULL)
        session.put("failure", failure)

        // gamescope's focus as last published by the guest agent, without the window lists
        // (the focus command returns those, fresh).
        val focus = AgentGuest.focus(context)?.let { snap ->
            JSONObject()
                .put("focusedApp", snap.opt("focusedApp") ?: JSONObject.NULL)
                .put("focusedWindow", snap.opt("focusedWindow") ?: JSONObject.NULL)
                .put("focusableApps", snap.optJSONArray("focusableApps") ?: JSONArray())
                .put("baselayerAppIds", snap.optJSONArray("baselayerAppIds") ?: JSONArray())
                .put("seq", snap.optInt("seq"))
                .put("t", snap.optLong("t"))
        }
        session.put("focus", focus ?: JSONObject.NULL)

        val hello = AgentGuest.hello(context)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("build", BuildConfig.BUILD_LABEL)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("runtime", JSONObject()
                .put("installed", LinuxRuntime.isInstalled(context))
                .put("version", runtimeVersion ?: JSONObject.NULL))
            .put("agent", JSONObject()
                .put("guest", hello != null)
                .put("guestFocus", hello?.optBoolean("focus") ?: false)
                .put("commands", AgentAccess.commandsAllowed(context)))
            .put("session", session)
    }

    private fun stop(context: Context): JSONObject {
        if (SessionState.running) {
            SessionService.stop(context)
            return JSONObject().put("ok", true).put("command", "stop")
        }
        if (SessionState.phase == SessionPhase.STOPPING) {
            return JSONObject().put("ok", true).put("command", "stop")
        }
        if (SessionState.phase in STARTING_PHASES) {
            SessionState.stopRequested = true
            SessionEvents.transition(SessionPhase.STOPPING, "session.stop_requested")
            return JSONObject().put("ok", true).put("command", "stop")
        }
        return error("NO_ACTIVE_SESSION", "There is no session to stop")
    }

    private fun resume(context: Context): JSONObject {
        if (!SessionState.running || !SessionState.suspended) {
            return error("SESSION_NOT_SUSPENDED", "There is no suspended session to resume")
        }
        SessionService.resume(context)
        return JSONObject().put("ok", true).put("command", "resume")
    }

    private fun launch(context: Context, request: JSONObject): JSONObject {
        val appId = request.optString("appId")
        if (!GameLaunchLink.validId(appId)) throw AgentException("INVALID_APP_ID", "appId must be a decimal Steam app id")
        if (!SessionState.running || SessionState.mode != SessionService.MODE_STEAM) {
            throw AgentException("NO_STEAM_SESSION", "Games launch inside a running Steam session")
        }
        if (!SessionService.launchGame(context, appId)) throw AgentException("LAUNCH_REJECTED", "The session did not accept the launch")
        SessionEvents.record("agent.launch_requested", mapOf("appId" to appId))
        return JSONObject().put("ok", true).put("command", "launch").put("appId", appId)
    }

    private fun env(context: Context, request: JSONObject): JSONObject {
        AgentAccess.requireCommands(context)
        val result = when (val op = request.optString("op", "list")) {
            "list" -> AgentEnv.list(context)
            "set" -> {
                val lines = request.optJSONArray("lines") ?: JSONArray()
                AgentEnv.set(context, List(lines.length()) { lines.optString(it) }, request.optBoolean("persistent"))
            }
            "clear" -> AgentEnv.clear(context, request.optString("scope", "next"))
            else -> throw AgentException("INVALID_REQUEST", "env op '$op' is not list, set or clear")
        }
        return result.put("ok", true).put("command", "env")
    }

    private fun prefs(context: Context, request: JSONObject): JSONObject {
        AgentAccess.requireCommands(context)
        val file = request.optString("file")
        val result = when (val op = request.optString("op", "get")) {
            "get" -> AgentPrefs.get(context, file)
            "set" -> AgentPrefs.set(context, file, request.optString("key"), request.optString("type", "string"),
                if (request.isNull("value")) null else request.optString("value"))
            else -> throw AgentException("INVALID_REQUEST", "prefs op '$op' is not get or set")
        }
        return result.put("ok", true).put("command", "prefs")
    }

    /** A guest agent request; its own answer already carries ok or error. */
    private fun guest(context: Context, request: JSONObject, timeoutMs: Long): JSONObject =
        AgentGuest.call(context, request, timeoutMs)

    /** The guest's own timeout plus slack for the round trip. */
    private fun timeoutMs(request: JSONObject, defaultSeconds: Double, slackMs: Long): Long {
        val seconds = if (request.has("timeout")) request.opt("timeout")?.toString()?.toDoubleOrNull()
            ?: throw AgentException("INVALID_REQUEST", "timeout must be a number") else defaultSeconds
        if (!seconds.isFinite() || seconds <= 0 || seconds > 600) {
            throw AgentException("INVALID_REQUEST", "timeout must be a positive number no greater than 600 seconds")
        }
        return (seconds * 1000).toLong() + slackMs
    }

    private fun decode(encoded: String?): JSONObject {
        if (encoded.isNullOrBlank()) return JSONObject()
        if (encoded.length > AgentAccess.MAX_REQUEST_CHARS) throw AgentException("INVALID_REQUEST", "The encoded request exceeds 256 KiB")
        return try {
            JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        } catch (e: Exception) {
            throw AgentException("INVALID_REQUEST", "The request extra is not base64 JSON")
        }
    }

    private fun error(code: String, message: String) = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val RESULT_JSON = "json"
        const val EXTRA_REQUEST = "request"
        /** 2 added agent and session.focus; everything in 1 is unchanged. */
        const val SCHEMA = 2
        private val recoveryComplete = CountDownLatch(1)

        fun awaitRecovery() {
            try {
                if (!recoveryComplete.await(90, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Session artifact recovery did not finish")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while recovering session artifacts", e)
            }
        }

        private val STARTING_PHASES = setOf(
            SessionPhase.PREPARING,
            SessionPhase.INSTALLING_RUNTIME,
            SessionPhase.STARTING_COMPOSITOR,
            SessionPhase.STARTING_GUEST,
            SessionPhase.STARTING_STEAM,
        )
    }
}
