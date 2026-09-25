package com.droiddeck.launcher.agent

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
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionArtifacts
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A shell-only control surface, installed only by debug builds. */
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
            when (method) {
                "state" -> state(context).put("ok", true)
                "start" -> error("USE_DROIDDECKCTL", "Start sessions with tools/droiddeckctl so Android launches a visible Activity")
                "stop" -> stop(context)
                "resume" -> resume(context)
                else -> error("UNKNOWN_COMMAND", "Unknown agent command '$method'")
            }
        } catch (e: Exception) {
            error("COMMAND_FAILED", e.message ?: e.javaClass.simpleName)
        }
        return Bundle().apply { putString(RESULT_JSON, response.toString()) }
    }

    private fun state(context: Context): JSONObject {
        val runtimeVersion = LinuxRuntimeInstaller.installedVersion(context)
        val dir = SessionState.logDirectory ?: SessionState.logFile?.parentFile ?: latestSessionDirectory()
        val session = JSONObject()
            .put("id", SessionState.sessionId ?: dir?.name ?: JSONObject.NULL)
            .put("phase", SessionState.phase.name)
            .put("running", SessionState.running)
            .put("mode", SessionState.mode)
            .put("program", SessionState.program ?: JSONObject.NULL)
            .put("steamUi", SessionState.steamUi ?: JSONObject.NULL)
            .put("steamUrl", SessionState.steamUrl ?: JSONObject.NULL)
            .put("suspended", SessionState.suspended)
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

        return JSONObject()
            .put("schema", 1)
            .put("build", BuildConfig.BUILD_LABEL)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("runtime", JSONObject()
                .put("installed", LinuxRuntime.isInstalled(context))
                .put("version", runtimeVersion ?: JSONObject.NULL))
            .put("session", session)
    }

    private fun latestSessionDirectory(): File? {
        val parent = LinuxRuntime.debugLogDir()
        return parent.listFiles { file -> file.isDirectory && file.name.startsWith("session-") }
            ?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
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
