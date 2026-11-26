package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamAchievementsTest {
    @Test
    fun parsesTheClientsAchievementList() {
        val json = """{"result":1,"data":{"rgAchievements":[
            {"strID":"ACH_WIN","strName":"Winner","strDescription":"Win once","bAchieved":true,"rtUnlocked":1764000000,
             "strImage":"https://cdn.example/apps/620/abc.jpg","bHidden":false},
            {"strID":"ACH_SECRET","strName":"Secret","strDescription":"","bAchieved":false,"rtUnlocked":1764000000,
             "strImage":"def.jpg","bHidden":true}]}}"""
        val list = (SteamAchievements.parse(620, json) as StoreAchievements.Result.Found).list
        assertEquals(2, list.size)
        assertEquals("Winner", list[0].name)
        assertTrue(list[0].unlocked)
        assertEquals(1764000000L, list[0].unlockedAt)
        assertEquals("https://cdn.example/apps/620/abc.jpg", list[0].icon)
        assertTrue(list[1].hidden)
        assertEquals(0L, list[1].unlockedAt)
        assertEquals("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/620/def.jpg", list[1].icon)
    }

    @Test
    fun aFailedResultIsUnreachable() {
        assertTrue(SteamAchievements.parse(620, """{"result":2}""") is StoreAchievements.Result.Failed)
        assertTrue(SteamAchievements.parse(620, "nope") is StoreAchievements.Result.Failed)
    }

    @Test
    fun noListIsNoAchievements() {
        assertEquals(0, (SteamAchievements.parse(620, """{"result":1,"data":{}}""") as StoreAchievements.Result.Found).list.size)
    }
}
