package com.droiddeck.launcher.input

object InputSpace {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val FIXED_ONE = 256

    fun x(position: Float, viewWidth: Int): Int = scale(position, viewWidth, WIDTH)

    fun y(position: Float, viewHeight: Int): Int = scale(position, viewHeight, HEIGHT)

    fun fixedX(position: Float, viewWidth: Int): Int = scale(position, viewWidth, WIDTH * FIXED_ONE)

    fun fixedY(position: Float, viewHeight: Int): Int = scale(position, viewHeight, HEIGHT * FIXED_ONE)

    private fun scale(position: Float, extent: Int, size: Int): Int =
        if (extent <= 0) 0 else (position / extent * size).toInt().coerceIn(0, size - 1)
}
