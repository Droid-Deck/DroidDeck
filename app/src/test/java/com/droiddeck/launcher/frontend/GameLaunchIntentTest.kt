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
            assertEquals("620", GameLaunchIntent.read(RuntimeEnvironment.getApplication(), request))
            assertEquals("8400", GameLaunchIntent.readId(Intent(action).putExtra("app_id", "8400")))
            assertEquals("620", GameLaunchIntent.readId(request.putExtra("game_source", "steam")))
        }
    }

    @Test fun rejectsUnsupportedSourcesTypesAndIdsWithoutFallingBackToData() {
        listOf("", "0", "01", "+1", "-1", "2147483648", "18446744073709551615", "620\n", "steam://rungameid/620").forEach { id ->
            assertNull(id, GameLaunchIntent.readId(Intent(GameLaunchIntent.ACTION).putExtra("app_id", id)))
        }
        listOf("EPIC", "GOG", "CUSTOM_GAME", "", "STEAM ").forEach { source ->
            assertNull(GameLaunchIntent.readId(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 620).putExtra("game_source", source)))
        }
        assertNull(GameLaunchIntent.readId(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 620L)))
        assertNull(GameLaunchIntent.readId(Intent(GameLaunchIntent.ACTION).putExtra("app_id", -1)))
        assertNull(GameLaunchIntent.readId(Intent(GameLaunchIntent.ACTION).putExtra("app_id", 620).putExtra("game_source", 1)))
        assertNull(GameLaunchIntent.readId(Intent(Intent.ACTION_MAIN).putExtra("app_id", 620)))
        assertNull(GameLaunchIntent.read(RuntimeEnvironment.getApplication(), Intent(GameLaunchIntent.ACTION,
            android.net.Uri.parse("droiddeck://game/620"))))
    }

    @Test fun legacyLinksAndPlainIdFilesUseTheSameReader() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("18446744073709551615", GameLaunchIntent.read(context,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("droiddeck://game/18446744073709551615"))))
        assertFalse(GameLaunchIntent.accepts(Intent.ACTION_SEND))
        assertTrue(GameLaunchIntent.accepts(GameLaunchIntent.ACTION))
    }
}
