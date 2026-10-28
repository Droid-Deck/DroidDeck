package com.droiddeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.update.AppUpdateState
import com.droiddeck.launcher.update.resetUpdateTask
import com.droiddeck.launcher.update.AppUpdates
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdatesMenuLifecycleTest {
    @Before fun resetTask() = resetUpdateTask()

    @Test fun backgroundCompletionSurvivesMenuRecreationWithoutStartingInstallation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val handler = Handler(Looper.getMainLooper())
        val first = UpdatesMenu(activity, handler)
        first.start()
        AppUpdateState.begin(AppUpdates.Apk("update.apk", "invalid://update", 1, "0".repeat(64), activity.packageName, 1, "0".repeat(64)))
        first.onResume()
        first.onPause()
        AppUpdateState.progress(45)
        assertEquals(45, first.state().percent)
        first.unregister()
        AppUpdateState.ready(activity, File(activity.cacheDir, "update.apk"))
        val second = UpdatesMenu(activity, handler)
        second.start()
        assertTrue(second.state().ready)
        assertEquals(AppUpdateState.Phase.READY, AppUpdateState.snapshot.phase)
        assertTrue(activity.packageManager.packageInstaller.mySessions.isEmpty())
        second.unregister()
        controller.pause().stop().destroy()
    }
}
