package com.droiddeck.launcher.input

object InputSpace {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    fun x(position: Float, viewWidth: Int): Int = scale(position, viewWidth, WIDTH)

    fun y(position: Float, viewHeight: Int): Int = scale(position, viewHeight, HEIGHT)

    private fun scale(position: Float, extent: Int, size: Int): Int =
        if (extent <= 0) 0 else (position / extent * size).toInt().coerceIn(0, size - 1)
}
