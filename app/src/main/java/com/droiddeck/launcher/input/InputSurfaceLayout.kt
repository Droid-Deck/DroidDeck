package com.droiddeck.launcher.input

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class InputElementKind(val control: InputControl) {
    BUTTON(InputControl.A), STICK(InputControl.LS), TRACKPAD(InputControl.LEFT_PAD),
    DPAD(InputControl.LS), TRIGGER(InputControl.LT);

    companion object {
        fun from(control: InputControl) = when {
            control.isTrackpad -> TRACKPAD
            control.dimensions == 2 -> STICK
            control == InputControl.LT || control == InputControl.RT -> TRIGGER
            else -> BUTTON
        }
    }
}

enum class InputButtonShape { ROUNDED, PILL, CIRCLE, RECTANGLE }

data class InputSurfaceElement(
    val control: InputControl, val x: Float, val y: Float, val width: Float, val height: Float,
    val id: String = "legacy:${control.id}",
    val kind: InputElementKind = InputElementKind.from(control),
    val name: String = control.label,
    val floating: Boolean = true,
    val hideWhenIdle: Boolean = false,
    val mouseGestures: Boolean = false,
    val toggle: Boolean = false,
    val icon: Int = 0,
    val image: String = "",
    val opacity: Float = 1f,
    val buttonShape: InputButtonShape = InputButtonShape.ROUNDED,
    val showLabel: Boolean = true,
    val alwaysShown: Boolean = false,
) {
    val hasClick get() = kind == InputElementKind.STICK || kind == InputElementKind.TRACKPAD

    fun bounded() = copy(x = x.coerceIn(0f, 0.97f), y = y.coerceIn(0f, 0.97f),
        width = width.coerceIn(0.03f, (1f - x.coerceIn(0f, 0.97f)).coerceAtLeast(0.03f)), height = height.coerceIn(0.03f, (1f - y.coerceIn(0f, 0.97f)).coerceAtLeast(0.03f)))
}

internal val QUICK_BUTTONS = listOf(InputCommand.STEAM, InputCommand.QUICK_ACCESS, InputCommand.KEYBOARD)

val InputSurfaceElement.quickButton: InputCommand? get() = when {
    id == "legacy:${InputControl.GUIDE.id}" -> InputCommand.STEAM
    id == "legacy:${InputControl.QAM.id}" -> InputCommand.QUICK_ACCESS
    id == "builtin:keyboard" -> InputCommand.KEYBOARD
    else -> null
}

internal fun List<InputSurfaceElement>.scaled(factor: Float): List<InputSurfaceElement> = map { e ->
    fun anchor(centre: Float) = when { centre < 1f / 3f -> 0f; centre > 2f / 3f -> 1f; else -> .5f }
    val w = (e.width * factor).coerceIn(.03f, 1f)
    val h = (e.height * factor).coerceIn(.03f, 1f)
    val cx = e.x + e.width / 2
    val cy = e.y + e.height / 2
    val ax = anchor(cx)
    val ay = anchor(cy)
    e.copy(x = (ax + (cx - ax) * factor - w / 2).coerceIn(0f, 1f - w), y = (ay + (cy - ay) * factor - h / 2).coerceIn(0f, 1f - h),
        width = w, height = h).bounded()
}

internal fun List<InputSurfaceElement>.withPositionsOf(defaults: List<InputSurfaceElement>): List<InputSurfaceElement> {
    val byId = defaults.associateBy { it.id }
    return map { e -> byId[e.id]?.let { d -> e.copy(x = d.x, y = d.y, width = d.width, height = d.height).bounded() } ?: e }
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

    fun read(context: Context, profile: String, role: InputRole = InputRole.SECONDARY): List<InputSurfaceElement> =
        InputLibraries.read(context).layout(profile, role)?.elements.orEmpty()

    internal fun hasLegacy(context: Context, profile: String) = prefs(context).contains(profile)

    internal fun readLegacy(context: Context, profile: String): List<InputSurfaceElement> {
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
        InputLibraries.update(context) { library ->
            val saved = library.layout(profile, InputRole.SECONDARY) ?: return@update library
            library.withLayout(saved.copy(elements = layout.map { it.bounded() }))
        }
    }

    internal fun saveLegacy(context: Context, profile: String, layout: List<InputSurfaceElement>) {
        val array = JSONArray()
        for (element in layout.distinctBy { it.control }.take(28)) {
            val e = element.bounded()
            array.put(JSONObject().put("control", e.control.id).put("x", e.x.toDouble()).put("y", e.y.toDouble())
                .put("w", e.width.toDouble()).put("h", e.height.toDouble()))
        }
        prefs(context).edit().putString(profile, JSONObject().put("version", 1).put("elements", array).toString()).apply()
    }
}
