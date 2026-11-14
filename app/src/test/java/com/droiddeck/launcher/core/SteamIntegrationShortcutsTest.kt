package com.droiddeck.launcher.core

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.input.HomeButtonService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SteamIntegrationShortcutsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val service = ComponentName(context, HomeButtonService::class.java)

    private fun listed() = context.packageManager.queryIntentServices(
        Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName), 0,
    ).any { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == service }

    private fun choose(value: String?) =
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)

    @Test fun theShortcutsServiceIsHiddenUntilChosen() {
        assertTrue(listed())
        assertFalse(SteamIntegration.shortcutsChosen(context))
        SteamIntegration.syncShortcutsListing(context)
        assertFalse(listed())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(service))
    }

    @Test fun anUpdateKeepsAServiceTheUserAlreadyTurnedOn() {
        choose(service.flattenToString())
        SteamIntegration.syncShortcutsListing(context)
        assertTrue(listed())
        assertTrue(SteamIntegration.shortcutsChosen(context))
    }

    @Test fun openingShortcutSetupListsTheServiceAndBackingOutHidesItAgain() {
        SteamIntegration.setShortcutsListed(context, true)
        assertTrue(listed())
        SteamIntegration.syncShortcutsListing(context)
        assertFalse(listed())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(service))
    }

    @Test fun aChosenServiceStaysListed() {
        choose("other.app/.Reader:" + service.flattenToShortString())
        assertTrue(SteamIntegration.shortcutsChosen(context))
        SteamIntegration.syncShortcutsListing(context)
        assertTrue(listed())
        choose(service.flattenToString())
        assertTrue(SteamIntegration.shortcutsChosen(context))
        choose("other.app/.Reader")
        assertFalse(SteamIntegration.shortcutsChosen(context))
        SteamIntegration.syncShortcutsListing(context)
        assertFalse(listed())
    }

    @Test fun anUpdateHidesAnUnchosenServiceWithoutOpeningDroidDeck() {
        val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName)
        assertTrue(context.packageManager.queryBroadcastReceivers(replaced, 0)
            .any { it.activityInfo.name == ShortcutsListingReceiver::class.java.name })
        assertTrue(listed())
        ShortcutsListingReceiver().onReceive(context, replaced)
        assertFalse(listed())
    }

    @Test fun anUpdateLeavesAChosenServiceListed() {
        choose(service.flattenToString())
        ShortcutsListingReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(listed())
        assertTrue(SteamIntegration.shortcutsChosen(context))
    }

    @Test fun otherBroadcastsChangeNothing() {
        ShortcutsListingReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(listed())
    }

    @Test fun turningTheServiceOffInSettingsHidesItAtOnce() {
        choose(service.flattenToString())
        val running = org.robolectric.Robolectric.buildService(HomeButtonService::class.java).create().get()
        running.onUnbind(null)
        assertTrue(listed())
        choose("")
        running.onUnbind(null)
        assertFalse(listed())
    }
}
