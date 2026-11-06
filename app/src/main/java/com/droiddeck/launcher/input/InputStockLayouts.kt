package com.droiddeck.launcher.input

internal object InputStockLayouts {
    const val DECK = "preset.deck"
    const val TRACKPAD = "preset.trackpad"
    const val LEFT = "preset.left-pad"
    const val RIGHT = "preset.right-pad"
    const val BOTH = "preset.both-pads"
    const val GRIPS = "preset.grips"
    const val GAMEPAD = "preset.gamepad"

    private fun commands() = listOf("Menu", "Keyboard", "Steam", "Quick access").mapIndexed { i, name ->
        InputSurfaceElement(InputControl.A, .02f + i * .245f, .01f, .225f, .11f, "command:$i", name = name)
    }
    private fun commandActions(): Map<String, InputAction> = listOf(InputCommand.MENU, InputCommand.KEYBOARD, InputCommand.STEAM, InputCommand.QUICK_ACCESS)
        .mapIndexed { i, command -> "command:$i" to InputAction.Command(command) }.toMap()
    private fun grips() = listOf(InputControl.L4, InputControl.L5, InputControl.R5, InputControl.R4).mapIndexed { i, c ->
        InputSurfaceElement(c, .02f + i * .245f, .78f, .225f, .20f)
    }
    private fun pads() = listOf(InputSurfaceElement(InputControl.LEFT_PAD, .02f, .14f, .47f, .62f),
        InputSurfaceElement(InputControl.RIGHT_PAD, .51f, .14f, .47f, .62f))
    private fun single(right: Boolean) = listOf(InputSurfaceElement(if (right) InputControl.RIGHT_PAD else InputControl.LEFT_PAD, .02f, .14f, .96f, .62f),
        InputSurfaceElement(if (right) InputControl.RIGHT_CLICK else InputControl.LEFT_CLICK, .02f, .78f, .96f, .20f, name = "Click"))

    fun layouts() = listOf(
        SavedInputLayout(InputLibraries.DECK, "Deck grips + trackpads", commands() + pads() + grips()),
        SavedInputLayout(InputLibraries.TRACKPAD, "Trackpad + keyboard", commands().take(2).mapIndexed { i, e -> e.copy(x = .02f + i * .49f, width = .47f) } + listOf(
            InputSurfaceElement(InputControl.RIGHT_PAD, .02f, .14f, .96f, .62f, mouseGestures = true),
            InputSurfaceElement(InputControl.A, .02f, .78f, .47f, .20f, name = "Left click"),
            InputSurfaceElement(InputControl.B, .51f, .78f, .47f, .20f, name = "Right click"))),
        SavedInputLayout("builtin.left-pad", "Left Deck trackpad", commands() + single(false)),
        SavedInputLayout("builtin.right-pad", "Right Deck trackpad", commands() + single(true)),
        SavedInputLayout("builtin.both-pads", "Two Deck trackpads", commands() + pads() + listOf(
            InputSurfaceElement(InputControl.LEFT_CLICK, .02f, .78f, .47f, .20f, name = "Left pad click"),
            InputSurfaceElement(InputControl.RIGHT_CLICK, .51f, .78f, .47f, .20f, name = "Right pad click"))),
        SavedInputLayout("builtin.grips", "Deck grips", commands() + listOf(InputControl.L4, InputControl.R4, InputControl.L5, InputControl.R5).mapIndexed { i, c ->
            InputSurfaceElement(c, .02f + (i % 2) * .49f, .14f + (i / 2) * .43f, .47f, .41f)
        }),
        SavedInputLayout("builtin.gamepad", "Compact gamepad", gamepad()),
    )

    private fun gamepad(): List<InputSurfaceElement> {
        val w = 600f; val h = 500f
        val boxes = GamepadGeometry.layout(w, h, 1f, portraitDock = true)
        val result = mutableListOf<InputSurfaceElement>()
        val controls = mapOf(GamepadGeometry.Slot.LEFT_MAIN to InputControl.LS, GamepadGeometry.Slot.RIGHT_MAIN to InputControl.RS,
            GamepadGeometry.Slot.LEFT_OUTER_SHOULDER to InputControl.LT, GamepadGeometry.Slot.LEFT_INNER_SHOULDER to InputControl.LB,
            GamepadGeometry.Slot.RIGHT_INNER_SHOULDER to InputControl.RB, GamepadGeometry.Slot.RIGHT_OUTER_SHOULDER to InputControl.RT,
            GamepadGeometry.Slot.VIEW to InputControl.SELECT, GamepadGeometry.Slot.MENU to InputControl.START,
            GamepadGeometry.Slot.STEAM to InputControl.GUIDE, GamepadGeometry.Slot.QAM to InputControl.QAM,
            GamepadGeometry.Slot.L4 to InputControl.L4, GamepadGeometry.Slot.L5 to InputControl.L5,
            GamepadGeometry.Slot.R4 to InputControl.R4, GamepadGeometry.Slot.R5 to InputControl.R5)
        for ((slot, box) in boxes) {
            fun add(c: InputControl, x: Float = box.left, y: Float = box.top, width: Float = box.width, height: Float = box.height,
                id: String = "legacy:${c.id}", kind: InputElementKind = InputElementKind.from(c), name: String = c.label) {
                result += InputSurfaceElement(c, x / w, y / h, width / w, height / h, id, kind, name, floating = false).bounded()
            }
            when (slot) {
                GamepadGeometry.Slot.LEFT_SECOND -> add(InputControl.LS, id = "compact:dpad", kind = InputElementKind.DPAD, name = "D-pad")
                GamepadGeometry.Slot.RIGHT_SECOND -> {
                    val size = minOf(box.width, box.height) * .32f
                    add(InputControl.Y, box.cx - size / 2, box.top, size, size)
                    add(InputControl.X, box.left, box.cy - size / 2, size, size)
                    add(InputControl.B, box.right - size, box.cy - size / 2, size, size)
                    add(InputControl.A, box.cx - size / 2, box.bottom - size, size, size)
                }
                GamepadGeometry.Slot.PANELS -> add(InputControl.A, id = "command:0", name = "Menu")
                else -> controls[slot]?.let { add(it) }
            }
        }
        return result
    }

    fun actions(layout: SavedInputLayout): Map<String, InputAction> = commandActions().filterKeys { id -> layout.elements.any { it.id == id } } + if (layout.id == InputLibraries.TRACKPAD) mapOf(
        "legacy:right-pad" to InputAction.Pointer(), "legacy:a" to InputAction.MouseButton(272), "legacy:b" to InputAction.MouseButton(273),
    ) else if (layout.id == "builtin.gamepad") mapOf("compact:dpad" to InputAction.Native()) else emptyMap()

    fun clicks(layout: SavedInputLayout): Map<String, InputAction> = if (layout.id == InputLibraries.TRACKPAD)
        mapOf("legacy:right-pad" to InputAction.MouseButton(272)) else emptyMap()

    fun presets(layouts: List<SavedInputLayout>) = layouts.zip(listOf(DECK, TRACKPAD, LEFT, RIGHT, BOTH, GRIPS, GAMEPAD)) { layout, id ->
        SavedInputBindings(id, layout.name, false, layout.id, elements = actions(layout), clicks = clicks(layout))
    }
}
