package com.droiddeck.launcher.stores.ubisoft

import com.droiddeck.launcher.stores.CloudSaves
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal class MemorySlots : UbisoftSlots {
    private var record = JSONObject().put("entityId", "e1").put("name", UbisoftCloud.entityName(77L)).put("revision", 1)
        .put("obj", JSONObject().put("slots", JSONArray()))
    private var clock = 1_700_000_000_000L
    val data = HashMap<String, ByteArray>()
    var uploadsLeft = Int.MAX_VALUE
    var afterUpdate: (JSONArray) -> Unit = {}

    override fun entity() = UbisoftCloud.Entity(JSONObject(record.toString()))

    override fun upload(slot: String, data: ByteArray) {
        if (uploadsLeft-- <= 0) throw java.io.IOException("upload failed")
        this.data[slot] = data
    }

    override fun update(entity: UbisoftCloud.Entity, slots: JSONArray) {
        afterUpdate(slots)
        record = JSONObject(record.toString()).put("revision", record.getInt("revision") + 1).put("obj", JSONObject().put("slots", slots))
    }

    override fun download(slot: String) = data[slot]

    fun store(slot: String, bytes: ByteArray) {
        data[slot] = bytes
        val slots = JSONArray(record.getJSONObject("obj").getJSONArray("slots").toString())
        val entry = (0 until slots.length()).map { slots.getJSONObject(it) }.firstOrNull { it.optString("filename") == slot }
            ?: JSONObject().put("filename", slot).put("id", slot.toInt()).also { slots.put(it) }
        clock += 60_000L
        entry.put("hash", UbisoftCloud.mark(bytes)).put("size", bytes.size).put("lastModified", Instant.ofEpochMilli(clock).toString())
        record = JSONObject(record.toString()).put("revision", record.getInt("revision") + 1).put("obj", JSONObject().put("slots", slots))
    }

    companion object {
        fun save(tag: String): ByteArray = ("UBISAVE0" + CloudSaves.md5(tag.toByteArray()) + tag).toByteArray()
    }
}
