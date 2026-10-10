package com.droiddeck.launcher.agent

import com.droiddeck.launcher.input.PadState
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.json.JSONObject

/**
 * Input into the running session the way a player gives it: keys and touches through the
 * compositor (what the session screen sends), and the virtual pad through the same PadBridge the
 * on-screen controls drive, so games that ignore touch still see a controller.
 */
object AgentInput {
    /** Out of the way of real fingers, which Android numbers from 0. */
    private const val TOUCH_ID = 9
    private const val TOUCH_SCALE_X = 1919f
    private const val TOUCH_SCALE_Y = 1079f

    fun handle(request: JSONObject): JSONObject {
        if (!SessionState.running || !SessionState.firstFrameSeen) {
            throw AgentException("SESSION_NOT_READY", "Input needs a running session that has drawn its first frame")
        }
        return when (val type = request.optString("type")) {
            "key" -> key(request)
            "text" -> text(request)
            "tap" -> tap(request)
            "swipe" -> swipe(request)
            "pad" -> pad(request)
            "stick" -> stick(request)
            "trigger" -> trigger(request)
            else -> throw AgentException("INVALID_INPUT", "Unknown input type '$type'")
        }.put("ok", true).put("command", "input").put("type", request.optString("type"))
    }

    private fun key(request: JSONObject): JSONObject {
        val name = request.optString("key")
        val code = KEYS[name.uppercase()] ?: name.toIntOrNull()
            ?: throw AgentException("INVALID_INPUT", "Unknown key '$name' (a name such as ENTER or an evdev code)")
        val modifiers = request.optJSONArray("modifiers")?.let { array ->
            List(array.length()) { i ->
                KEYS[array.optString(i).uppercase()] ?: throw AgentException("INVALID_INPUT", "Unknown modifier '${array.optString(i)}'")
            }
        }.orEmpty()
        when (val action = request.optString("action", "tap")) {
            "down" -> WaylandCompositor.nativeSendKey(code, 1)
            "up" -> WaylandCompositor.nativeSendKey(code, 0)
            "tap" -> {
                modifiers.forEach { WaylandCompositor.nativeSendKey(it, 1) }
                WaylandCompositor.nativeSendKey(code, 1)
                hold(request, 40)
                WaylandCompositor.nativeSendKey(code, 0)
                modifiers.asReversed().forEach { WaylandCompositor.nativeSendKey(it, 0) }
            }
            else -> throw AgentException("INVALID_INPUT", "Key action '$action' is not tap, down or up")
        }
        return JSONObject().put("code", code)
    }

    /** Typed as key presses (US layout), which reaches X programs that have no text-input support. */
    private fun text(request: JSONObject): JSONObject {
        val text = request.optString("text")
        if (request.optBoolean("commit")) {
            WaylandCompositor.textInputCommit(text)
            return JSONObject().put("length", text.length).put("via", "text-input")
        }
        val missing = text.filter { TYPED[it] == null }
        if (missing.isNotEmpty()) throw AgentException("INVALID_INPUT", "Cannot type '${missing.toSet().joinToString("")}' as keys; use commit")
        val delay = request.optLong("delayMs", 15)
        for (ch in text) {
            val (code, shift) = TYPED.getValue(ch)
            if (shift) WaylandCompositor.nativeSendKey(KEYS.getValue("LEFTSHIFT"), 1)
            WaylandCompositor.nativeSendKey(code, 1)
            WaylandCompositor.nativeSendKey(code, 0)
            if (shift) WaylandCompositor.nativeSendKey(KEYS.getValue("LEFTSHIFT"), 0)
            Thread.sleep(delay)
        }
        return JSONObject().put("length", text.length).put("via", "keys")
    }

    private fun tap(request: JSONObject): JSONObject {
        val (x, y) = point(request, "x", "y")
        WaylandCompositor.nativeSendTouch(0, TOUCH_ID, x, y)
        hold(request, 60)
        WaylandCompositor.nativeSendTouch(2, TOUCH_ID, x, y)
        return JSONObject().put("touch", org.json.JSONArray().put(x).put(y))
    }

    private fun swipe(request: JSONObject): JSONObject {
        val (x1, y1) = point(request, "x", "y")
        val (x2, y2) = point(request, "x2", "y2")
        val duration = request.optLong("durationMs", 300).coerceIn(16, 10_000)
        val steps = (duration / 16).toInt().coerceAtLeast(2)
        WaylandCompositor.nativeSendTouch(0, TOUCH_ID, x1, y1)
        for (i in 1..steps) {
            Thread.sleep(duration / steps)
            WaylandCompositor.nativeSendTouch(1, TOUCH_ID, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
        }
        WaylandCompositor.nativeSendTouch(2, TOUCH_ID, x2, y2)
        return JSONObject().put("from", org.json.JSONArray().put(x1).put(y1)).put("to", org.json.JSONArray().put(x2).put(y2))
    }

    /**
     * A point in the compositor's touch space (0..1919 x 0..1079 whatever the session's size), from
     * the session's output pixels (space "output", the default: what the guest draws at) or 0..1
     * fractions (space "fraction").
     */
    private fun point(request: JSONObject, xKey: String, yKey: String): Pair<Int, Int> {
        if (!request.has(xKey) || !request.has(yKey)) throw AgentException("INVALID_INPUT", "$xKey and $yKey are required")
        val x = request.getDouble(xKey)
        val y = request.getDouble(yKey)
        val (fx, fy) = when (val space = request.optString("space", "output")) {
            "fraction" -> x to y
            "output" -> {
                val (w, h) = SessionState.outputSize
                x / (w - 1).coerceAtLeast(1) to y / (h - 1).coerceAtLeast(1)
            }
            else -> throw AgentException("INVALID_INPUT", "space '$space' is not output or fraction")
        }
        return (fx.coerceIn(0.0, 1.0) * TOUCH_SCALE_X).toInt() to (fy.coerceIn(0.0, 1.0) * TOUCH_SCALE_Y).toInt()
    }

    private fun pad(request: JSONObject): JSONObject {
        val bridge = SessionState.padBridge ?: throw AgentException("NO_PAD", "The session has no virtual pad")
        val name = request.optString("button").lowercase()
        if (name == "qam") {
            bridge.triggerQam()
            return JSONObject().put("button", name)
        }
        val button = BUTTONS[name]
        if (button == null && name !in DPAD) throw AgentException("INVALID_INPUT", "Unknown pad button '$name'")
        val apply = { s: PadState, down: Boolean ->
            when (name) {
                "up" -> s.up = down
                "down" -> s.down = down
                "left" -> s.left = down
                "right" -> s.right = down
                else -> s.press(button!!, down)
            }
        }
        when (val action = request.optString("action", "tap")) {
            "down" -> bridge.applyTouch { apply(it, true) }
            "up" -> bridge.applyTouch { apply(it, false) }
            "tap" -> {
                bridge.applyTouch { apply(it, true) }
                hold(request, 120)
                bridge.applyTouch { apply(it, false) }
            }
            else -> throw AgentException("INVALID_INPUT", "Pad action '$action' is not tap, down or up")
        }
        return JSONObject().put("button", name)
    }

    private fun stick(request: JSONObject): JSONObject {
        val bridge = SessionState.padBridge ?: throw AgentException("NO_PAD", "The session has no virtual pad")
        val right = request.optString("stick", "left") == "right"
        val x = request.optDouble("x", 0.0).toFloat().coerceIn(-1f, 1f)
        val y = request.optDouble("y", 0.0).toFloat().coerceIn(-1f, 1f)
        bridge.applyTouch { if (right) { it.rightX = x; it.rightY = y } else { it.leftX = x; it.leftY = y } }
        hold(request, 300)
        bridge.applyTouch { if (right) { it.rightX = 0f; it.rightY = 0f } else { it.leftX = 0f; it.leftY = 0f } }
        return JSONObject().put("stick", if (right) "right" else "left")
    }

    private fun trigger(request: JSONObject): JSONObject {
        val bridge = SessionState.padBridge ?: throw AgentException("NO_PAD", "The session has no virtual pad")
        val right = request.optString("trigger", "right") == "right"
        val value = request.optDouble("value", 1.0).toFloat().coerceIn(0f, 1f)
        bridge.applyTouch { if (right) it.rightTrigger = value else it.leftTrigger = value }
        hold(request, 200)
        bridge.applyTouch { if (right) it.rightTrigger = 0f else it.leftTrigger = 0f }
        return JSONObject().put("trigger", if (right) "right" else "left")
    }

    private fun hold(request: JSONObject, defaultMs: Long) =
        Thread.sleep(request.optLong("holdMs", defaultMs).coerceIn(0, 10_000))

    private val DPAD = setOf("up", "down", "left", "right")

    private val BUTTONS = mapOf(
        "a" to PadState.A, "b" to PadState.B, "x" to PadState.X, "y" to PadState.Y,
        "lb" to PadState.LB, "rb" to PadState.RB, "select" to PadState.SELECT, "back" to PadState.SELECT,
        "start" to PadState.START, "menu" to PadState.START, "l3" to PadState.L3, "r3" to PadState.R3,
        "guide" to PadState.GUIDE, "steam" to PadState.GUIDE,
    )

    /** Linux evdev codes (input-event-codes.h) by name. */
    private val KEYS: Map<String, Int> = buildMap {
        "1234567890".forEachIndexed { i, c -> put(c.toString(), 2 + i) }
        "QWERTYUIOP".forEachIndexed { i, c -> put(c.toString(), 16 + i) }
        "ASDFGHJKL".forEachIndexed { i, c -> put(c.toString(), 30 + i) }
        "ZXCVBNM".forEachIndexed { i, c -> put(c.toString(), 44 + i) }
        (1..10).forEach { put("F$it", 58 + it) }
        put("F11", 87); put("F12", 88)
        putAll(mapOf(
            "ESC" to 1, "MINUS" to 12, "EQUAL" to 13, "BACKSPACE" to 14, "TAB" to 15, "LEFTBRACE" to 26,
            "RIGHTBRACE" to 27, "ENTER" to 28, "LEFTCTRL" to 29, "SEMICOLON" to 39, "APOSTROPHE" to 40,
            "GRAVE" to 41, "LEFTSHIFT" to 42, "BACKSLASH" to 43, "COMMA" to 51, "DOT" to 52, "SLASH" to 53,
            "RIGHTSHIFT" to 54, "LEFTALT" to 56, "SPACE" to 57, "CAPSLOCK" to 58, "RIGHTCTRL" to 97,
            "RIGHTALT" to 100, "HOME" to 102, "UP" to 103, "PAGEUP" to 104, "LEFT" to 105, "RIGHT" to 106,
            "END" to 107, "DOWN" to 108, "PAGEDOWN" to 109, "INSERT" to 110, "DELETE" to 111, "LEFTMETA" to 125,
            "CTRL" to 29, "SHIFT" to 42, "ALT" to 56, "META" to 125, "SUPER" to 125, "RETURN" to 28, "ESCAPE" to 1,
        ))
    }

    /** US-layout characters as (evdev code, shift). */
    private val TYPED: Map<Char, Pair<Int, Boolean>> = buildMap {
        for (c in 'a'..'z') put(c, KEYS.getValue(c.uppercase()) to false)
        for (c in 'A'..'Z') put(c, KEYS.getValue(c.toString()) to true)
        for (c in '0'..'9') put(c, KEYS.getValue(c.toString()) to false)
        "!@#$%^&*()".forEachIndexed { i, c -> put(c, KEYS.getValue(((i + 1) % 10).toString()) to true) }
        putAll(mapOf(
            ' ' to (57 to false), '\n' to (28 to false), '\t' to (15 to false),
            '-' to (12 to false), '_' to (12 to true), '=' to (13 to false), '+' to (13 to true),
            '[' to (26 to false), '{' to (26 to true), ']' to (27 to false), '}' to (27 to true),
            ';' to (39 to false), ':' to (39 to true), '\'' to (40 to false), '"' to (40 to true),
            '`' to (41 to false), '~' to (41 to true), '\\' to (43 to false), '|' to (43 to true),
            ',' to (51 to false), '<' to (51 to true), '.' to (52 to false), '>' to (52 to true),
            '/' to (53 to false), '?' to (53 to true),
        ))
    }
}
