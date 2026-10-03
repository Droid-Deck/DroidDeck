package com.droiddeck.launcher.session

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.widget.Toast
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity

/** Owns Android's PiP parameters, while the service continues to own the session. */
class SessionPipController(
    private val activity: SessionActivity,
    private val bounds: () -> Rect,
    private val showPipUi: (Boolean) -> Unit,
) {
    var transitioning = false
        private set
    private var suppressAuto = false
    private var visit = false
    private var lastParams = ""
    val floating get() = transitioning || activity.isInPictureInPictureMode

    fun eligible(automatic: Boolean = false) = supported(activity) && !activity.isFinishing &&
        PipPolicy.eligible(SessionState.running, SessionState.firstFrameSeen, SessionState.phase,
            SessionState.suspended, automatic)

    fun automatic() = !suppressAuto && SessionPrefs.pipAutoEnter(activity) && eligible(true)

    fun refresh(force: Boolean = false) {
        if (!supported(activity)) return
        val rect = bounds()
        val key = "$rect:${SessionState.outputSize}:${automatic()}:${SessionState.suspended}"
        if (!force && key == lastParams) return
        lastParams = key
        runCatching { activity.setPictureInPictureParams(params(rect)) }
    }

    private fun params(rect: Rect): PictureInPictureParams {
        val (w, h) = PipPolicy.aspect(SessionState.outputSize.first, SessionState.outputSize.second)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(w, h))
        if (!rect.isEmpty) builder.setSourceRectHint(rect)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(automatic()).setSeamlessResizeEnabled(false)
        return builder.build()
    }

    fun enter() {
        if (!eligible()) {
            Toast.makeText(activity, R.string.pip_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (!allowed()) {
            Toast.makeText(activity, R.string.pip_permission, Toast.LENGTH_SHORT).show()
            runCatching { activity.startActivity(Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",
                Uri.parse("package:${activity.packageName}"))) }
            return
        }
        prepare()
        val entered = runCatching { activity.enterPictureInPictureMode(params(bounds())) }.getOrDefault(false)
        if (!entered) {
            restore()
            Toast.makeText(activity, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun leaving() {
        if (!automatic() || !allowed()) return
        if (Build.VERSION.SDK_INT < 31) enter() else prepare()
    }

    private fun prepare() {
        if (floating) return
        transitioning = true
        showPipUi(true)
    }

    fun changed(inPip: Boolean) {
        if (inPip) {
            transitioning = false
            visit = true
            SessionState.pipActive = true
            showPipUi(true)
            SessionService.beginPip(activity)
            SessionService.setActivityVisible(activity, true)
        } else restore()
        refresh(true)
    }

    private fun restore() {
        val needsRestore = transitioning || visit
        transitioning = false
        if (visit) {
            SessionState.pipActive = false
            visit = false
        }
        if (needsRestore) showPipUi(false)
    }

    fun resumed() {
        suppressAuto = false
        if (!activity.isInPictureInPictureMode) {
            restore()
            if (SessionState.running) SessionService.setActivityVisible(activity, true)
        }
        refresh(true)
    }

    fun background() { suppressAuto = true; refresh(true) }
    fun destroyed() { if (visit) restore() }

    private fun allowed(): Boolean {
        val ops = activity.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            android.os.Process.myUid(), activity.packageName) == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        fun supported(context: android.content.Context) = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
}
