package com.droiddeck.launcher.input

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

internal object ArmsControlSkins {
    private val buttons = mapOf(
        InputControl.A to "cross", InputControl.B to "circle", InputControl.X to "square", InputControl.Y to "triangle",
        InputControl.LB to "l1", InputControl.RB to "r1", InputControl.LT to "l2", InputControl.RT to "r2",
        InputControl.L3 to "l3", InputControl.R3 to "r3", InputControl.SELECT to "select", InputControl.START to "start",
        InputControl.UP to "up", InputControl.DOWN to "down", InputControl.LEFT to "left", InputControl.RIGHT to "right")
    private val keys = buttons.values.toSet() + setOf("analog_base", "analog_stick", "analog_base_left", "analog_base_right",
        "analog_stick_left", "analog_stick_right")

    fun parse(context: android.content.Context, bytes: ByteArray, original: SavedInputLayout, actions: SavedInputBindings, name: String, nativeFailure: Exception? = null): CommunityControls.Pack {
        require(bytes.size <= 32 * 1024 * 1024 && original.editable && actions.compatible(original))
        val art = linkedMapOf<String, ByteArray>()
        var total = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 256 && ControlSources.validPath(entry.name.trimEnd('/'))) { "Invalid skin archive path or file count." }
                if (entry.isDirectory) continue
                val data = CommunityControls.readLimited(zip, minOf(8 * 1024 * 1024, 64 * 1024 * 1024 - total))
                total += data.size
                if (entry.name == "controls.json") throw (nativeFailure ?: IllegalArgumentException("Import this file as a DroidDeck controls pack."))
                val file = entry.name.substringAfterLast('/').lowercase()
                if (file.startsWith("._") || !file.endsWith(".png")) continue
                val key = file.removeSuffix(".png").removePrefix("ic_controller_").removeSuffix("_button")
                if (key !in keys) continue
                require(key !in art) { "Duplicate skin button artwork." }
                art[key] = normalize(data)
            }
        }
        require(art.isNotEmpty()) { "No supported controller PNGs found in this skin." }
        val used = linkedMapOf<String, ByteArray>()
        fun image(key: String): String? = art[key]?.let { data ->
            val file = "skin_$key.png"
            used[file] = data
            file
        }
        var matched = 0
        val elements = original.elements.map { e ->
            val action = actions.action(e) as? InputAction.Native ?: return@map e
            val control = action.target ?: e.control
            if (e.kind == InputElementKind.STICK && (control == InputControl.LS || control == InputControl.RS)) {
                val side = if (control == InputControl.LS) "left" else "right"
                val base = image("analog_base_$side") ?: image("analog_base")
                val stick = image("analog_stick_$side") ?: image("analog_stick")
                if (base == null && stick == null) e else {
                    matched++
                    e.copy(image = base ?: e.image, stickImage = stick ?: e.stickImage, showLabel = false)
                }
            } else if (e.kind == InputElementKind.BUTTON || e.kind == InputElementKind.TRIGGER) {
                val file = buttons[control]?.let(::image)
                if (file == null) e else { matched++; e.copy(image = file, icon = 0, showLabel = false) }
            } else e
        }
        require(matched > 0) { "This layout has no matching gamepad controls. Choose a gamepad profile and screen." }
        val id = newInputId()
        val label = name.trim().take(80).ifBlank { "Imported controller skin" }
        val layout = original.copy(id = id, name = label, origin = null, elements = elements)
        for (file in elements.flatMap { listOf(it.image, it.stickImage) }.filter { it.isNotEmpty() }.distinct()) {
            if (file !in used) used[file] = requireNotNull(InputButtonImages.file(context, file)).inputStream().use {
                CommunityControls.readLimited(it, 1024 * 1024)
            }
        }
        return CommunityControls.Pack(layout, actions.copy(id = newInputId(), name = label, layoutId = id), used)
    }

    fun export(context: android.content.Context, layout: SavedInputLayout, actions: SavedInputBindings): ByteArray {
        val images = linkedMapOf<String, String>()
        for (element in layout.elements) {
            val action = actions.action(element) as? InputAction.Native ?: continue
            val control = action.target ?: element.control
            if (element.kind == InputElementKind.STICK && (control == InputControl.LS || control == InputControl.RS)) {
                val side = if (control == InputControl.LS) "left" else "right"
                if (element.image.isNotEmpty()) images["ic_controller_analog_base_$side.png"] = element.image
                if (element.stickImage.isNotEmpty()) images["ic_controller_analog_stick_$side.png"] = element.stickImage
            } else if (element.image.isNotEmpty()) {
                buttons[control]?.let { images["ic_controller_${it}_button.png"] = element.image }
            }
        }
        require(images.isNotEmpty()) { "This layout has no controller skin artwork to export." }
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            for ((name, local) in images) {
                val bytes = requireNotNull(InputButtonImages.file(context, local)).inputStream().use {
                    CommunityControls.readLimited(it, 1024 * 1024)
                }
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray().also { require(it.size <= 32 * 1024 * 1024) }
    }

    private fun normalize(data: ByteArray): ByteArray {
        require(data.take(8) == listOf<Byte>(-119, 80, 78, 71, 13, 10, 26, 10)) { "Invalid skin PNG." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "Invalid skin image dimensions." }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options)) { "Unreadable skin image." }
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
