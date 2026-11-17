package com.droiddeck.launcher.session

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager

// While a session runs, sits invisibly on top of every other display until it ends, so
// that display's home launcher is not its top activity. lmkd kills the launcher first; restarted as a
// top activity it takes focus from the session, which then drops to adj 50 and is killed next.
class KeepTopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (intent.getBooleanExtra(EXTRA_BOUNCE, false)) {
            bounces++
            finish()
            return
        }
        if (intent.getIntExtra(EXTRA_GENERATION, -1) != generation) {
            finishAndRemoveTask()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val id = display?.displayId ?: Display.INVALID_DISPLAY
        starting.remove(id)
        if (guards[id]?.isFinishing == false) {
            finishAndRemoveTask()
            return
        }
        guards[id] = this
    }

    override fun onDestroy() {
        guards.values.remove(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepTop"
        private const val EXTRA_BOUNCE = "bounce"
        private const val EXTRA_GENERATION = "generation"
        private var generation = 0
        var bounces = 0
            private set
        private val guards = HashMap<Int, KeepTopActivity>()
        private const val START_TIMEOUT_MS = 2_000L
        private val starting = HashMap<Int, Long>()

        fun guardOtherDisplays(context: Context, sessionDisplay: Int): Boolean {
            val displays = context.getSystemService(DisplayManager::class.java)?.displays ?: return false
            var started = false
            for (display in displays) {
                val id = display.displayId
                if (id == sessionDisplay || display.state != Display.STATE_ON || guards[id]?.isFinishing == false ||
                    starting[id]?.let { SystemClock.elapsedRealtime() - it < START_TIMEOUT_MS } == true) continue
                runCatching {
                    launch(context, id, bounce = false)
                    starting[id] = SystemClock.elapsedRealtime()
                    started = true
                    Log.i(TAG, "guarding display $id from a home launcher restart")
                }.onFailure { Log.w(TAG, "could not guard display $id", it) }
            }
            if (!started) return false
            return runCatching { launch(context, sessionDisplay, bounce = true) }
                .onFailure { Log.w(TAG, "could not return focus to display $sessionDisplay", it) }.isSuccess
        }

        fun hasPendingStarts() = starting.isNotEmpty()

        const val RETRY_MS = START_TIMEOUT_MS + 250

        fun release() {
            generation++
            guards.values.toList().forEach { it.finishAndRemoveTask() }
            guards.clear()
            starting.clear()
        }

        private fun launch(context: Context, displayId: Int, bounce: Boolean) {
            val intent = Intent(context, KeepTopActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_BOUNCE, bounce)
                .putExtra(EXTRA_GENERATION, generation)
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }
    }
}
