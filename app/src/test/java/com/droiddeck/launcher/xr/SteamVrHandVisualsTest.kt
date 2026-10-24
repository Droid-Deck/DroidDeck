package com.droiddeck.launcher.xr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SteamVrHandVisualsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(edit: (JSONObject) -> Unit = {}): ByteArray {
        val names = mutableListOf("Root", "wrist_l", "finger_thumb_0_l", "finger_thumb_1_l", "finger_thumb_2_l", "finger_thumb_l_end")
        for (f in listOf("index", "middle", "ring", "pinky"))
            names += listOf("finger_${f}_meta_l", "finger_${f}_0_l", "finger_${f}_1_l", "finger_${f}_2_l", "finger_${f}_l_end")
        names += listOf("thumb", "index", "middle", "ring", "pinky").map { "finger_${it}_l_aux" }
        val nodes = JSONArray(names.map { mapOf("name" to it) })
        val views = JSONArray(); val accessors = JSONArray()
        val bytes = ByteBuffer.allocate(2124).order(ByteOrder.LITTLE_ENDIAN)
        fun accessor(offset: Int, size: Int, count: Int, type: String, kind: Int): Int {
            val index = views.length()
            views.put(JSONObject().put("buffer", 0).put("byteOffset", offset).put("byteLength", size))
            accessors.put(JSONObject().put("bufferView", index).put("componentType", kind).put("type", type).put("count", count))
            return index
        }
        for (v in 0..2) { bytes.putFloat(v.toFloat()).putFloat(0f).putFloat(-1f) }
        for (v in 0..2) { bytes.putFloat(0f).putFloat(1f).putFloat(0f) }
        for (v in 0..2) { bytes.putFloat(2f).putFloat(0f).putFloat(0f).putFloat(0f) }
        repeat(3) { bytes.put(byteArrayOf(1,0,0,0)) }
        bytes.putShort(0).putShort(1).putShort(2).putShort(0)
        for (bone in 0..30) for (i in 0..15) bytes.putFloat(if (i % 5 == 0) 1f else 0f)
        val attributes = JSONObject()
            .put("POSITION", accessor(0,36,3,"VEC3",5126)).put("NORMAL", accessor(36,36,3,"VEC3",5126))
            .put("WEIGHTS_0", accessor(72,48,3,"VEC4",5126)).put("JOINTS_0", accessor(120,12,3,"VEC4",5121))
        val indices = accessor(132,6,3,"SCALAR",5123)
        val bind = accessor(140,1984,31,"MAT4",5126)
        val json = JSONObject().put("buffers", JSONArray().put(JSONObject().put("byteLength", bytes.capacity())))
            .put("nodes", nodes).put("bufferViews", views).put("accessors", accessors)
            .put("skins", JSONArray().put(JSONObject().put("joints", JSONArray((0..30).toList())).put("inverseBindMatrices", bind)))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(JSONObject().put("attributes", attributes).put("indices", indices)))))
        edit(json)
        var text = json.toString(); while (text.toByteArray().size % 4 != 0) text += " "
        val encoded = text.toByteArray()
        return ByteBuffer.allocate(28+encoded.size+bytes.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546c67).putInt(2).putInt(28+encoded.size+bytes.capacity())
            .putInt(encoded.size).putInt(0x4e4f534a).put(encoded)
            .putInt(bytes.capacity()).putInt(0x004e4942).put(bytes.array()).array()
    }

    @Test fun preservesGeometryAndNormalizesSkinWeights() {
        val data = ByteBuffer.wrap(SteamVrHandVisuals.decode(fixture(), false)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x31484d44, data.int); assertEquals(3, data.int); assertEquals(3, data.int); assertEquals(31, data.int)
        assertEquals(-1f, data.getFloat(24), 0f)
        assertEquals(1f, data.getFloat(40), 0f)
        assertEquals(1f, data.getFloat(56), 0f)
        assertEquals(2, data.getInt(16+3*56+2*4))
    }
    @Test fun rejectsTruncatedOrWrongHandAssets() {
        assertThrows(IllegalArgumentException::class.java) { SteamVrHandVisuals.decode(fixture().dropLast(4).toByteArray(), false) }
        assertThrows(IllegalArgumentException::class.java) { SteamVrHandVisuals.decode(fixture(), true) }
    }
    @Test fun rejectsOutOfBoundsAccessorsAndExternalBuffers() {
        assertThrows(IllegalArgumentException::class.java) {
            SteamVrHandVisuals.decode(fixture { it.getJSONArray("accessors").getJSONObject(0).put("byteOffset", Int.MAX_VALUE) }, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SteamVrHandVisuals.decode(fixture { it.getJSONArray("buffers").getJSONObject(0).put("uri", "external.bin") }, false)
        }
    }
    @Test fun localHandsAppearOnlyInLiveSteamVrMenuOrBackground() {
        val proc = temporary.newFolder("proc")
        File(proc, "1").mkdir(); File(proc, "1/comm").writeText("vrserver\n")
        File(proc, "2").mkdir(); File(proc, "2/comm").writeText("Game.exe\n")
        val state = temporary.newFile("scene-focus.json")
        fun focus(pid: Int, dashboard: Boolean) { state.writeText("""{"serverPid":1,"scenePid":$pid,"dashboard":$dashboard}""") }
        focus(2,true); assertTrue(SteamVrHandVisuals.visible(state,proc))
        focus(2,false); assertFalse(SteamVrHandVisuals.visible(state,proc))
        focus(0,false); assertTrue(SteamVrHandVisuals.visible(state,proc))
        File(proc,"2/comm").writeText("vrstartup\n"); focus(2,false); assertTrue(SteamVrHandVisuals.visible(state,proc))
        File(proc,"1/comm").writeText("unrelated\n"); assertFalse(SteamVrHandVisuals.visible(state,proc))
        state.writeText("{"); assertFalse(SteamVrHandVisuals.visible(state,proc))
    }
}
