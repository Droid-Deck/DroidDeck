package com.droiddeck.launcher.input

enum class InputRole { PHYSICAL, PRIMARY, SECONDARY }

enum class InputControl(val id: String, val label: String, val dimensions: Int = 1) {
    A("a", "A"), B("b", "B"), X("x", "X"), Y("y", "Y"),
    LB("lb", "L1"), RB("rb", "R1"), SELECT("select", "View / Select"), START("start", "Menu / Start"),
    L3("l3", "L3"), R3("r3", "R3"), GUIDE("guide", "Steam / Guide"), QAM("qam", "Quick access"),
    UP("up", "D-pad up"), RIGHT("right", "D-pad right"), DOWN("down", "D-pad down"), LEFT("left", "D-pad left"),
    LT("lt", "L2"), RT("rt", "R2"), LS("ls", "Left stick", 2), RS("rs", "Right stick", 2),
    L4("l4", "L4"), R4("r4", "R4"), L5("l5", "L5"), R5("r5", "R5"),
    LEFT_PAD("left-pad", "Left trackpad", 2), RIGHT_PAD("right-pad", "Right trackpad", 2),
    LEFT_CLICK("left-click", "Left trackpad click"), RIGHT_CLICK("right-click", "Right trackpad click");

    val isPad: Boolean get() = ordinal <= RS.ordinal
    val isTrackpad: Boolean get() = this == LEFT_PAD || this == RIGHT_PAD

    companion object {
        val padControls = entries.filter { it.isPad }.toTypedArray()
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
    }
}

enum class InputCommand { MENU, KEYBOARD, STEAM, QUICK_ACCESS }

sealed interface InputAction {
    data class Native(val target: InputControl? = null) : InputAction
    data object Disabled : InputAction
    data class Keys(val codes: List<Int>) : InputAction
    data class MouseButton(val code: Int) : InputAction
    data class Command(val command: InputCommand) : InputAction
    data class Directions(val up: Int, val right: Int, val down: Int, val left: Int) : InputAction
    data class Pointer(val speed: Float = 900f) : InputAction
    data class Scroll(val speed: Float = 10f) : InputAction
}

data class InputProfile(
    val id: String,
    val bindings: Map<InputRole, Map<InputControl, InputAction>> = emptyMap(),
    val keys: List<PhysicalInputBinding> = emptyList(),
) {
    fun action(role: InputRole, control: InputControl): InputAction =
        bindings[role]?.get(control) ?: InputAction.Native()

    fun withBinding(role: InputRole, control: InputControl, action: InputAction) =
        copy(bindings = bindings + (role to (bindings[role].orEmpty() + (control to action))))

    companion object {
        const val GAME = "game"
        const val DESKTOP = "desktop"

        fun defaults(id: String): InputProfile {
            if (id != DESKTOP) return InputProfile(GAME)
            val desktop = mapOf(
                InputControl.LS to InputAction.Pointer(), InputControl.RS to InputAction.Scroll(),
                InputControl.A to InputAction.MouseButton(272), InputControl.B to InputAction.MouseButton(273),
                InputControl.X to InputAction.Keys(listOf(28)), InputControl.Y to InputAction.Keys(listOf(1)),
                InputControl.UP to InputAction.Keys(listOf(103)), InputControl.RIGHT to InputAction.Keys(listOf(106)),
                InputControl.DOWN to InputAction.Keys(listOf(108)), InputControl.LEFT to InputAction.Keys(listOf(105)),
                InputControl.LT to InputAction.MouseButton(273), InputControl.RT to InputAction.MouseButton(272),
                InputControl.LB to InputAction.Keys(listOf(29)), InputControl.RB to InputAction.Keys(listOf(56)),
                InputControl.SELECT to InputAction.Command(InputCommand.KEYBOARD),
                InputControl.START to InputAction.Command(InputCommand.MENU),
                InputControl.LEFT_PAD to InputAction.Scroll(), InputControl.RIGHT_PAD to InputAction.Pointer(),
                InputControl.LEFT_CLICK to InputAction.MouseButton(273), InputControl.RIGHT_CLICK to InputAction.MouseButton(272),
            )
            return InputProfile(DESKTOP, InputRole.entries.associateWith { desktop })
        }
    }
}

internal fun InputAction.validFor(control: InputControl): Boolean = when (this) {
    is InputAction.Native -> target == null || (control.isPad == target.isPad && control.dimensions == target.dimensions &&
        (!control.isTrackpad && !target.isTrackpad || control.isTrackpad && target.isTrackpad))
    is InputAction.Keys -> codes.isNotEmpty() && codes.size <= 8 && codes.distinct().size == codes.size && codes.all { it in 1..255 }
    is InputAction.MouseButton -> code in 272..276
    is InputAction.Directions -> control.dimensions == 2 && listOf(up, right, down, left).all { it in 1..255 }
    is InputAction.Pointer -> control.dimensions == 2 && speed.isFinite() && speed in 100f..3000f
    is InputAction.Scroll -> control.dimensions == 2 && speed.isFinite() && speed in 1f..60f
    else -> true
}
