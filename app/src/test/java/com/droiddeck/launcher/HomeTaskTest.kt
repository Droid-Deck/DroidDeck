package com.droiddeck.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.core.AppUiPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.download.StoreDownloadService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeTaskTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val homeEntry get() = ComponentName(context, HomeActivity::class.java)

    @Before fun noSession() = resetSession()
    @After fun resetSession() {
        SessionState.running = false
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.pipActive = false
        // Component states outlive a test in Robolectric's package manager, so the manifest ships the Home entry switched off.
        context.packageManager.setComponentEnabledSetting(homeEntry, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }

    @Test fun theHomeEntryIsTheFrontEndKeptToOneInstanceInTheHomeTask() {
        val info = context.packageManager.getActivityInfo(homeEntry, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals(context.packageName + ".home", info.taskAffinity)
        assertEquals(0, info.flags and ActivityInfo.FLAG_NO_HISTORY)
        assertTrue(FrontEndActivity::class.java.isAssignableFrom(HomeActivity::class.java))
    }

    @Test fun plainAppLaunchesKeepTheirOwnTask() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, FrontEndActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, info.launchMode)
        assertEquals(context.packageName, info.taskAffinity)
    }

    @Test fun theHomeIntentReachesTheHomeFrontEndOnceTheEntryIsOn() {
        makeHome()
        val resolved = context.packageManager.resolveActivity(home(), PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(HomeActivity::class.java.name, resolved?.activityInfo?.name)
        assertTrue(HomeRoute.isHomeApp(context))
    }

    @Test fun homeOpensTheChosenHomePage() {
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_APPS)
        val route = HomeRoute.resolve(context, home())
        assertNull(route.guide)
        assertNull(route.frontEnd.action)
        assertEquals(AppUiPrefs.HOME_PAGE_APPS, route.frontEnd.getStringExtra(StoreDownloadService.EXTRA_NAV))
    }

    @Test fun homeOnTheSteamPageRequestsNoPage() {
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_STEAM)
        val route = HomeRoute.resolve(context, home())
        assertNull(route.guide)
        assertNull(route.frontEnd.action)
        assertFalse(route.frontEnd.hasExtra(StoreDownloadService.EXTRA_NAV))
    }

    @Test fun homeDuringASteamSessionOpensTheGuideAndLeavesThePageAlone() {
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_APPS)
        SessionState.running = true
        val route = HomeRoute.resolve(context, home())
        val guide = route.guide!!
        assertEquals(SessionActivity::class.java.name, guide.component?.className)
        assertEquals(SessionService.ACTION_HOME_GUIDE, guide.action)
        assertTrue(guide.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(HomeRoute.ACTION_SESSION_HOME, route.frontEnd.action)
        assertFalse(route.frontEnd.hasExtra(StoreDownloadService.EXTRA_NAV))
    }

    @Test fun homeWithTheSessionInPictureInPictureShowsTheFrontEnd() {
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_APPS)
        SessionState.running = true
        SessionState.pipActive = true
        val route = HomeRoute.resolve(context, home())
        assertNull(route.guide)
        assertEquals(AppUiPrefs.HOME_PAGE_APPS, route.frontEnd.getStringExtra(StoreDownloadService.EXTRA_NAV))
    }

    @Test fun aForwardedLaunchIsAHomeIntentThatKeepsItsRequest() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("droiddeck://game/42"), context, FrontEndActivity::class.java)
        val forward = HomeRoute.forward(context, link)
        assertNull(forward.component)
        assertEquals(setOf(Intent.CATEGORY_HOME), forward.categories)
        assertTrue(HomeRoute.isHomeIntent(forward))
        val route = HomeRoute.resolve(context, forward)
        assertNull(route.guide)
        assertEquals(Intent.ACTION_VIEW, route.frontEnd.action)
        assertEquals(link.data, route.frontEnd.data)
    }

    @Test fun theAppIconOnlyBringsTheHomeFrontEndForward() {
        val icon = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, "com.droiddeck.launcher.MainActivity"))
        val route = HomeRoute.resolve(context, HomeRoute.forward(context, icon))
        assertNull(route.frontEnd.action)
        assertFalse(route.frontEnd.hasCategory(Intent.CATEGORY_LAUNCHER))
    }

    @Test fun theShortcutPickerIsNeverForwarded() {
        assertFalse(HomeRoute.forwards(Intent(Intent.ACTION_CREATE_SHORTCUT)))
        assertTrue(HomeRoute.forwards(Intent(context, FrontEndActivity::class.java)))
    }

    @Test fun aPlainFrontEndHandsItsLaunchToTheHomeTaskWhileDroidDeckIsHome() {
        makeHome()
        val notification = Intent(context, FrontEndActivity::class.java).putExtra(StoreDownloadService.EXTRA_NAV, "downloads")
        val activity = Robolectric.buildActivity(FrontEndActivity::class.java, notification).get()
        assertTrue(handOff(activity))
        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(HomeRoute.isHomeIntent(started))
        assertEquals("downloads", HomeRoute.resolve(context, started).frontEnd.getStringExtra(StoreDownloadService.EXTRA_NAV))
    }

    @Test fun aForwardingFrontEndFinishesWithoutSettingUp() {
        makeHome()
        val controller = Robolectric.buildActivity(FrontEndActivity::class.java, Intent(context, FrontEndActivity::class.java)).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertTrue(HomeRoute.isHomeIntent(shadowOf(activity).nextStartedActivity))
        controller.destroy()
    }

    @Test fun aPlainFrontEndStaysPutWhenDroidDeckIsNotHome() {
        val activity = Robolectric.buildActivity(FrontEndActivity::class.java, Intent(context, FrontEndActivity::class.java)).get()
        assertFalse(handOff(activity))
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun theHomeFrontEndRoutesHomeWhenCreated() {
        makeHome()
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_APPS)
        val activity = Robolectric.buildActivity(HomeActivity::class.java, home()).get()
        assertFalse(handOff(activity))
        assertFalse(activity.isFinishing)
        assertNull(activity.intent.action)
        assertEquals(AppUiPrefs.HOME_PAGE_APPS, activity.intent.getStringExtra(StoreDownloadService.EXTRA_NAV))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun theHomeFrontEndRoutesEachLaterHomePress() {
        makeHome()
        val controller = Robolectric.buildActivity(HomeActivity::class.java, home()).create()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        nav(activity, null)
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_APPS)
        controller.newIntent(home())
        assertEquals(AppUiPrefs.HOME_PAGE_APPS, nav(activity))
        assertNull(activity.intent.action)
    }

    @Test fun homeDuringASteamSessionReachesTheGuideFromTheHomeFrontEnd() {
        makeHome()
        val controller = Robolectric.buildActivity(HomeActivity::class.java, home()).create()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        while (shadowOf(activity).nextStartedActivity != null) Unit
        SessionState.running = true
        controller.newIntent(home())
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(SessionService.ACTION_HOME_GUIDE, started.action)
        assertEquals(HomeRoute.ACTION_SESSION_HOME, activity.intent.action)
    }

    @Test fun theHomeEntryOpensTheFrontEndTaskAsBeforeWhenDroidDeckIsNotHome() {
        AppUiPrefs.setHomePage(context, AppUiPrefs.HOME_PAGE_STEAM)
        val activity = Robolectric.buildActivity(HomeActivity::class.java, home()).get()
        // Robolectric switches on the component of an activity it builds; DroidDeck's Home entry is off here.
        context.packageManager.setComponentEnabledSetting(homeEntry, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertTrue(handOff(activity))
        assertTrue(activity.isFinishing)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(FrontEndActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private fun home() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun makeHome() {
        context.packageManager.setComponentEnabledSetting(homeEntry, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }

    private fun handOff(activity: FrontEndActivity): Boolean =
        FrontEndActivity::class.java.getDeclaredMethod("handOff", Bundle::class.java)
            .apply { isAccessible = true }.invoke(activity, null) as Boolean

    @Suppress("UNCHECKED_CAST")
    private fun navState(activity: FrontEndActivity) =
        FrontEndActivity::class.java.getDeclaredField("navRequest\$delegate")
            .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.MutableState<String?>

    private fun nav(activity: FrontEndActivity): String? = navState(activity).value
    private fun nav(activity: FrontEndActivity, value: String?) { navState(activity).value = value }
}
