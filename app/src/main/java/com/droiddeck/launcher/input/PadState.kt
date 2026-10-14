package com.droiddeck.launcher.input

class PadState {
    @JvmField var leftX = 0f
    @JvmField var leftY = 0f
    @JvmField var rightX = 0f
    @JvmField var rightY = 0f
    @JvmField var leftTrigger = 0f
    @JvmField var rightTrigger = 0f
    @JvmField var up = false
    @JvmField var right = false
    @JvmField var down = false
    @JvmField var left = false
    private var buttons = 0

    fun press(button: Int, down: Boolean) {
        buttons = if (down) buttons or (1 shl button) else buttons and (1 shl button).inv()
    }

    fun isDown(button: Int): Boolean = buttons and (1 shl button) != 0

    fun hatX(): Int = if (right && !left) 1 else if (left && !right) -1 else 0
    fun hatY(): Int = if (down && !up) 1 else if (up && !down) -1 else 0

    fun copyFrom(other: PadState) {
        leftX = other.leftX; leftY = other.leftY; rightX = other.rightX; rightY = other.rightY
        leftTrigger = other.leftTrigger; rightTrigger = other.rightTrigger
        up = other.up; right = other.right; down = other.down; left = other.left
        buttons = other.buttons
    }

    fun clear() {
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
        leftTrigger = 0f; rightTrigger = 0f
        up = false; right = false; down = false; left = false
        buttons = 0
    }

    companion object {
        const val A = 0
        const val B = 1
        const val X = 2
        const val Y = 3
        const val LB = 4
        const val RB = 5
        const val SELECT = 6
        const val START = 7
        const val L3 = 8
        const val R3 = 9
        const val GUIDE = 12
        const val QAM = 13
    }
}
