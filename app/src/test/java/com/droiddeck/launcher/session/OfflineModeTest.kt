package com.droiddeck.launcher.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineModeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root get() = LinuxRuntime.rootDir(context)
    private val vdf get() = File(root, "root/.local/share/Steam/config/loginusers.vdf")

    @Before
    fun setUp() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        vdf.parentFile!!.mkdirs()
        writeSteam("0")
        network(online = true)
    }

    private fun writeSteam(wants: String) {
        vdf.writeText(
            "\"users\"\n{\n\t\"7656\"\n\t{\n\t\t\"AccountName\"\t\t\"player\"\n" +
                "\t\t\"RememberPassword\"\t\t\"1\"\n\t\t\"WantsOfflineMode\"\t\t\"$wants\"\n" +
                "\t\t\"SkipOfflineModeWarning\"\t\t\"$wants\"\n\t}\n}\n"
        )
    }

    private fun steamWants(): String =
        Regex(""""WantsOfflineMode"\s*"([01])"""").find(vdf.readText())!!.groupValues[1]

    private fun network(online: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        if (online) {
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun offlineWhenChosenOrWithoutInternet() {
        assertFalse(OfflineMode.offline(manual = false, online = true))
        assertTrue(OfflineMode.offline(manual = false, online = false))
        assertTrue(OfflineMode.offline(manual = true, online = true))
        assertTrue(OfflineMode.offline(manual = true, online = false))
    }

    @Test
    fun steamsOwnChoiceWinsOverTheSavedSetting() {
        assertTrue(OfflineMode.manual(steamWants = true, automatic = false, saved = false))
        assertFalse(OfflineMode.manual(steamWants = false, automatic = false, saved = true))
        assertFalse(OfflineMode.manual(steamWants = true, automatic = true, saved = false))
        assertTrue(OfflineMode.manual(steamWants = null, automatic = false, saved = true))
    }

    @Test
    fun startsOfflineWithoutInternetAndOnlineOnceItReturns() {
        network(online = false)
        assertTrue(OfflineMode.apply(context, root))
        assertEquals("1", steamWants())
        assertFalse(OfflineMode.enabled(context))

        network(online = true)
        assertFalse(OfflineMode.apply(context, root))
        assertEquals("0", steamWants())
        assertFalse(OfflineMode.enabled(context))
    }

    @Test
    fun manualOfflineStaysOnWithInternet() {
        OfflineMode.setEnabled(context, true)
        assertEquals("1", steamWants())
        assertTrue(OfflineMode.apply(context, root))
        assertEquals("1", steamWants())
        assertTrue(OfflineMode.enabled(context))

        OfflineMode.setEnabled(context, false)
        assertEquals("0", steamWants())
        assertFalse(OfflineMode.enabled(context))
    }

    @Test
    fun goingOfflineInsideSteamShowsInDroidDeckAndIsKept() {
        writeSteam("1")
        assertTrue(OfflineMode.enabled(context))
        assertTrue(OfflineMode.apply(context, root))
        assertEquals("1", steamWants())
    }

    @Test
    fun goingOnlineInsideSteamAfterAnAutomaticOfflineStartIsKept() {
        network(online = false)
        OfflineMode.apply(context, root)
        writeSteam("0")
        network(online = true)
        assertFalse(OfflineMode.apply(context, root))
        assertFalse(OfflineMode.enabled(context))
        assertEquals("0", steamWants())
    }

    @Test
    fun turningTheToggleOffWithoutInternetStillStartsOfflineButNotAsAChoice() {
        OfflineMode.setEnabled(context, true)
        network(online = false)
        OfflineMode.setEnabled(context, false)
        assertEquals("1", steamWants())
        assertFalse(OfflineMode.enabled(context))
        network(online = true)
        assertFalse(OfflineMode.apply(context, root))
        assertEquals("0", steamWants())
    }

    @Test
    fun steamGoingOfflineAfterANetworkDropIsNotTakenAsAChoice() {
        assertFalse(OfflineMode.apply(context, root))
        network(online = false)
        OfflineMode.follow(context)
        writeSteam("1")
        assertFalse(OfflineMode.enabled(context))
        network(online = true)
        OfflineMode.follow(context)
        writeSteam("0")
        assertFalse(OfflineMode.enabled(context))
        assertFalse(OfflineMode.apply(context, root))
    }

    @Test
    fun chosenOfflineSurvivesANetworkDropAndReturn() {
        OfflineMode.setEnabled(context, true)
        network(online = false)
        OfflineMode.follow(context)
        network(online = true)
        OfflineMode.follow(context)
        assertTrue(OfflineMode.enabled(context))
        assertTrue(OfflineMode.apply(context, root))
        assertEquals("1", steamWants())
    }
}
