package com.droiddeck.launcher.frontend

import android.content.Intent
import android.net.Uri
import android.app.Activity
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class GameLaunchLinkTest {
    @Test fun acceptsCanonicalUnsignedDecimalIdsIncludingFullWidthShortcuts() {
        val ids = listOf("1", "17410", "9223372036854775808", "18446744073709551615")
        ids.forEach { id ->
            assertTrue(GameLaunchLink.validId(id))
            assertEquals(id, GameLaunchLink.parse(GameLaunchLink.uri(id)))
            assertEquals("steam://rungameid/$id", GameLaunchLink.steamUrl(id))
        }
    }

    @Test fun rejectsAnythingOtherThanOneCanonicalGamePath() {
        listOf(
            null, "", "droiddeck://game/0", "droiddeck://game/01", "droiddeck://game/+1",
            "droiddeck://game/-1", "droiddeck://game/18446744073709551616",
            "droiddeck://game/1?url=steam://rungameid/2", "droiddeck://game/1/../2",
            "droiddeck://games/1", "droiddeck://game/1/", "DROIDDECK://game/1",
            "droiddeck://game:4/1", "file:///game/1", "steam://rungameid/1",
        ).forEach { assertNull("should reject $it", GameLaunchLink.parse(it)) }
    }

    @Test fun convertsSignedStorageRepresentationToUnsignedShortcutUri() {
        assertEquals("18446744073709551615", Library.SteamGame(1, "Added", null, Library.ADDED, -1L).gameIdString)
        assertEquals("9223372036854775808", Library.SteamGame(1, "Added", null, Library.ADDED, Long.MIN_VALUE).gameIdString)
        assertEquals("droiddeck://game/18446744073709551615", GameLaunchLink.uri(Library.SteamGame(1, "Added", null, Library.ADDED, -1L).gameIdString))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameShortcutIntentTest {
    @Test fun shortcutLaunchIsExplicitAndReturnsThroughTheExistingMainActivity() {
        val context = RuntimeEnvironment.getApplication()
        val id = "18446744073709551615"
        val intent = GameShortcuts.launchIntent(context, id)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("droiddeck://game/$id"), intent.data)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
    }

    @Test fun pinnedShortcutHasStableIdAndCanonicalExplicitLaunchIntent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val game = Library.SteamGame(8400, "Geometry Wars: Retro Evolved", null, "steam", 8400L)

        GameShortcuts.pin(activity, game)

        val shortcut = activity.getSystemService(android.content.pm.ShortcutManager::class.java)
            .pinnedShortcuts.single()
        val launch = requireNotNull(shortcut.intent)
        assertEquals("game:8400", shortcut.id)
        assertEquals("Geometry Wars: Retro Evolved", shortcut.shortLabel.toString())
        assertEquals(MainActivity::class.java.name, shortcut.activity?.className)
        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals(Uri.parse("droiddeck://game/8400"), launch.data)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, launch.flags)
    }

    @Test fun actionCreateShortcutUsesAndroidsShortcutResultIntentApi() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val game = Library.SteamGame(8400, "Geometry Wars: Retro Evolved", null, "steam", 8400L)

        assertNotNull(GameShortcuts.result(activity, game))
    }
}
