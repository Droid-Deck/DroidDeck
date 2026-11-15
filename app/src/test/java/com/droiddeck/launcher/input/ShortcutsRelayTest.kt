package com.droiddeck.launcher.input

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.core.SteamIntegration
import com.droiddeck.shortcuts.Relay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ShortcutsRelayTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val companionUid = 10_999

    private fun choose(value: String?) =
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)

    private fun installCompanion() {
        val app = ApplicationInfo().apply { packageName = Relay.PACKAGE; uid = companionUid }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = Relay.PACKAGE; applicationInfo = app })
        shadowOf(context.packageManager).setPackagesForUid(companionUid, Relay.PACKAGE)
    }

    private fun config(binder: IBinder, uid: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Relay.DESCRIPTOR)
            ShadowBinder.setCallingUid(uid)
            if (!binder.transact(Relay.CONFIG, data, reply, 0)) return null
            reply.setDataPosition(0)
            return reply.readInt()
        } finally {
            ShadowBinder.reset()
            data.recycle()
            reply.recycle()
        }
    }

    @Test fun droidDeckDeclaresNoAccessibilityService() {
        val services = context.packageManager.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName), 0)
        assertTrue(services.isEmpty())
    }

    @Test fun theCompanionFindsTheRelay() {
        val relays = context.packageManager.queryIntentServices(Intent(Relay.ACTION).setPackage(context.packageName), 0)
        assertEquals(listOf(ShortcutsRelay::class.java.name), relays.map { it.serviceInfo.name })
    }

    @Test fun shortcutsAreOnOnlyWhileTheCompanionServiceIsEnabled() {
        assertFalse(SteamIntegration.shortcutsEnabled(context))
        choose("other.app/.Reader:${Relay.PACKAGE}/${Relay.SERVICE}")
        assertTrue(SteamIntegration.shortcutsEnabled(context))
        choose("${Relay.PACKAGE}/.ShortcutsService")
        assertTrue(SteamIntegration.shortcutsEnabled(context))
        choose("other.app/.Reader")
        assertFalse(SteamIntegration.shortcutsEnabled(context))
    }

    @Test fun theRelayAnswersOnlyTheEnabledCompanion() {
        installCompanion()
        val binder = Robolectric.buildService(ShortcutsRelay::class.java).create().get().onBind(Intent(Relay.ACTION))
        assertNull(config(binder, companionUid))
        choose("${Relay.PACKAGE}/${Relay.SERVICE}")
        assertNull(config(binder, 10_998))
        assertEquals(0, config(binder, companionUid))
    }

    @Test fun windowSnapshotsSurviveTheTrip() {
        val sent = mapOf(2 to listOf(Relay.Window(7, 1, 3, Rect(0, 0, 1080, 1240), "guest.app"), Relay.Window(8, 2, 1, Rect(1, 2, 3, 4), null)))
        val got = Relay.decode(Relay.encode(sent))
        assertEquals(setOf(2), got.keys)
        val windows = got.getValue(2)
        assertEquals(listOf(7, 8), windows.map { it.id })
        assertEquals(listOf(1, 2), windows.map { it.type })
        assertEquals(listOf(3, 1), windows.map { it.layer })
        assertEquals(listOf(Rect(0, 0, 1080, 1240), Rect(1, 2, 3, 4)), windows.map { it.bounds })
        assertEquals(listOf("guest.app", null), windows.map { it.pkg })
    }
}
