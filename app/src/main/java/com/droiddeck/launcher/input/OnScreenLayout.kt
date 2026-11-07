package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OnScreenLayout {
    data class Dock(val portrait: Boolean, val grips: Boolean)

    private const val TOLERANCE = .01f
    private const val KEYBOARD_ID = "builtin:keyboard"

    fun elements(context: Context, layout: SavedInputLayout?, width: Int, height: Int, safe: Rect,
        buttonsOnly: Boolean = false, dock: Dock? = null): List<InputSurfaceElement> {
        val stored = layout?.elements ?: return emptyList()
        if (width <= 0 || height <= 0) return stored
        val generator = LegacyGamepadLayout(context, width, height, safe)
        if (buttonsOnly && dock == null) return generator.buttonsOnly(stored)
        val (reference, generated) = reference(context, layout, width, height)
        val gamepad = if (generated) stored.filter(::isGamepad) else emptyList()
        val others = fit(stored.filterNot { generated && isGamepad(it) }, reference.first, reference.second, width, height, safe)
        val placed = when {
            gamepad.isEmpty() -> emptyList()
            dock != null -> generator.dock(gamepad, dock.portrait, dock.grips)
            else -> generator.regenerate(gamepad)
        }
        val byId = (placed + others).associateBy { it.id }
        return stored.mapNotNull { byId[it.id] } + placed.filter { p -> stored.none { it.id == p.id } }
    }

    fun fit(elements: List<InputSurfaceElement>, referenceWidth: Float, referenceHeight: Float, width: Int, height: Int, safe: Rect): List<InputSurfaceElement> {
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = min(w / referenceWidth, h / referenceHeight)
        return elements.map { e ->
            val ew = e.width * referenceWidth * scale
            val eh = e.height * referenceHeight * scale
            val left = anchor(e.x, e.width, referenceWidth, w, scale).coerceIn(safe.left.toFloat(), max(safe.left.toFloat(), w - safe.right - ew))
            val top = anchor(e.y, e.height, referenceHeight, h, scale).coerceIn(safe.top.toFloat(), max(safe.top.toFloat(), h - safe.bottom - eh))
            e.copy(x = left / w, y = top / h, width = ew / w, height = eh / h).bounded()
        }
    }

    private fun anchor(start: Float, size: Float, reference: Float, actual: Float, scale: Float): Float {
        val centre = start + size / 2f
        val scaled = size * reference * scale
        return when {
            centre < 1f / 3f -> start * reference * scale
            centre > 2f / 3f -> actual - (1f - start - size) * reference * scale - scaled
            else -> centre * actual - scaled / 2f
        }
    }

    private fun isGamepad(e: InputSurfaceElement) = e.id.startsWith("legacy:") || e.id == KEYBOARD_ID

    private fun reference(context: Context, layout: SavedInputLayout, width: Int, height: Int): Pair<Pair<Float, Float>, Boolean> {
        val (display, metrics) = InputLibraries.primaryDisplay(context)
        val long = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val short = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val gamepad = layout.elements.filter(::isGamepad)
        if (gamepad.any { it.id.startsWith("legacy:") }) {
            val named = layout.id.removePrefix("migrated.layout.").takeIf { it != layout.id }?.split('x')?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 2 && it.all { size -> size > 0 } }?.let { it[0] to it[1] }
            for ((w, h) in listOfNotNull(named, width to height, long to short, short to long).distinct()) {
                if (matches(gamepad, LegacyGamepadLayout(display, w, h).elements())) return (w.toFloat() to h.toFloat()) to true
            }
        }
        val aspect = layout.aspect.takeIf { it > 0f } ?: (long.toFloat() / short)
        return (aspect to 1f) to false
    }

    private fun matches(stored: List<InputSurfaceElement>, generated: List<InputSurfaceElement>): Boolean {
        val byId = generated.associateBy { it.id }
        return stored.all { e ->
            val g = byId[e.id] ?: return@all false
            abs(e.x - g.x) <= TOLERANCE && abs(e.y - g.y) <= TOLERANCE && abs(e.width - g.width) <= TOLERANCE && abs(e.height - g.height) <= TOLERANCE
        }
    }
}
