package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.snapshots.Snapshot
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean

fun createRobolectricComposeRule(): ComposeContentTestRule {
    val compose = createComposeRule()
    RingVeil.reset()
    val context = RuntimeEnvironment.getApplication()
    SessionPrefs.setAnimationsEnabled(context, false)
    Motion.refresh(context)
    shadowOf(context.packageManager).addActivityIfNotPresent(
        ComponentName(context, ComponentActivity::class.java),
    )
    Snapshot.sendApplyNotifications()
    return object : ComposeContentTestRule by compose {
        override fun apply(base: Statement, description: Description): Statement {
            val test = compose.apply(base, description)
            return object : Statement() {
                override fun evaluate() {
                    // Robolectric clears the main queue between tests; scope snapshot delivery to each test.
                    val handler = Handler(Looper.getMainLooper())
                    val pending = AtomicBoolean(false)
                    val notify = Runnable {
                        pending.set(false)
                        Snapshot.sendApplyNotifications()
                    }
                    val observer = Snapshot.registerGlobalWriteObserver {
                        if (pending.compareAndSet(false, true)) handler.post(notify)
                    }
                    try {
                        test.evaluate()
                    } finally {
                        observer.dispose()
                        handler.removeCallbacks(notify)
                        Snapshot.sendApplyNotifications()
                    }
                }
            }
        }
    }
}
