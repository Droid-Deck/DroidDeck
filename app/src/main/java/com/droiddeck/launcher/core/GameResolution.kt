package com.droiddeck.launcher.core

object GameResolution {
    sealed interface Choice
    data class Fixed(val width: Int, val height: Int) : Choice
    data class Scaled(val scale: Float) : Choice

    data class Aspect(val label: String, val sizes: List<Fixed>)

    val aspects = listOf(
        Aspect("16:9", listOf(Fixed(960, 540), Fixed(1280, 720), Fixed(1600, 900), Fixed(1920, 1080), Fixed(2560, 1440))),
        Aspect("16:10", listOf(Fixed(1280, 800), Fixed(1440, 900), Fixed(1680, 1050), Fixed(1920, 1200), Fixed(2560, 1600))),
        Aspect("4:3", listOf(Fixed(640, 480), Fixed(800, 600), Fixed(1024, 768), Fixed(1280, 960), Fixed(1440, 1080))),
    )

    val scales = listOf(1.5f, 2f, 3f)

    const val MIN_WIDTH = 320
    const val MIN_HEIGHT = 240
    const val MAX_SIDE = 7680
    private const val SCALED_PREFIX = "/"

    fun parse(value: String?): Choice? {
        val text = value?.trim().orEmpty()
        if (text.startsWith(SCALED_PREFIX)) {
            return text.removePrefix(SCALED_PREFIX).toFloatOrNull()?.takeIf { it > 1f && it <= 8f }?.let(::Scaled)
        }
        val parts = text.split('x', 'X', '×').map { it.trim().toIntOrNull() }
        if (parts.size != 2) return null
        val (w, h) = parts
        return if (w != null && h != null && valid(w, h)) Fixed(w and 1.inv(), h and 1.inv()) else null
    }

    fun valid(width: Int, height: Int) = width in MIN_WIDTH..MAX_SIDE && height in MIN_HEIGHT..MAX_SIDE

    fun format(choice: Choice): String = when (choice) {
        is Fixed -> "${choice.width}x${choice.height}"
        is Scaled -> SCALED_PREFIX + formatScale(choice.scale)
    }

    fun formatScale(scale: Float): String =
        (Math.round(scale * 10) / 10f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }

    fun resolve(choice: Choice, output: Pair<Int, Int>): Pair<Int, Int> = when (choice) {
        is Fixed -> choice.width to choice.height
        is Scaled -> (Math.round(output.first / choice.scale).coerceAtLeast(MIN_WIDTH) and 1.inv()) to
            (Math.round(output.second / choice.scale).coerceAtLeast(MIN_HEIGHT) and 1.inv())
    }

    fun aspectLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val ratio = width.toFloat() / height
        aspects.firstOrNull { a ->
            val (w, h) = a.label.split(':').map(String::toFloat)
            kotlin.math.abs(ratio - w / h) < 0.02f
        }?.let { return it.label }
        if (kotlin.math.abs(ratio - 64f / 27f) < 0.04f || kotlin.math.abs(ratio - 21f / 9f) < 0.04f) return "21:9"
        val g = gcd(width, height)
        return if (width / g <= 32) "${width / g}:${height / g}" else String.format(java.util.Locale.ROOT, "%.2f:1", ratio)
    }

    fun sizesFor(aspect: Aspect, panel: Pair<Int, Int>): List<Fixed> {
        val long = maxOf(panel.first, panel.second)
        val short = minOf(panel.first, panel.second)
        val fit = aspect.sizes.filter { it.width <= long && it.height <= short }
        return fit.ifEmpty { aspect.sizes.take(1) }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
