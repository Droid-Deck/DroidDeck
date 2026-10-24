package com.droiddeck.launcher.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.PhantomProcessStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w960dp-h600dp-land", application = Application::class)
class PhantomProcessGatePageTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun wirelessDebuggingIsAvailableWithoutOpeningOtherWaysOnNewerRoms() {
        val context: Context = ApplicationProvider.getApplicationContext()
        var wirelessRequests = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val uiContext = LocalContext.current
            remember(uiContext) {
                val connectivity = uiContext.getSystemService(ConnectivityManager::class.java)
                shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                    NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
                ))
                val capabilities = NetworkCapabilities()
                shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
                shadowOf(uiContext.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
                true
            }
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost {
                        PhantomProcessGatePage(
                            status = PhantomProcessStatus.UNREADABLE,
                            onDismiss = {}, onOpenDeveloperOptions = {},
                            onFixWithWirelessDebugging = { wirelessRequests++ },
                            onEnterAddressManually = {}, onCopyCommand = {}, onOpenNotificationSettings = {},
                        )
                    }
                }
            }
        }
        val wireless = compose.onNodeWithText(context.getString(R.string.gate_use_wireless_instead))
        wireless.performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        wireless.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, wirelessRequests) }
    }
}
