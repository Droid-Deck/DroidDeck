package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FpsLimitScopeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun allGamesAppliesWhereNoGameHasItsOwn() {
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_ALL, null, 30)
        assertEquals(30, SessionPrefs.savedFpsLimit(ctx, null))
        assertEquals(30, SessionPrefs.savedFpsLimit(ctx, "123"))
        assertEquals(SessionPrefs.SCOPE_ALL, SessionPrefs.fpsLimitScope(ctx, "123"))
    }

    @Test fun aGameLimitOverridesAllGamesForThatGameOnly() {
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_ALL, null, 30)
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_GAME, "123", 45)
        assertEquals(45, SessionPrefs.savedFpsLimit(ctx, "123"))
        assertEquals(30, SessionPrefs.savedFpsLimit(ctx, "456"))
    }

    @Test fun thisSessionDropsOnlyTheGameValueAndKeepsAllGames() {
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_ALL, null, 30)
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_GAME, "123", 45)
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_SESSION, "123", 60)
        assertEquals(30, SessionPrefs.savedFpsLimit(ctx, "123"))
        SessionPrefs.saveFpsLimit(ctx, SessionPrefs.SCOPE_SESSION, null, 60)
        assertEquals(30, SessionPrefs.savedFpsLimit(ctx, null))
    }
}
