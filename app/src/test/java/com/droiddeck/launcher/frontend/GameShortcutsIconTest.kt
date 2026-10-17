package com.droiddeck.launcher.frontend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShortcutsIconTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("shortcut-icons").toFile()
    }

    @Test fun aSmallSteamIconGivesWayToTheMiddleOfTheCoverArt() {
        val icon = image("icon.png", 32, 32, Color.GREEN)
        val cover = image("cover.png", 300, 450, Color.BLUE, band = 75, bandColor = Color.RED)
        val bitmap = GameShortcuts.iconBitmap(game(icon = icon, art = cover))!!
        assertEquals(300, bitmap.width)
        assertEquals(300, bitmap.height)
        assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
        assertEquals(Color.BLUE, bitmap.getPixel(299, 299))
    }

    @Test fun anIconLargeEnoughForTheHomeScreenIsKept() {
        val icon = image("icon.png", 256, 256, Color.GREEN)
        val cover = image("cover.png", 300, 450, Color.BLUE)
        val bitmap = GameShortcuts.iconBitmap(game(icon = icon, art = cover))!!
        assertEquals(256, bitmap.width)
        assertEquals(Color.GREEN, bitmap.getPixel(128, 128))
    }

    @Test fun theSmallIconIsStillUsedWhenThereIsNoArt() {
        val icon = image("icon.png", 32, 32, Color.GREEN)
        val bitmap = GameShortcuts.iconBitmap(game(icon = icon, art = null))!!
        assertEquals(32, bitmap.width)
        assertEquals(Color.GREEN, bitmap.getPixel(16, 16))
    }

    private fun game(icon: File?, art: File?) =
        Library.SteamGame(appId = 17480, name = "Game", art = art, library = "test", icon = icon)

    private fun image(name: String, width: Int, height: Int, color: Int, band: Int = 0, bandColor: Int = color): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        if (band > 0) {
            val paint = Paint().apply { this.color = bandColor }
            canvas.drawRect(0f, 0f, width.toFloat(), band.toFloat(), paint)
            canvas.drawRect(0f, (height - band).toFloat(), width.toFloat(), height.toFloat(), paint)
        }
        return File(dir, name).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
