package com.droiddeck.launcher.xr

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the reference gloves from the user's SteamVR installation; Valve assets are not bundled. */
internal object SteamVrHandVisuals {
    fun model(steam: File, right: Boolean): ByteArray? {
        val side = if (right) "right" else "left"
        val file = File(steam, "steamapps/common/SteamVR/resources/rendermodels/vr_glove/vr_glove_${side}_model_slim.glb")
        if (!file.isFile) return null
        require(file.length() in 20..8_388_608)
        return decode(file.readBytes(), right)
    }

    fun visible(state: File, proc: File = File("/proc")): Boolean = runCatching {
        if (!state.isFile || state.length() > 4096) return@runCatching false
        val focus = JSONObject(state.readText())
        val server = focus.getInt("serverPid")
        if (server <= 0 || File(proc, "$server/comm").readText().trim() != "vrserver") return@runCatching false
        if (focus.getBoolean("dashboard")) return@runCatching true
        val scene = focus.getInt("scenePid")
        scene == 0 || (scene > 0 && File(proc, "$scene/comm").readText().trim() == "vrstartup")
    }.getOrDefault(false)

    internal fun decode(bytes: ByteArray, right: Boolean): ByteArray {
        require(bytes.size in 20..8_388_608)
        val glb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(glb.int == 0x46546c67 && glb.int == 2 && glb.int == bytes.size)
        val jsonBytes = glb.int
        require(glb.int == 0x4e4f534a && jsonBytes in 2..1_048_576 && jsonBytes <= glb.remaining() - 8)
        val text = ByteArray(jsonBytes); glb.get(text)
        val json = JSONObject(text.toString(Charsets.UTF_8))
        val binaryBytes = glb.int
        require(glb.int == 0x004e4942 && binaryBytes == glb.remaining())
        val binary = glb.slice().order(ByteOrder.LITTLE_ENDIAN)
        val buffers = json.getJSONArray("buffers")
        require(buffers.length() == 1 && !buffers.getJSONObject(0).has("uri"))
        require(buffers.getJSONObject(0).getInt("byteLength") <= binaryBytes)
        val skins = json.getJSONArray("skins"); require(skins.length() == 1)
        val skin = skins.getJSONObject(0)
        val jointNodes = skin.getJSONArray("joints"); require(jointNodes.length() == 31)
        val suffix = if (right) "r" else "l"
        val names = mutableListOf("Root", "wrist_$suffix")
        names += listOf("finger_thumb_0_$suffix", "finger_thumb_1_$suffix", "finger_thumb_2_$suffix", "finger_thumb_${suffix}_end")
        for (finger in listOf("index", "middle", "ring", "pinky"))
            names += listOf("finger_${finger}_meta_$suffix", "finger_${finger}_0_$suffix", "finger_${finger}_1_$suffix", "finger_${finger}_2_$suffix", "finger_${finger}_${suffix}_end")
        names += listOf("thumb", "index", "middle", "ring", "pinky").map { "finger_${it}_${suffix}_aux" }
        val nodes = json.getJSONArray("nodes")
        val remap = IntArray(31) { i -> names.indexOf(nodes.getJSONObject(jointNodes.getInt(i)).getString("name")) }
        require(remap.toSet() == (0..30).toSet())
        val meshes = json.getJSONArray("meshes"); require(meshes.length() == 1)
        val primitives = meshes.getJSONObject(0).getJSONArray("primitives"); require(primitives.length() == 1)
        val primitive = primitives.getJSONObject(0)
        require(primitive.optInt("mode", 4) == 4 && !primitive.has("targets"))
        val attributes = primitive.getJSONObject("attributes")
        val accessors = json.getJSONArray("accessors")
        val views = json.getJSONArray("bufferViews")
        data class Accessor(val count: Int, val width: Int, val kind: Int, val stride: Int, val offset: Int)
        fun accessor(index: Int, type: String): Accessor {
            val a = accessors.getJSONObject(index)
            require(a.getString("type") == type && !a.has("sparse") && !a.optBoolean("normalized"))
            val width = when (type) { "SCALAR" -> 1; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("Unsupported attribute") }
            val kind = a.getInt("componentType")
            val size = when (kind) { 5121 -> 1; 5123 -> 2; 5125, 5126 -> 4; else -> error("Unsupported component") }
            val view = views.getJSONObject(a.getInt("bufferView"))
            require(view.getInt("buffer") == 0)
            val start = view.optInt("byteOffset", 0); val length = view.getInt("byteLength")
            val relative = a.optInt("byteOffset", 0); val count = a.getInt("count")
            val stride = view.optInt("byteStride", width * size)
            require(start >= 0 && length >= 0 && start.toLong() + length <= binaryBytes)
            require(relative >= 0 && count in 1..100_000 && stride in width * size..256 && stride % size == 0)
            require(relative.toLong() + (count - 1L) * stride + width * size <= length)
            return Accessor(count, width, kind, stride, start + relative)
        }
        fun scalar(a: Accessor, item: Int, component: Int): Float {
            val size = when (a.kind) { 5121 -> 1; 5123 -> 2; else -> 4 }
            val offset = a.offset + item * a.stride + component * size
            val value = when (a.kind) {
                5121 -> (binary.get(offset).toInt() and 255).toFloat()
                5123 -> (binary.getShort(offset).toInt() and 65535).toFloat()
                5125 -> { val v = binary.getInt(offset); require(v >= 0); v.toFloat() }
                else -> binary.getFloat(offset)
            }
            require(value.isFinite())
            return value
        }
        val positions = accessor(attributes.getInt("POSITION"), "VEC3")
        val normals = accessor(attributes.getInt("NORMAL"), "VEC3")
        val weights = accessor(attributes.getInt("WEIGHTS_0"), "VEC4")
        val joints = accessor(attributes.getInt("JOINTS_0"), "VEC4")
        require(listOf(positions, normals, weights).all { it.kind == 5126 })
        require(joints.kind in listOf(5121, 5123))
        require(listOf(normals, weights, joints).all { it.count == positions.count })
        val indices = accessor(primitive.getInt("indices"), "SCALAR")
        require(indices.kind in listOf(5121, 5123, 5125) && indices.count % 3 == 0)
        val bind = accessor(skin.getInt("inverseBindMatrices"), "MAT4")
        require(bind.kind == 5126 && bind.count == 31)
        val out = ByteBuffer.allocate(16 + positions.count * 56 + indices.count * 4 + 31 * 64).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x31484d44).putInt(positions.count).putInt(indices.count).putInt(31)
        for (v in 0 until positions.count) {
            for (a in listOf(positions, normals)) for (c in 0..2) out.putFloat(scalar(a, v, c))
            val total = (0..3).sumOf { scalar(weights, v, it).toDouble() }.toFloat()
            require(total > 0 && (0..3).all { scalar(weights, v, it) >= 0 })
            for (c in 0..3) out.putFloat(scalar(weights, v, c) / total)
            for (c in 0..3) { val joint = scalar(joints, v, c).toInt(); require(joint in 0..30); out.putFloat(remap[joint].toFloat()) }
        }
        for (i in 0 until indices.count) { val index = scalar(indices, i, 0).toInt(); require(index in 0 until positions.count); out.putInt(index) }
        val inverseRemap = IntArray(31); for (i in 0..30) inverseRemap[remap[i]] = i
        for (bone in 0..30) for (c in 0..15) out.putFloat(scalar(bind, inverseRemap[bone], c))
        return out.array()
    }
}
