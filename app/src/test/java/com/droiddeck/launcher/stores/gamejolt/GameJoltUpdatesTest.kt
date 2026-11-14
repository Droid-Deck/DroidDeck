package com.droiddeck.launcher.stores.gamejolt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class GameJoltUpdatesTest {
    private fun build(id: String, pkg: String, stamp: String, windows: Boolean = true, name: String = "game.zip") =
        GameJoltBuild(id, pkg, "Main", "1.0", name, 100L, stamp, if (windows) setOf("windows") else setOf("linux"), "", "")

    @Test fun aNewerReleaseOfTheSamePackageIsAnUpdate() {
        val update = GameJoltUpdates.evaluate(listOf(build("20", "1", "200", name = "new.zip")), "10", "1", "100")!!
        assertEquals("20", update.buildId)
        assertEquals("new.zip", update.filename)
        assertFalse(update.replaced)
    }

    @Test fun theInstalledBuildIsNotAnUpdate() {
        assertNull(GameJoltUpdates.evaluate(listOf(build("10", "1", "100")), "10", "1", "100"))
    }

    @Test fun anOlderBuildIsNotAnUpdate() {
        assertNull(GameJoltUpdates.evaluate(listOf(build("5", "1", "50")), "10", "1", "100"))
    }

    @Test fun theSameReleaseTimeWithAHigherBuildIdIsAnUpdate() {
        assertEquals("11", GameJoltUpdates.evaluate(listOf(build("11", "1", "100")), "10", "1", "100")?.buildId)
    }

    @Test fun otherPackagesDoNotCount() {
        assertEquals("30", GameJoltUpdates.evaluate(listOf(build("40", "2", "900"), build("30", "1", "300")), "10", "1", "100")?.buildId)
    }

    @Test fun aRemovedPackageFallsBackToTheNewPreferredBuildAndSaysSo() {
        val update = GameJoltUpdates.evaluate(listOf(build("40", "2", "900")), "10", "1", "100")!!
        assertEquals("40", update.buildId)
        assertTrue(update.replaced)
    }

    @Test fun nonWindowsBuildsAreNeverOffered() {
        assertNull(GameJoltUpdates.evaluate(listOf(build("40", "1", "900", windows = false)), "10", "1", "100"))
    }

    @Test fun nothingToCompareMeansNoUpdate() {
        assertNull(GameJoltUpdates.evaluate(null, "10", "1", "100"))
        assertNull(GameJoltUpdates.evaluate(listOf(build("40", "1", "900")), "", "1", "100"))
    }

    @Test fun foundUpdatesSurviveEncodingAndDropEntriesWithoutABuild() {
        val saved = GameJoltUpdates.encode(mapOf("gamejolt:1" to GameJoltUpdate("20", "200", "new.zip", true)))
        val back = GameJoltUpdates.decode(saved).getValue("gamejolt:1")
        assertEquals("20", back.buildId)
        assertEquals("200", back.stamp)
        assertEquals("new.zip", back.filename)
        assertTrue(back.replaced)
        assertEquals(emptyMap<String, GameJoltUpdate>(), GameJoltUpdates.decode("""{"gamejolt:1":{"stamp":"1"}}"""))
        assertEquals(emptyMap<String, GameJoltUpdate>(), GameJoltUpdates.decode("not json"))
    }
}
