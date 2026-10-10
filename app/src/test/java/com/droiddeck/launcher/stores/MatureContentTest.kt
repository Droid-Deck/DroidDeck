package com.droiddeck.launcher.stores

import com.droiddeck.launcher.stores.gog.GogStoreCatalog
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mature means what the store's own ratings or tags say; a title without them stays visible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MatureContentTest {
    private fun gog(ratings: String, tags: String) = JSONObject("""{"id":"1","title":"T","ratings":[$ratings],"tags":[$tags]}""")

    @Test fun gogRatingsAndTagsDecide() {
        assertTrue(GogStoreCatalog.isMature(gog("""{"name":"esrbRating","ageRating":"17"}""", "")))
        assertTrue(GogStoreCatalog.isMature(gog("""{"name":"gogRating","ageRating":"18"}""", "")))
        assertTrue(GogStoreCatalog.isMature(gog("""{"name":"pegiRating","ageRating":"12"}""", """{"name":"NSFW","slug":"nsfw"}""")))
        assertFalse(GogStoreCatalog.isMature(gog("""{"name":"esrbRating","ageRating":"13"},{"name":"uskRating","ageRating":"16"}""", """{"name":"Puzzle","slug":"puzzle"}""")))
        // No rating data at all: visible.
        assertFalse(GogStoreCatalog.isMature(JSONObject("""{"id":"2","title":"U"}""")))
    }

    @Test fun tagNamesFromAnyStore() {
        assertTrue(matureByTags(listOf("Action", "Sexual Content")))
        assertFalse(matureByTags(listOf("Action", "FPS", "RELAXING")))
    }
}
