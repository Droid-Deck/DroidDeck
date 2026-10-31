package com.droiddeck.launcher.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InputSurfaceElement(val control: InputControl, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun bounded() = copy(x = x.coerceIn(0f, 0.9f), y = y.coerceIn(0f, 0.9f),
        width = width.coerceIn(0.1f, 1f - x.coerceIn(0f, 0.9f)), height = height.coerceIn(0.1f, 1f - y.coerceIn(0f, 0.9f)))
}

object InputSurfaceLayout {
    private fun prefs(context: Context) = context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE)

    fun resetAll(context: Context) { prefs(context).edit().clear().apply() }

    fun defaults() = listOf(
        InputSurfaceElement(InputControl.LEFT_PAD, 0.02f, 0.02f, 0.46f, 0.65f),
        InputSurfaceElement(InputControl.RIGHT_PAD, 0.52f, 0.02f, 0.46f, 0.65f),
        InputSurfaceElement(InputControl.A, 0.02f, 0.72f, 0.20f, 0.25f),
        InputSurfaceElement(InputControl.B, 0.26f, 0.72f, 0.20f, 0.25f),
        InputSurfaceElement(InputControl.SELECT, 0.52f, 0.72f, 0.20f, 0.25f),
        InputSurfaceElement(InputControl.START, 0.76f, 0.72f, 0.22f, 0.25f),
    )

    fun read(context: Context, profile: String): List<InputSurfaceElement> {
        val text = prefs(context).getString(profile, null) ?: return defaults()
        return runCatching {
            val doc = JSONObject(text)
            require(doc.getInt("version") == 1)
            val elements = doc.getJSONArray("elements")
            (0 until minOf(elements.length(), 28)).mapNotNull { i ->
                val item = elements.optJSONObject(i) ?: return@mapNotNull null
                val control = InputControl.fromId(item.optString("control")) ?: return@mapNotNull null
                val values = listOf("x", "y", "w", "h").map { item.optDouble(it, Double.NaN).toFloat() }
                if (values.any { !it.isFinite() }) return@mapNotNull null
                InputSurfaceElement(control, values[0], values[1], values[2], values[3]).bounded()
            }.distinctBy { it.control }
        }.getOrElse { defaults() }
    }

    fun save(context: Context, profile: String, layout: List<InputSurfaceElement>) {
        val array = JSONArray()
        for (element in layout.distinctBy { it.control }.take(28)) {
            val e = element.bounded()
            array.put(JSONObject().put("control", e.control.id).put("x", e.x.toDouble()).put("y", e.y.toDouble())
                .put("w", e.width.toDouble()).put("h", e.height.toDouble()))
        }
        prefs(context).edit().putString(profile, JSONObject().put("version", 1).put("elements", array).toString()).apply()
    }
}
