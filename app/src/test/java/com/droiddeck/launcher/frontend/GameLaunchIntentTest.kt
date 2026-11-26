package com.droiddeck.launcher.frontend

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameLaunchIntentTest {
    @Test fun acceptsNativeAndExplicitlyRetargetedGameNativeProfiles() {
        listOf(GameLaunchIntent.ACTION, "app.gamenative.LAUNCH_GAME").forEach { action ->
            val request = Intent(action).putExtra("app_id", 620).putExtra("game_source", "STEAM")
            assertEquals("620", GameLaunchIntent.read(RuntimeEnvironment.getApplication(), request)?.id)
            assertEquals("8400", GameLaunchIntent.readExtras(Intent(action).putExtra("app_id", "8400"))?.id)
            assertEquals("620", GameLaunchIntent.readExtras(request.putExtra("game_source", "steam"))?.id)
        }
    }

    @Test fun aLinkIsReplayedOnlyWhenANewProcessRestoresItOrRecentsReopensIt() {
        val link = Intent(Intent.ACTION_VIEW)
        assertFalse(GameLaunchIntent.replayed(link, restored = false, savedProcess = null, process = "b"))
        assertFalse(GameLaunchIntent.replayed(link, restored = true, savedProcess = "b", process = "b"))
        assertTrue(GameLaunchIntent.replayed(link, restored = true, savedProcess = "a", process = "b"))
        assertTrue(GameLaunchIntent.replayed(link, restored = true, savedProcess = null, process = "b"))
        val fromRecents = Intent(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertTrue(GameLaunchIntent.replayed(fromRecents, restored = false, savedProcess = null, process = "b"))
        assertTrue(GameLaunchIntent.replayed(fromRecents, restored = true, savedProcess = "b", process = "b"))
        val settings = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("droiddeck://settings/storage"))
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertFalse(GameLaunchIntent.replayed(settings, restored = true, savedProcess = "a", process = "b"))
        assertTrue(GameLaunchIntent.replayed(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("droiddeck://game/620")),
            restored = true, savedProcess = "a", process = "b"))
    }

    @Test fun longAndStringIdsPreserveUnsignedShortcutIds() {
        listOf("2147483648", "18446744073709551615").forEach { id ->
            assertEquals(id, GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", id))?.id)
        }
        assertEquals("620", GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 620L))?.id)
        assertEquals("18446744073709551615", GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", -1L))?.id)
    }

    @Test fun storeSourcesComeThroughForLibraryMatching() {
        mapOf("EPIC" to "epic", "gog" to "gog", "AMAZON" to "amazon", "XBOX" to "xbox", "UBISOFT" to "ubisoft",
            "EA" to "ea", "ROCKSTAR" to "rockstar", "BATTLENET" to "battlenet", "ITCH" to "itch", "GAMEJOLT" to "gamejolt",
            "CUSTOM_GAME" to Library.ADDED).forEach { (name, source) ->
            val request = GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 1207658924).putExtra("game_source", name))
            assertEquals(name, source, request?.source)
            assertEquals("1207658924", request?.id)
        }
    }

    @Test fun rejectsUnsupportedSourcesTypesAndIdsWithoutFallingBackToData() {
        listOf("", "0", "01", "+1", "-1", "18446744073709551616", "620\n", "steam://rungameid/620").forEach { id ->
            assertNull(id, GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", id)))
        }
        listOf("STADIA", "", "STEAM ", "added").forEach { source ->
            assertNull(GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 620).putExtra("game_source", source)))
        }
        assertNull(GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 0L)))
        assertNull(GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", -1)))
        assertNull(GameLaunchIntent.readExtras(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 620).putExtra("game_source", 1)))
        assertNull(GameLaunchIntent.readExtras(Intent(Intent.ACTION_MAIN).putExtra("app_id", 620)))
        assertNull(GameLaunchIntent.read(RuntimeEnvironment.getApplication(), Intent(GameLaunchIntent.ACTION,
            android.net.Uri.parse("droiddeck://game/620"))))
    }

    @Test fun deepLinksStillAcceptUnsignedShortcutIds() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("18446744073709551615", GameLaunchIntent.read(context,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("droiddeck://game/18446744073709551615")))?.id)
        assertFalse(GameLaunchIntent.accepts(Intent.ACTION_SEND))
        assertTrue(GameLaunchIntent.accepts(GameLaunchIntent.ACTION))
    }
}
