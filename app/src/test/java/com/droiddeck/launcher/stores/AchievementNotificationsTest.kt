package com.droiddeck.launcher.stores

import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AchievementNotificationsTest {
    private val unlock = AchievementNotifications.Unlock("123", "first", "First Steps", "", null, "Hollow Valley")
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun reset() { AchievementNotifications.begin("test"); drain() }

    @Test fun duplicatesAndOldSessionEventsDoNotQueue() {
        AchievementNotifications.unlock("old", unlock)
        AchievementNotifications.unlock("test", unlock)
        AchievementNotifications.unlock("test", unlock.copy(name = "Repeated"))
        drain()
        assertEquals(listOf(unlock), AchievementNotifications.pending)
    }

    @Test fun oldTeardownAndDismissCannotRemoveReplacementUnlock() {
        AchievementNotifications.unlock("test", unlock); drain()
        AchievementNotifications.begin("replacement")
        val replacement = unlock.copy(id = "second")
        AchievementNotifications.unlock("replacement", replacement)
        AchievementNotifications.end("test"); drain()
        AchievementNotifications.dismiss(unlock)
        assertEquals(listOf(replacement), AchievementNotifications.pending)
        AchievementNotifications.end("replacement"); drain()
        assertTrue(AchievementNotifications.pending.isEmpty())
    }

    @Test fun burstIsBoundedAndKeepsOrder() {
        repeat(30) { AchievementNotifications.unlock("test", unlock.copy(id = "$it")) }; drain()
        assertEquals((0..15).map { "$it" }, AchievementNotifications.pending.map { it.id })
        AchievementNotifications.dismiss(AchievementNotifications.pending.first())
        assertEquals("1", AchievementNotifications.pending.first().id)
    }

    @Test fun malformedEventsAndNonHttpsIconsAreRejected() {
        assertNull(AchievementNotifications.parse(JSONObject().put("client_id", "123"), ""))
        val event = JSONObject().put("client_id", "123").put("id", "first").put("name", "First Steps").put("icon", "file:///private/icon")
        assertNull(AchievementNotifications.parse(event, "Game")!!.icon)
        event.put("client_id", "null")
        assertNull(AchievementNotifications.parse(event, "Game"))
    }
}
