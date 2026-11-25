package com.droiddeck.launcher.input

import android.content.Context
import android.graphics.Rect
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OnScreenLayout {
    internal fun supportsFloatingSteamControls(controller: String, layout: SavedInputLayout?) =
        layout?.overlay == true &&
            (controller == SessionPrefs.CONTROLLER_DECK || controller == SessionPrefs.CONTROLLER_STEAM)

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

    private const val TOLERANCE = .01f
    private const val KEYBOARD_ID = "builtin:keyboard"
    private val REAR = setOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)
    private val BAR = setOf(InputControl.GUIDE, InputControl.QAM)

    fun elements(context: Context, saved: SavedInputLayout?, width: Int, height: Int, safe: Rect,
        buttonsOnly: Boolean = false, dock: Dock? = null, rearButtons: Boolean? = null,
        trackpads: Boolean? = null): List<InputSurfaceElement> {
        if (saved == null) return emptyList()
        if (width <= 0 || height <= 0) return saved.elements
        val layout = oriented(saved, dock?.portrait ?: (height > width))
        val stored = layout.elements
        val generator = LegacyGamepadLayout(context, width, height, safe, layout.scale)
        val (reference, matched) = reference(context, layout, width, height)
        if (buttonsOnly && dock == null) return generator.buttonsOnly(stored) + fit(stored.filter { it.alwaysShown }, reference.first, reference.second, width, height, safe)
        if (dock?.portrait == true && !dock.grips && !matched && layout.aspect > 0f && layout.aspect < 1f && stored.any(::isGamepad))
            return cluster(stored.filterNot { it.alwaysShown || isGamepad(it) && (it.id == KEYBOARD_ID || it.control in BAR) }, layout.aspect, width, height, safe)
        val generated = matched || PadKit.forOverlay(context, layout) != null && stored.any(::isGamepad) &&
            (dock != null || height > width && layout.aspect >= 1f || height <= width && layout.aspect > 0f && layout.aspect < 1f)
        val gamepad = if (generated) stored.filter(::isGamepad) else emptyList()
        val others = fit(stored.filterNot { generated && isGamepad(it) || dock != null && it.alwaysShown }, reference.first, reference.second, width, height, safe)
        val regenerated = when {
            gamepad.isEmpty() -> emptyList()
            dock != null -> generator.dock(gamepad, dock.portrait, dock.grips, dock.left, dock.right)
            else -> generator.regenerate(gamepad)
        }
        val placed = regenerated + gamepad.filter { g -> dock == null && optional(g) && regenerated.none { it.id == g.id } }
        val byId = (placed + others).associateBy { it.id }
        val ordered = stored.mapNotNull { byId[it.id] } + placed.filter { p -> stored.none { it.id == p.id } }
        val kit = PadKit.forOverlay(context, layout) ?: return ordered
        if (generated &&
            ControllerPrefs.layout(context, reference.first.toInt(), reference.second.toInt()).isEmpty() &&
            ControllerPrefs.layout(context, width, height).isEmpty()) {
            if (dock != null || height > width) {
                return ControllerPortraitGeometry(width.toFloat(), height.toFloat(), safe, context.resources.displayMetrics.density)
                    .place(ordered, kit, dock != null, layout.scale, rearButtons == true, trackpads == true)
            }
            return ControllerOverlayGeometry(width, height, safe, context.resources.displayMetrics, layout.scale)
                .place(ordered, kit, rearButtons == true, trackpads == true)
        }
        if (dock != null || !kit.steam) return ordered
        return FloatingSteamOverlay(width, height, safe, context.resources.displayMetrics.density)
            .place(ordered, lowerShortcuts = generated, rearButtons = rearButtons, trackpads = trackpads)
    }

    fun oriented(layout: SavedInputLayout, width: Int, height: Int): SavedInputLayout = oriented(layout, height > width)

    private fun oriented(layout: SavedInputLayout, portrait: Boolean): SavedInputLayout {
        if (layout.rotated.isEmpty() || layout.aspect <= 0f || (layout.aspect < 1f) == portrait || (layout.rotatedAspect < 1f) != portrait) return layout
        val rotated = layout.rotated.associateBy { it.id }
        return layout.copy(elements = layout.elements.map { e -> rotated[e.id]?.let { e.copy(x = it.x, y = it.y, width = it.width, height = it.height) } ?: e } +
            layout.rotated.filter { r -> optional(r) && layout.elements.none { it.id == r.id } },
            aspect = layout.rotatedAspect, scale = layout.rotatedScale, rotated = emptyList(), rotatedAspect = 0f, rotatedScale = 1f)
    }

    fun withFrame(context: Context, saved: SavedInputLayout, elements: List<InputSurfaceElement>, aspect: Float, scale: Float = saved.scale): SavedInputLayout {
        val (_, metrics) = InputLibraries.primaryDisplay(context)
        val generated = generatedSize(context, saved, metrics.widthPixels, metrics.heightPixels) != null
        val portraitMain = saved.aspect > 0f && saved.aspect < 1f
        if (aspect <= 0f || aspect >= 1f || portraitMain && !generated) {
            val rotated = if (aspect >= 1f && portraitMain && !generated) Triple(saved.elements, saved.aspect, saved.scale) else Triple(saved.rotated, saved.rotatedAspect, saved.rotatedScale)
            return saved.copy(elements = elements, aspect = aspect, scale = scale, rotated = rotated.first,
                rotatedAspect = if (rotated.first.isEmpty()) 0f else rotated.second, rotatedScale = if (rotated.first.isEmpty()) 1f else rotated.third)
        }
        val main = if (portraitMain) 1f / saved.aspect else saved.aspect.takeIf { it > 0f } ?: (1f / aspect)
        val ids = elements.associateBy { it.id }
        val kept = saved.elements.mapNotNull { e -> ids[e.id]?.copy(x = e.x, y = e.y, width = e.width, height = e.height) }
        val added = fit(elements.filter { d -> !optional(d) && saved.elements.none { it.id == d.id } }, aspect, 1f, (main * 1000).roundToInt().coerceAtLeast(1), 1000, Rect())
        return saved.copy(elements = kept + added, aspect = main, rotated = elements, rotatedAspect = aspect, rotatedScale = scale)
    }

    fun optionalControls(context: Context): Pair<Boolean?, Boolean?> {
        val steam = SessionState.mode == SessionService.MODE_STEAM && SessionState.deckPad
        return SessionPrefs.oscRearButtons(context).takeIf { steam } to SessionPrefs.oscTrackpads(context).takeIf { steam }
    }

    fun editorElements(context: Context, layout: SavedInputLayout, width: Int, height: Int, edited: Boolean = false, safe: Rect = Rect()): List<InputSurfaceElement> {
        val (rear, pads) = optionalControls(context)
        return elements(context, if (edited && width > 0 && height > 0) layout.copy(aspect = width.toFloat() / height) else layout, width, height, safe,
            rearButtons = rear, trackpads = pads)
    }

    fun editorFrame(layout: SavedInputLayout, displayed: List<InputSurfaceElement>): List<InputSurfaceElement> {
        val byId = displayed.associateBy { it.id }
        return layout.elements.map { byId[it.id] ?: it } + displayed.filter { d ->
            optional(d) && layout.elements.none { it.id == d.id }
        }
    }

    fun safeInsets(insets: android.view.WindowInsets?, docked: Boolean): Rect {
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) insets?.displayCutout else null
        return Rect(cutout?.safeInsetLeft ?: 0, if (docked) 0 else cutout?.safeInsetTop ?: 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
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

    private fun cluster(elements: List<InputSurfaceElement>, aspect: Float, width: Int, height: Int, safe: Rect): List<InputSurfaceElement> {
        if (elements.isEmpty()) return elements
        val left = elements.minOf { it.x } * aspect
        val top = elements.minOf { it.y }
        val spanX = (elements.maxOf { it.x + it.width } * aspect - left).coerceAtLeast(.0001f)
        val spanY = (elements.maxOf { it.y + it.height } - top).coerceAtLeast(.0001f)
        val w = (width - safe.left - safe.right).toFloat().coerceAtLeast(1f)
        val h = (height - safe.top - safe.bottom).toFloat().coerceAtLeast(1f)
        val scale = minOf(w / aspect, w / spanX, h / spanY)
        val originX = safe.left + (w - spanX * scale) / 2f - left * scale
        val originY = safe.top + (h - spanY * scale) / 2f - top * scale
        return elements.map { e ->
            e.copy(x = (originX + e.x * aspect * scale) / width, y = (originY + e.y * scale) / height,
                width = e.width * aspect * scale / width, height = e.height * scale / height)
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

    private fun optional(e: InputSurfaceElement) = e.id == "legacy:${e.control.id}" && (e.control.isTrackpad || e.control in REAR)

    private fun isGamepad(e: InputSurfaceElement) = e.id.startsWith("legacy:") || e.id == KEYBOARD_ID

    fun generatedSize(context: Context, layout: SavedInputLayout, width: Int, height: Int): Pair<Int, Int>? =
        reference(context, layout, width, height).takeIf { it.second }?.first?.let { it.first.toInt() to it.second.toInt() }

    private fun reference(context: Context, layout: SavedInputLayout, width: Int, height: Int): Pair<Pair<Float, Float>, Boolean> {
        val (display, metrics) = InputLibraries.primaryDisplay(context)
        val long = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val short = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val gamepad = layout.elements.filter(::isGamepad)
        if (gamepad.any { it.id.startsWith("legacy:") }) {
            val named = layout.id.removePrefix("migrated.layout.").takeIf { it != layout.id }?.split('x')?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 2 && it.all { size -> size > 0 } }?.let { it[0] to it[1] }
            val aspectSize = layout.aspect.takeIf { layout.overlay && it > 0f }
                ?.let { (it * height).roundToInt().coerceAtLeast(1) to height }
            for ((w, h) in listOfNotNull(named, width to height, aspectSize, long to short, short to long).distinct()) {
                val generator = LegacyGamepadLayout(display, w, h, scale = layout.scale)
                if (matches(gamepad, generator.elements()) || matches(gamepad, generator.elements(previousStickGeometry = true)) ||
                    ControllerPrefs.sizes.any { size ->
                        val sizedGenerator = LegacyGamepadLayout(display, w, h, scale = layout.scale, sizeOverride = size)
                        matches(gamepad, sizedGenerator.elements()) || matches(gamepad, sizedGenerator.elements(previousStickGeometry = true))
                    }) return (w.toFloat() to h.toFloat()) to true
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
