package com.droiddeck.launcher.input

internal object InputStockLayouts {
    const val DECK = "preset.deck"
    const val TRACKPAD = "preset.trackpad"
    private fun commands(indices: List<Int>): List<InputSurfaceElement> {
        val names = listOf("DroidDeck menu", "Keyboard", "Steam", "Quick access")
        val icons = listOf(InputButtonIcons.MENU, InputButtonIcons.KEYBOARD, InputButtonIcons.STEAM, InputButtonIcons.QUICK_ACCESS)
        val step = .98f / indices.size
        return indices.mapIndexed { position, i ->
            InputSurfaceElement(InputControl.A, .02f + position * step, .01f, step - .02f, .11f,
                "command:$i", name = names[i], icon = icons[i])
        }
    }
    private fun commandActions(): Map<String, InputAction> = listOf(InputCommand.MENU, InputCommand.KEYBOARD, InputCommand.STEAM, InputCommand.QUICK_ACCESS)
        .mapIndexed { i, command -> "command:$i" to InputAction.Command(command) }.toMap()
    private fun grips() = listOf(InputControl.L4, InputControl.L5, InputControl.R5, InputControl.R4).mapIndexed { i, c ->
        InputSurfaceElement(c, .02f + i * .245f, .78f, .225f, .20f)
    }
    private fun pads() = listOf(InputSurfaceElement(InputControl.LEFT_PAD, .02f, .14f, .47f, .62f),
        InputSurfaceElement(InputControl.RIGHT_PAD, .51f, .14f, .47f, .62f))
    fun layouts() = listOf(
        SavedInputLayout(InputLibraries.DECK, "Deck grips + trackpads", commands(listOf(2, 3, 0)) + pads() + grips(), screen = InputLayoutScreen.SECONDARY),
        SavedInputLayout(InputLibraries.TRACKPAD, "Trackpad + keyboard", commands(listOf(0, 1)) +
            InputSurfaceElement(InputControl.RIGHT_PAD, .02f, .14f, .96f, .84f, mouseGestures = true), screen = InputLayoutScreen.SECONDARY),
    )

    fun actions(layout: SavedInputLayout): Map<String, InputAction> = (commandActions() + if (layout.id == InputLibraries.TRACKPAD) mapOf(
        "legacy:right-pad" to InputAction.Pointer(), "legacy:a" to InputAction.MouseButton(272), "legacy:b" to InputAction.MouseButton(273),
    ) else emptyMap()).filterKeys { id -> layout.elements.any { it.id == id } }

    fun clicks(layout: SavedInputLayout): Map<String, InputAction> = if (layout.id == InputLibraries.TRACKPAD)
        mapOf("legacy:right-pad" to InputAction.MouseButton(272)) else emptyMap()

    fun presets(layouts: List<SavedInputLayout>) = layouts.map { layout ->
        val desktop = layout.id == InputLibraries.TRACKPAD
        SavedInputBindings(if (desktop) TRACKPAD else DECK, layout.name, false, layout.id,
            controls = InputProfile.defaults(if (desktop) InputProfile.DESKTOP else InputProfile.GAME).bindings[InputRole.SECONDARY].orEmpty(),
            elements = actions(layout), clicks = clicks(layout))
    }
}
