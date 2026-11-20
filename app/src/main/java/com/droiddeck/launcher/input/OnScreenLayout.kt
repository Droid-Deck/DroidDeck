package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

object OnScreenLayout {
    data class Dock(val portrait: Boolean, val grips: Boolean, val left: InputControl = InputControl.LS, val right: InputControl = InputControl.RS) {
        companion object {
            fun deck(portrait: Boolean, gamepad: SavedInputLayout?): Dock {
                fun side(right: Boolean) = gamepad?.elements?.firstOrNull {
                    (it.kind == InputElementKind.STICK || it.kind == InputElementKind.TRACKPAD) && (it.x + it.width / 2f >= .5f) == right
                }?.kind
                val left = if (side(false) == InputElementKind.TRACKPAD) InputControl.LEFT_PAD else InputControl.LS
                val right = if (side(true) == InputElementKind.STICK) InputControl.RS else InputControl.RIGHT_PAD
                return Dock(portrait, true, left, right)
            }
        }
    }

    /**
     * The built-in gamepad keeps no kit positions until its kit is edited; until then the kit follows the virtual controller and
     * the layout stores only the keys and controls the player added on top.
     */
    fun generated(layout: SavedInputLayout) = hasBuiltInKit(layout) && layout.elements.none { KitLayout.isKit(it.id) }

    fun hasBuiltInKit(layout: SavedInputLayout) =
        !layout.kitless && (layout.id == InputLibraries.OVERLAY || layout.origin == InputLibraries.OVERLAY)

    fun elements(context: Context, layout: SavedInputLayout?, width: Int, height: Int, safe: Rect, dock: Dock? = null,
        cutouts: List<Rect> = emptyList(), rearButtons: Boolean = true, trackpads: Boolean = true): List<InputSurfaceElement> {
        layout ?: return emptyList()
        if (width <= 0 || height <= 0) return layout.elements
        if (generated(layout)) {
            val kit = PadKit.current(context)
            val extras = if (layout.elements.isEmpty()) emptyList() else fit(layout.elements, aspect(context, layout), 1f, width, height, safe)
            return (if (dock != null) KitLayout.dock(context, kit, width, height, dock.portrait, dock.left, dock.right)
                else KitLayout.elements(context, kit, width, height, safe, layout.scale, cutouts, rearButtons, trackpads, addedControls = extras)) + extras
        }
        val elements = if (dock == null && hasBuiltInKit(layout)) layout.elements.filter { element ->
            !KitLayout.isKit(element.id) || KitLayout.visibleOnOverlay(element.control, rearButtons, trackpads)
        } else layout.elements
        return fit(elements, aspect(context, layout), 1f, width, height, safe)
    }

    internal const val STEAM_SHORTCUT_ID = "shortcut:steam"
    internal const val QAM_SHORTCUT_ID = "shortcut:qam"

    internal fun steamShortcuts(width: Int, height: Int, safe: Rect, density: Float,
        pinned: List<InputSurfaceElement> = emptyList()): List<InputSurfaceElement> {
        if (width <= 0 || height <= 0) return emptyList()
        val size = min(48f * density, (width - safe.left - safe.right - 64f * density) / 2f)
        if (size <= 0f || height - safe.bottom - safe.top < size) return emptyList()
        val gap = 4f * density
        val left = safe.left + 56f * density
        val right = width - safe.right - size - gap
        val middle = (left + right) / 2f
        val pinnedFromBottom = pinned.sortedByDescending { it.y }
        fun button(control: InputControl, id: String, leftSide: Boolean): InputSurfaceElement? {
            val edge = if (leftSide) left else right
            val range = if (leftSide) left..(middle - size / 2f) else (middle + size / 2f)..right
            val candidates = listOf(edge) + pinned.map {
                if (leftSide) (it.x + it.width) * width + gap else it.x * width - size - gap
            }.filter { it in range }.sortedBy { kotlin.math.abs(it - edge) }
            for (x in candidates) {
                var y = max(safe.top.toFloat(), height - safe.bottom - size - gap)
                for (element in pinnedFromBottom) {
                    if (x >= (element.x + element.width) * width || x + size <= element.x * width) continue
                    if (y >= (element.y + element.height) * height || y + size <= element.y * height) continue
                    y = element.y * height - size - gap
                }
                if (y >= safe.top) return InputSurfaceElement(control, x / width, y / height, size / width, size / height,
                    id, buttonShape = InputButtonShape.PILL).bounded()
            }
            return null
        }
        return listOfNotNull(
            button(InputControl.GUIDE, STEAM_SHORTCUT_ID, leftSide = true),
            button(InputControl.QAM, QAM_SHORTCUT_ID, leftSide = false),
        )
    }

    fun editorElements(context: Context, layout: SavedInputLayout, width: Int, height: Int, edited: Boolean = false): List<InputSurfaceElement> =
        elements(context, if (edited && width > 0 && height > 0) layout.copy(aspect = width.toFloat() / height) else layout, width, height, Rect())

    fun editorFrame(layout: SavedInputLayout, displayed: List<InputSurfaceElement>): List<InputSurfaceElement> {
        val byId = displayed.associateBy { it.id }
        return layout.elements.map { byId[it.id] ?: it }
    }

    fun fit(elements: List<InputSurfaceElement>, referenceWidth: Float, referenceHeight: Float, width: Int, height: Int, safe: Rect): List<InputSurfaceElement> {
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = min(w / referenceWidth, h / referenceHeight)
        // A layout shown on a screen of the other orientation keeps its shape: spread by thirds, its clusters would come apart.
        val turned = (w > h) != (referenceWidth > referenceHeight)
        val blockX = turned && w > referenceWidth * scale * BLOCK_STRETCH
        val blockY = turned && h > referenceHeight * scale * BLOCK_STRETCH
        return elements.map { e ->
            val ew = e.width * referenceWidth * scale
            val eh = e.height * referenceHeight * scale
            val x = if (blockX) (w + safe.left - safe.right) / 2f + (e.x - .5f) * referenceWidth * scale else anchor(e.x, e.width, referenceWidth, w, scale)
            val y = if (blockY) h - safe.bottom - (1f - e.y) * referenceHeight * scale else anchor(e.y, e.height, referenceHeight, h, scale)
            val left = x.coerceIn(safe.left.toFloat(), max(safe.left.toFloat(), w - safe.right - ew))
            val top = y.coerceIn(safe.top.toFloat(), max(safe.top.toFloat(), h - safe.bottom - eh))
            e.copy(x = left / w, y = top / h, width = ew / w, height = eh / h).bounded()
        }
    }

    private const val BLOCK_STRETCH = 1.5f

    private fun anchor(start: Float, size: Float, reference: Float, actual: Float, scale: Float): Float {
        val centre = start + size / 2f
        val scaled = size * reference * scale
        return when {
            centre < 1f / 3f -> start * reference * scale
            centre > 2f / 3f -> actual - (1f - start - size) * reference * scale - scaled
            else -> centre * actual - scaled / 2f
        }
    }

    private fun aspect(context: Context, layout: SavedInputLayout): Float = layout.aspect.takeIf { it > 0f } ?: run {
        val metrics = InputLibraries.primaryDisplay(context).second
        max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1).toFloat() / min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    }
}
