package com.droiddeck.launcher.input

import kotlin.math.abs

interface InputOutput {
    fun key(code: Int, down: Boolean)
    fun button(code: Int, down: Boolean)
    fun pointer(dx: Float, dy: Float)
    fun scroll(steps: Int)
    fun scrollHorizontal(steps: Int) {}
    fun command(command: InputCommand)
    fun requestMotion()
}

class InputRouter(private val output: InputOutput) {
    private val keys = IntArray(256)
    private val buttons = IntArray(5)
    private val channels = ArrayList<Channel>()
    private val pads = ArrayList<PadSource>()
    private var profile = InputProfile.defaults(InputProfile.GAME)
    private var moving = false
    private var wheel = 0f
    private var horizontalWheel = 0f
    private var enabled = true
    private val snapshots = ArrayList<() -> Unit>()

    @Synchronized
    fun beforeProfileChange(snapshot: () -> Unit) { snapshots.add(snapshot) }

    @Synchronized
    fun removeSnapshot(snapshot: () -> Unit) { snapshots.remove(snapshot) }

    @Synchronized
    fun setEnabled(on: Boolean): Boolean {
        if (enabled == on) return false
        if (!on) releaseAll()
        enabled = on
        for (pad in pads) pad.refreshIdentity()
        return true
    }

    @Synchronized
    fun setProfile(next: InputProfile) {
        if (profile == next) return
        for (snapshot in snapshots) snapshot()
        for (pad in pads) pad.snapshot()
        profile = next
        for (channel in channels) channel.configure(channel.actionFrom(next))
        for (pad in pads) pad.refreshIdentity()
        wheel = 0f
        horizontalWheel = 0f
        moving = false
    }

    @JvmOverloads
    @Synchronized
    fun pad(role: InputRole, retained: PadState? = null): PadSource = PadSource(role, retained).also { pads.add(it) }

    @Synchronized
    fun channel(role: InputRole, control: InputControl, fixedAction: InputAction? = null, element: InputSurfaceElement? = null, elementClick: Boolean = false): Channel = Channel(role, control, fixedAction, element, elementClick).also {
        channels.add(it)
        it.configure(it.actionFrom(profile))
    }

    @Synchronized
    fun releaseAll() {
        for (snapshot in snapshots) snapshot()
        for (pad in pads) pad.snapshot()
        for (channel in channels) channel.suspend()
        for (pad in pads) pad.refreshIdentity()
        wheel = 0f
        horizontalWheel = 0f
        moving = false
    }

    @Synchronized
    fun tick(seconds: Float): Boolean {
        if (!enabled) return false
        var x = 0f
        var y = 0f
        var scroll = 0f
        var horizontalScroll = 0f
        for (channel in channels) {
            if (channel.blocked || channel.control.isTrackpad) continue
            val cx = if (abs(channel.x) > 0.12f) channel.x else 0f
            val cy = if (abs(channel.y) > 0.12f) channel.y else 0f
            when (val action = channel.action) {
                is InputAction.Pointer -> { x += cx * action.speed; y += cy * action.speed }
                is InputAction.Scroll -> if (action.horizontal) horizontalScroll += cx * action.speed else scroll += cy * action.speed
                else -> Unit
            }
        }
        val dt = seconds.coerceIn(0f, 0.05f)
        if (x != 0f || y != 0f) output.pointer(x * dt, y * dt)
        wheel += scroll * dt
        horizontalWheel += horizontalScroll * dt
        flushWheel()
        moving = x != 0f || y != 0f || scroll != 0f || horizontalScroll != 0f
        return moving
    }

    private fun flushWheel() {
        val steps = wheel.toInt()
        if (steps != 0) { wheel -= steps; output.scroll(steps) }
        val horizontalSteps = horizontalWheel.toInt()
        if (horizontalSteps != 0) { horizontalWheel -= horizontalSteps; output.scrollHorizontal(horizontalSteps) }
    }

    private fun key(code: Int, down: Boolean) {
        val was = keys[code]
        keys[code] = (was + if (down) 1 else -1).coerceAtLeast(0)
        if ((was == 0) != (keys[code] == 0)) output.key(code, down)
    }

    private fun button(code: Int, down: Boolean) {
        val at = code - 272
        val was = buttons[at]
        buttons[at] = (was + if (down) 1 else -1).coerceAtLeast(0)
        if ((was == 0) != (buttons[at] == 0)) output.button(code, down)
    }

    inner class Channel internal constructor(val role: InputRole, val control: InputControl, private val fixedAction: InputAction?, private val element: InputSurfaceElement?, private val elementClick: Boolean) {
        var action: InputAction = InputAction.Native()
            private set
        internal var x = 0f
        internal var y = 0f
        internal var contact = false
        internal var blocked = false
        private var held = false
        private val directions = BooleanArray(4)
        private var radialKeys = emptyList<Int>()

        internal fun actionFrom(profile: InputProfile) = element?.let { if (elementClick) profile.elementClickAction(role, it) else profile.elementAction(role, it.id, control) }
            ?: profile.action(role, control)

        internal fun configure(next: InputAction) {
            suspend()
            action = (fixedAction ?: next).takeIf { if (elementClick) it.validForClick() else if (element != null) it.validFor(element) else it.validFor(control) }
                ?: if (element != null) InputAction.Disabled else InputAction.Native()
        }

        internal fun observe(value: Float, second: Float = 0f, touching: Boolean = false) {
            x = value; y = second; contact = touching
        }

        internal fun awaitNeutral(value: Float, second: Float, touching: Boolean) = synchronized(this@InputRouter) {
            observe(value, second, touching)
            suspend()
        }

        internal fun nativeIdentity(): Boolean = enabled && !blocked && (action as? InputAction.Native)?.target == null && action is InputAction.Native

        internal fun suspend() {
            releaseOutputs()
            blocked = contact || abs(x) > 0.12f || abs(y) > 0.12f
        }

        fun release() = synchronized(this@InputRouter) {
            releaseOutputs()
            x = 0f; y = 0f; contact = false; blocked = false
        }

        fun close() = synchronized(this@InputRouter) {
            release()
            channels.remove(this)
        }

        fun acceptsPointerGesture() = synchronized(this@InputRouter) { enabled && !blocked && contact && action is InputAction.Pointer }

        fun pointerDelta(dx: Float, dy: Float) = synchronized(this@InputRouter) {
            val pointer = action as? InputAction.Pointer
            if (enabled && !blocked && contact && pointer != null && dx.isFinite() && dy.isFinite()) output.pointer(dx * pointer.speed, dy * pointer.speed)
        }

        fun wheelSteps(steps: Int) = synchronized(this@InputRouter) {
            val scroll = action as? InputAction.Scroll
            if (enabled && !blocked && scroll != null && steps != 0) {
                if (scroll.horizontal) output.scrollHorizontal(steps) else output.scroll(steps)
            }
        }

        fun update(value: Float, second: Float = 0f, touching: Boolean = false): InputControl? = synchronized(this@InputRouter) {
            val oldX = x
            val oldY = y
            val oldContact = contact
            x = value.coerceIn(-1f, 1f)
            y = second.coerceIn(-1f, 1f)
            contact = touching
            if (!enabled) {
                blocked = contact || abs(x) > 0.12f || abs(y) > 0.12f
                return@synchronized null
            }
            if (blocked) {
                if (!contact && abs(x) <= 0.12f && abs(y) <= 0.12f) blocked = false
                return@synchronized null
            }
            when (val a = action) {
                is InputAction.Native -> return@synchronized a.target ?: control
                is InputAction.Disabled -> Unit
                is InputAction.Pointer -> {
                    if (control.isTrackpad) {
                        if (contact && oldContact) output.pointer((x - oldX) * a.speed, -(y - oldY) * a.speed)
                    } else requestMotion()
                }
                is InputAction.Scroll -> {
                    if (control.isTrackpad) {
                        if (contact && oldContact) {
                            if (a.horizontal) horizontalWheel -= (x - oldX) * a.speed else wheel += (y - oldY) * a.speed
                            flushWheel()
                        }
                    } else requestMotion()
                }
                is InputAction.Directions -> {
                    val vertical = if (control.isTrackpad) -y else y
                    if (a.diagonals == null) {
                        direction(0, -vertical, a.up); direction(1, x, a.right)
                        direction(2, vertical, a.down); direction(3, -x, a.left)
                    } else {
                        val cardinal = listOf(a.up, a.right, a.down, a.left)
                        val sector = ((kotlin.math.round(Math.toDegrees(kotlin.math.atan2(vertical, x).toDouble()) / 45).toInt() + 2) % 8 + 8) % 8
                        val next = if (kotlin.math.hypot(x, vertical) < .24f) emptyList() else if (sector % 2 == 0) listOf(cardinal[sector / 2])
                            else a.diagonals[sector / 2].let { if (it != 0) listOf(it) else listOf(cardinal[sector / 2], cardinal[(sector / 2 + 1) % 4]) }
                        val codes = next.filter { it != 0 }.distinct()
                        radialKeys.filterNot { it in codes }.forEach { key(it, false) }
                        codes.filterNot { it in radialKeys }.forEach { key(it, true) }
                        radialKeys = codes
                    }
                }
                else -> {
                    val down = if (control.isTrackpad) contact else maxOf(abs(x), abs(y)) > if (held) 0.35f else 0.55f
                    if (held != down) {
                        held = down
                        when (a) {
                            is InputAction.Keys -> if (down) a.codes.forEach { key(it, true) } else a.codes.asReversed().forEach { key(it, false) }
                            is InputAction.MouseButton -> button(a.code, down)
                            is InputAction.Command -> if (down) output.command(a.command)
                            else -> Unit
                        }
                    }
                }
            }
            null
        }

        private fun requestMotion() {
            if (!moving && (abs(x) > 0.12f || abs(y) > 0.12f)) { moving = true; output.requestMotion() }
        }

        private fun direction(at: Int, value: Float, code: Int) {
            val down = value > if (directions[at]) 0.35f else 0.55f
            if (directions[at] != down) { directions[at] = down; if (code != 0) key(code, down) }
        }

        private fun releaseOutputs() {
            when (val a = action) {
                is InputAction.Keys -> if (held) a.codes.asReversed().forEach { key(it, false) }
                is InputAction.MouseButton -> if (held) button(a.code, false)
                is InputAction.Directions -> {
                    val codes = intArrayOf(a.up, a.right, a.down, a.left)
                    for (i in directions.indices) if (directions[i] && codes[i] != 0) key(codes[i], false)
                }
                else -> Unit
            }
            radialKeys.forEach { key(it, false) }; radialKeys = emptyList()
            held = false
            directions.fill(false)
        }
    }

    inner class PadSource internal constructor(role: InputRole, retained: PadState?) {
        private val routes = Array(InputControl.padControls.size) { channel(role, InputControl.padControls[it]) }
        private val result = PadState()
        private var last: PadState? = null
        private var identity = true
        private var nativeCandidate = true

        init {
            last = retained
            if (retained != null) for (route in routes) {
                route.awaitNeutral(retained.value(route.control), retained.secondValue(route.control), false)
            }
            refreshIdentity()
        }

        internal fun refreshIdentity() {
            nativeCandidate = routes.all { (it.action as? InputAction.Native)?.target == null && it.action is InputAction.Native }
            identity = enabled && nativeCandidate && routes.none { it.blocked }
        }

        internal fun snapshot() {
            val raw = last ?: return
            for (route in routes) {
                route.x = raw.value(route.control)
                route.y = raw.secondValue(route.control)
            }
        }

        fun route(raw: PadState): PadState = synchronized(this@InputRouter) {
            last = raw
            if (identity) return@synchronized raw
            result.clear()
            for (route in routes) {
                val x = raw.value(route.control)
                val y = raw.secondValue(route.control)
                val target = route.update(x, y) ?: continue
                result.add(target, x, y)
            }
            if (nativeCandidate && enabled && routes.none { it.blocked }) identity = true
            result
        }

        fun release() = synchronized(this@InputRouter) {
            for (route in routes) route.release()
            last = null
            result.clear()
            refreshIdentity()
        }

        fun close() = synchronized(this@InputRouter) {
            release()
            for (route in routes) route.close()
            pads.remove(this)
        }
    }
}

private val padButtons = intArrayOf(PadState.A, PadState.B, PadState.X, PadState.Y, PadState.LB, PadState.RB,
    PadState.SELECT, PadState.START, PadState.L3, PadState.R3, PadState.GUIDE, PadState.QAM)

internal fun PadState.value(control: InputControl): Float = when (control) {
    InputControl.UP -> if (up) 1f else 0f
    InputControl.RIGHT -> if (right) 1f else 0f
    InputControl.DOWN -> if (down) 1f else 0f
    InputControl.LEFT -> if (left) 1f else 0f
    InputControl.LT -> leftTrigger
    InputControl.RT -> rightTrigger
    InputControl.LS -> leftX
    InputControl.RS -> rightX
    else -> if (control.ordinal < padButtons.size && isDown(padButtons[control.ordinal])) 1f else 0f
}

internal fun PadState.secondValue(control: InputControl): Float = when (control) {
    InputControl.LS -> leftY
    InputControl.RS -> rightY
    else -> 0f
}

internal fun PadState.add(control: InputControl, x: Float, y: Float = 0f) {
    when (control) {
        InputControl.UP -> up = up || x > 0.5f
        InputControl.RIGHT -> right = right || x > 0.5f
        InputControl.DOWN -> down = down || x > 0.5f
        InputControl.LEFT -> left = left || x > 0.5f
        InputControl.LT -> leftTrigger = maxOf(leftTrigger, x)
        InputControl.RT -> rightTrigger = maxOf(rightTrigger, x)
        InputControl.LS -> if (x * x + y * y > leftX * leftX + leftY * leftY) { leftX = x; leftY = y }
        InputControl.RS -> if (x * x + y * y > rightX * rightX + rightY * rightY) { rightX = x; rightY = y }
        else -> if (control.ordinal < padButtons.size && x > 0.5f) press(padButtons[control.ordinal], true)
    }
}
