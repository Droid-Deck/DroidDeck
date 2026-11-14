package com.droiddeck.launcher.session

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

object OpenXrRuntime {
    data class Probe(
        val detected: Boolean = false,
        val androidCreateInstance: Boolean = false,
        val openGles: Boolean = false,
        val extensionCount: Int = 0,
        val error: String = "",
    ) {
        val compatible get() = detected && androidCreateInstance && openGles && error.isEmpty()
    }

    private var cached: Probe? = null
    private var probedAt = 0L
    private var receiver: BroadcastReceiver? = null
    private var sessionGeneration = 0L
    private var sessionState = "unverified"
    private var sessionError = ""
    private var sessionProbe: Probe? = null
    private var revision by mutableIntStateOf(0)

    fun isAvailable(context: Context, refresh: Boolean = false): Boolean {
        revision
        return probe(context, refresh).compatible
    }

    @Synchronized
    internal fun probe(context: Context, refresh: Boolean = false, now: Long = SystemClock.elapsedRealtime(),
                       resolve: () -> Probe = {
                System.loadLibrary("droiddeckxr")
                val result = JSONObject(nativeProbe(context.applicationContext))
                Probe(result.optBoolean("detected"), result.optBoolean("androidCreateInstance"),
                    result.optBoolean("openGles"), result.optInt("extensionCount"), result.optString("error"))
            }): Probe {
        watchPackages(context.applicationContext)
        if (sessionState == "starting" || sessionState == "ready")
            return sessionProbe ?: Probe(error = "XR session startup in progress")
        cached?.let {
            val lifetime = if (it.compatible) 30_000 else 2_000
            if (!refresh && now >= probedAt && now - probedAt < lifetime) return it
        }
        return runCatching(resolve).getOrElse {
            Probe(error = it.message ?: it.javaClass.simpleName)
        }.also {
            cached = it
            probedAt = now
            Log.i("DroidDeckXR", "OpenXR runtime detected=${it.detected} compatible=${it.compatible} error=${it.error}")
        }
    }

    @Synchronized
    fun invalidate() { cached = null; revision++ }

    private fun watchPackages(context: Context) {
        if (receiver != null) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = invalidate()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                context.registerReceiver(listener, filter)
            }
            receiver = listener
        }.onFailure { Log.w("DroidDeckXR", "Runtime package observer unavailable", it) }
    }

    @Synchronized
    fun sessionStarting(): Long {
        sessionProbe = cached?.takeIf { it.compatible }
        sessionState = "starting"
        sessionError = ""
        return ++sessionGeneration
    }

    @Synchronized
    fun sessionReady(owner: Long) {
        if (owner != sessionGeneration) return
        sessionState = "ready"
        sessionError = ""
        sessionProbe = (sessionProbe ?: Probe()).copy(
            detected = true, androidCreateInstance = true, openGles = true, error = "")
    }

    @Synchronized
    fun sessionFailed(owner: Long, error: String) {
        if (owner != sessionGeneration) return
        sessionState = "failed"
        sessionError = error
        sessionProbe = null
    }

    @Synchronized
    fun sessionEnded(owner: Long) {
        if (owner != sessionGeneration) return
        sessionProbe = null
        if (sessionState != "failed") sessionState = "inactive"
        invalidate()
    }

    @Synchronized
    fun diagnostics(context: Context): JSONObject {
        val result = probe(context)
        return JSONObject().put("detected", result.detected).put("graphicsBinding", "opengl_es")
            .put("androidCreateInstance", result.androidCreateInstance).put("openGles", result.openGles)
            .put("compatible", result.compatible).put("extensionCount", result.extensionCount)
            .put("probeError", result.error).put("sessionState", sessionState).put("sessionError", sessionError)
    }

    private external fun nativeProbe(context: Context): String
}
