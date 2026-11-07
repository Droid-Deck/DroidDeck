package com.droiddeck.launcher.input

import androidx.annotation.StringRes
import com.droiddeck.launcher.R

enum class DeckPanel(val id: String, val layout: String, val bindings: String, @StringRes val label: Int) {
    GAMEPAD("gamepad", "builtin.deck.gamepad", "preset.deck.gamepad", R.string.deck_tab_gamepad),
    GRIPS("grips", "builtin.deck.grips", "preset.deck.grips", R.string.deck_tab_grips),
    LEFT("left", "builtin.deck.left", "preset.deck.left", R.string.deck_tab_left),
    RIGHT("right", "builtin.deck.right", "preset.deck.right", R.string.deck_tab_right),
    BOTH("both", InputLibraries.DECK, InputStockLayouts.DECK, R.string.deck_tab_both);

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: BOTH
    }
}

internal object InputStockLayouts {
    private val NEUTRAL = InputSurfaceStyle(tint = null)
    const val DECK = "preset.deck"
    const val TRACKPAD = "preset.trackpad"
    private fun grips() = listOf(InputControl.L4, InputControl.L5, InputControl.R5, InputControl.R4).mapIndexed { i, c ->
        InputSurfaceElement(c, .02f + i * .245f, .78f, .225f, .20f)
    }
    private fun pads() = listOf(InputSurfaceElement(InputControl.LEFT_PAD, .02f, .02f, .47f, .74f),
        InputSurfaceElement(InputControl.RIGHT_PAD, .51f, .02f, .47f, .74f))
    private fun gripGrid() = listOf(InputControl.L4 to (.02f to .02f), InputControl.L5 to (.02f to .51f),
        InputControl.R4 to (.51f to .02f), InputControl.R5 to (.51f to .51f)).map { (c, at) ->
        InputSurfaceElement(c, at.first, at.second, .47f, .47f)
    }
    private fun pad(right: Boolean) = listOf(
        InputSurfaceElement(if (right) InputControl.RIGHT_PAD else InputControl.LEFT_PAD, .02f, .02f, .96f, .74f),
        InputSurfaceElement(if (right) InputControl.RIGHT_CLICK else InputControl.LEFT_CLICK, .02f, .78f, .96f, .20f),
    )
    private fun gamepad(): List<InputSurfaceElement> {
        fun round(c: InputControl, x: Float, y: Float, size: Float) = InputSurfaceElement(c, x, y, size, size, buttonShape = InputButtonShape.CIRCLE)
        fun pill(c: InputControl, x: Float) = InputSurfaceElement(c, x, .02f, .2f, .12f, buttonShape = InputButtonShape.PILL)
        return listOf(pill(InputControl.LT, .02f), pill(InputControl.LB, .24f), pill(InputControl.RB, .56f), pill(InputControl.RT, .78f),
            InputSurfaceElement(InputControl.LS, .02f, .17f, .4f, .4f),
            InputSurfaceElement(InputControl.RIGHT_PAD, .58f, .59f, .4f, .39f),
            round(InputControl.UP, .14f, .6f, .12f), round(InputControl.LEFT, .03f, .73f, .12f),
            round(InputControl.RIGHT, .25f, .73f, .12f), round(InputControl.DOWN, .14f, .86f, .12f),
            round(InputControl.Y, .72f, .17f, .12f), round(InputControl.X, .61f, .3f, .12f),
            round(InputControl.B, .83f, .3f, .12f), round(InputControl.A, .72f, .43f, .12f),
            InputSurfaceElement(InputControl.SELECT, .42f, .3f, .07f, .1f, buttonShape = InputButtonShape.PILL),
            InputSurfaceElement(InputControl.START, .51f, .3f, .07f, .1f, buttonShape = InputButtonShape.PILL))
    }
    fun layouts() = listOf(
        SavedInputLayout(InputLibraries.DECK, "Deck grips + trackpads", pads() + grips(), style = NEUTRAL, screen = InputLayoutScreen.SECONDARY),
        SavedInputLayout(InputLibraries.TRACKPAD, "Trackpad + keyboard", listOf(
            InputSurfaceElement(InputControl.RIGHT_PAD, .02f, .02f, .96f, .96f, mouseGestures = true)), style = NEUTRAL, screen = InputLayoutScreen.SECONDARY),
        SavedInputLayout(DeckPanel.GAMEPAD.layout, "Deck gamepad", gamepad(), style = NEUTRAL, screen = InputLayoutScreen.SECONDARY),
        SavedInputLayout(DeckPanel.GRIPS.layout, "Deck grips", gripGrid(), style = NEUTRAL, screen = InputLayoutScreen.SECONDARY),
        SavedInputLayout(DeckPanel.LEFT.layout, "Deck left trackpad", pad(right = false), style = NEUTRAL, screen = InputLayoutScreen.SECONDARY),
        SavedInputLayout(DeckPanel.RIGHT.layout, "Deck right trackpad", pad(right = true), style = NEUTRAL, screen = InputLayoutScreen.SECONDARY),
    )

    fun actions(layout: SavedInputLayout): Map<String, InputAction> = (if (layout.id == InputLibraries.TRACKPAD) mapOf(
        "legacy:right-pad" to InputAction.Pointer(), "legacy:a" to InputAction.MouseButton(272), "legacy:b" to InputAction.MouseButton(273),
    ) else emptyMap()).filterKeys { id -> layout.elements.any { it.id == id } }

    fun clicks(layout: SavedInputLayout): Map<String, InputAction> = if (layout.id == InputLibraries.TRACKPAD)
        mapOf("legacy:right-pad" to InputAction.MouseButton(272)) else emptyMap()

    fun presets(layouts: List<SavedInputLayout>) = layouts.map { layout ->
        val desktop = layout.id == InputLibraries.TRACKPAD
        val id = if (desktop) TRACKPAD else DeckPanel.entries.first { it.layout == layout.id }.bindings
        SavedInputBindings(id, layout.name, false, layout.id,
            controls = InputProfile.defaults(if (desktop) InputProfile.DESKTOP else InputProfile.GAME).bindings[InputRole.SECONDARY].orEmpty(),
            elements = actions(layout), clicks = clicks(layout))
    }
}
