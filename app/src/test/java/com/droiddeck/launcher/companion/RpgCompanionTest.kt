package com.droiddeck.launcher.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RpgCompanionTest {
    @Test
    fun pipBoyStatsFavoritesAndRadio() {
        val data = """{"type":"data","id":"droiddeck","data":{
            "player.stats":{"level":23,"hp":245,"hpMax":310,"ap":88,"apMax":105,"rads":65,"caps":1834,"weight":187.4,"weightMax":260},
            "player.inventory":[
              {"id":"0x101","name":"Old Laser Rifle","type":"weapon","count":1,"equipped":true},
              {"id":"0x102","name":"Service Pistol","type":"weapon","count":1,"equipped":false,"fav":1},
              {"id":"0x236","name":"Med Kit","type":"aid","count":7,"equipped":false,"fav":0}],
            "radio":{"enabled":true}}}"""
        val s = RpgCompanion.reduce(RpgCompanion.FALLOUT_4, RpgCompanion.State(), data)
        assertEquals(23, s.level)
        assertEquals(listOf("hp", "ap"), s.bars.map { it.key })
        assertEquals(310f, s.bars[0].max)
        assertEquals(listOf("Med Kit", "Service Pistol"), s.slots.map { it.label })
        assertEquals("×7", s.slots[0].detail)
        assertEquals(true, s.radio)
        assertEquals("1834", s.facts.toMap()["caps"])

        val toggle = JSONObject(RpgCompanion.trigger(RpgCompanion.FALLOUT_4, s.slots[1], 3))
        assertEquals("toggle", toggle.getString("command"))
        assertEquals("0x102", toggle.getJSONObject("params").getString("id"))
        assertEquals(false, JSONObject(RpgCompanion.radio(false, 4)).getJSONObject("params").getBoolean("enable"))
    }

    @Test
    fun skyrimVitalsAndHotkeys() {
        val data = """{"type":"data","id":"droiddeck","ts":1,"fields":{
            "health":320.5,"health.max":400,"magicka":180,"magicka.max":200,"stamina":99.25,"stamina.max":150,
            "level":31,"weight":210.2,"carry":300,
            "hotkeys":[{"slot":1,"bound":true,"kind":"spell","name":"Flames","school":"Destruction"},{"slot":2,"bound":false},
                       {"slot":3,"bound":true,"kind":"item","name":"Iron Sword","categoryType":"Weapon"}]}}"""
        val s = RpgCompanion.reduce(RpgCompanion.SKYRIM_SE, RpgCompanion.State(), data)
        assertEquals(listOf("health", "magicka", "stamina"), s.bars.map { it.key })
        assertEquals(31, s.level)
        assertEquals(listOf("1", "3"), s.slots.map { it.key })
        assertEquals("Destruction", s.slots[0].detail)
        assertEquals("210 / 300", s.facts.toMap()["weight"])
        assertNull(s.radio)

        val partial = RpgCompanion.reduce(RpgCompanion.SKYRIM_SE, s, """{"type":"data","id":"droiddeck","fields":{"level":32}}""")
        assertEquals(32, partial.level)
        assertEquals(3, partial.bars.size)

        val hotkey = JSONObject(RpgCompanion.trigger(RpgCompanion.SKYRIM_SE, s.slots[1], 9))
        assertEquals("hotkey_trigger", hotkey.getString("command"))
        assertEquals(3, hotkey.getInt("slot"))
    }

    @Test
    fun subscriptionsAndForeignMessages() {
        val skyrim = JSONObject(RpgCompanion.subscribe(RpgCompanion.SKYRIM_SE))
        assertEquals("Hotkey::Items", skyrim.getJSONObject("fields").getString("hotkeys"))
        assertTrue(skyrim.getJSONObject("settings").getBoolean("sendOnChange"))
        assertEquals("player.stats", JSONObject(RpgCompanion.subscribe(RpgCompanion.FALLOUT_4)).getJSONArray("fields").getString(0))
        val s = RpgCompanion.State(level = 5)
        assertEquals(s, RpgCompanion.reduce(RpgCompanion.FALLOUT_4, s, """{"type":"hello","server":"ThorPipBoy"}"""))
        assertEquals(s, RpgCompanion.reduce(RpgCompanion.FALLOUT_4, s, """{"type":"data","id":"other","data":{}}"""))
        assertEquals(8767, RpgCompanion.port(RpgCompanion.FALLOUT_4))
        assertEquals(8765, RpgCompanion.port(RpgCompanion.SKYRIM_SE))
    }
}
