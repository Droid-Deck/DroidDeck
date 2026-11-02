package com.droiddeck.launcher.core

import android.Manifest
import android.app.Activity
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import com.droiddeck.launcher.core.DroidDeckTestRunner as RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35, 37], manifest = Config.NONE)
class AppPermissionsTest {
    @Test fun localNetworkIsUnrestrictedBeforeAndroidSeventeen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        assertEquals(android.os.Build.VERSION.SDK_INT < 37, AppPermissions.localNetwork(activity))
        shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
        assertTrue(AppPermissions.localNetwork(activity))
    }

    @Test fun startupAsksOnlyOnceAndOnlyForApplicablePermissions() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("stores", Context.MODE_PRIVATE).edit().clear().commit()
        activity.getSharedPreferences("runtime-permissions", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(activity).denyPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK, Manifest.permission.POST_NOTIFICATIONS)
        val first = AppPermissions.startup(activity)
        assertEquals(android.os.Build.VERSION.SDK_INT >= 37, Manifest.permission.ACCESS_LOCAL_NETWORK in first)
        assertEquals(android.os.Build.VERSION.SDK_INT >= 33, Manifest.permission.POST_NOTIFICATIONS in first)
        assertTrue(AppPermissions.startup(activity).isEmpty())
    }

    @Test @Config(sdk = [37]) fun grantedPermissionsAreNeverRequested() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(AppPermissions.startup(activity).isEmpty())
    }
}
