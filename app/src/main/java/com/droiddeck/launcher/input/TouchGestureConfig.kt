/*
 * SPDX-License-Identifier: GPL-3.0
 * Modified for DroidDeck on 2026-10-15.
 */
package com.droiddeck.launcher.input

import org.json.JSONObject

data class TouchGestureConfig(
    val tapEnabled: Boolean = true,
    val tapAction: String = ACTION_LEFT_CLICK,
    val dragEnabled: Boolean = true,
    val dragAction: String = PAN_LEFT_CLICK_DRAG,
    val longPressEnabled: Boolean = false,
    val longPressAction: String = ACTION_RIGHT_CLICK,
    val longPressMouseBehavior: String = MOUSE_BEHAVIOR_HOLD,
    val longPressDelay: Int = DEFAULT_DELAY_MS,
    val directHoldBehavior: String = MOUSE_BEHAVIOR_CLICK,
    val doubleTapEnabled: Boolean = true,
    val doubleTapDelay: Int = DEFAULT_DELAY_MS,
    val twoFingerDragEnabled: Boolean = true,
    val twoFingerDragAction: String = PAN_ARROW_KEYS,
    val pinchEnabled: Boolean = true,
    val pinchAction: String = ZOOM_SCROLL_WHEEL,
    val twoFingerTapEnabled: Boolean = true,
    val twoFingerTapAction: String = ACTION_RIGHT_CLICK,
    val twoFingerHoldEnabled: Boolean = true,
    val twoFingerHoldAction: String = ACTION_MIDDLE_CLICK,
    val twoFingerHoldMouseBehavior: String = MOUSE_BEHAVIOR_HOLD,
    val twoFingerHoldDelay: Int = DEFAULT_DELAY_MS,
    val threeFingerTapEnabled: Boolean = true,
    val threeFingerTapAction: String = ACTION_SHOW_KEYBOARD,
    val threeFingerDragEnabled: Boolean = false,
    val threeFingerDragAction: String = PAN_ARROW_KEYS,
    val threeFingerHoldEnabled: Boolean = true,
    val threeFingerHoldAction: String = ACTION_KEY_ESC,
    val threeFingerHoldMouseBehavior: String = MOUSE_BEHAVIOR_HOLD,
    val threeFingerHoldDelay: Int = DEFAULT_DELAY_MS,
    val showGestureDebugOverlay: Boolean = false,
    val gestureThreshold: Int = DEFAULT_GESTURE_THRESHOLD,
    val mouseDragMovementMode: String = MOUSE_DRAG_MOVEMENT_DIRECT,
) {

    fun toJson(): String {
        return JSONObject().apply {
            put(KEY_TAP_ENABLED, tapEnabled)
            put(KEY_TAP_ACTION, tapAction)
            put(KEY_DRAG_ENABLED, dragEnabled)
            put(KEY_DRAG_ACTION, dragAction)
            put(KEY_LONG_PRESS_ENABLED, longPressEnabled)
            put(KEY_LONG_PRESS_ACTION, longPressAction)
            put(KEY_LONG_PRESS_MOUSE_BEHAVIOR, longPressMouseBehavior)
            put(KEY_LONG_PRESS_DELAY, longPressDelay)
            put(KEY_DIRECT_HOLD_BEHAVIOR, directHoldBehavior)
            put(KEY_DOUBLE_TAP_ENABLED, doubleTapEnabled)
            put(KEY_DOUBLE_TAP_DELAY, doubleTapDelay)
            put(KEY_TWO_FINGER_DRAG_ENABLED, twoFingerDragEnabled)
            put(KEY_TWO_FINGER_DRAG_ACTION, twoFingerDragAction)
            put(KEY_PINCH_ENABLED, pinchEnabled)
            put(KEY_PINCH_ACTION, pinchAction)
            put(KEY_TWO_FINGER_TAP_ENABLED, twoFingerTapEnabled)
            put(KEY_TWO_FINGER_TAP_ACTION, twoFingerTapAction)
            put(KEY_TWO_FINGER_HOLD_ENABLED, twoFingerHoldEnabled)
            put(KEY_TWO_FINGER_HOLD_ACTION, twoFingerHoldAction)
            put(KEY_TWO_FINGER_HOLD_MOUSE_BEHAVIOR, twoFingerHoldMouseBehavior)
            put(KEY_TWO_FINGER_HOLD_DELAY, twoFingerHoldDelay)
            put(KEY_THREE_FINGER_TAP_ENABLED, threeFingerTapEnabled)
            put(KEY_THREE_FINGER_TAP_ACTION, threeFingerTapAction)
            put(KEY_THREE_FINGER_DRAG_ENABLED, threeFingerDragEnabled)
            put(KEY_THREE_FINGER_DRAG_ACTION, threeFingerDragAction)
            put(KEY_THREE_FINGER_HOLD_ENABLED, threeFingerHoldEnabled)
            put(KEY_THREE_FINGER_HOLD_ACTION, threeFingerHoldAction)
            put(KEY_THREE_FINGER_HOLD_MOUSE_BEHAVIOR, threeFingerHoldMouseBehavior)
            put(KEY_THREE_FINGER_HOLD_DELAY, threeFingerHoldDelay)
            put(KEY_SHOW_GESTURE_DEBUG_OVERLAY, showGestureDebugOverlay)
            put(KEY_GESTURE_THRESHOLD, gestureThreshold)
            put(KEY_MOUSE_DRAG_MOVEMENT_MODE, mouseDragMovementMode)
        }.toString()
    }

    companion object {
        const val DEFAULT_DELAY_MS = 300
        const val DEFAULT_GESTURE_THRESHOLD = 40
        const val ACTION_LEFT_CLICK = "left_click"
        const val ACTION_RIGHT_CLICK = "right_click"
        const val ACTION_MIDDLE_CLICK = "middle_click"

        const val MOUSE_BEHAVIOR_HOLD = "hold"
        const val MOUSE_BEHAVIOR_CLICK = "click"
        const val MOUSE_BEHAVIOR_NONE = "none"

        const val MOUSE_DRAG_MOVEMENT_DIRECT = "direct"
        const val MOUSE_DRAG_MOVEMENT_RELATIVE = "relative"
        const val ACTION_SHOW_KEYBOARD = "show_keyboard"
        const val ACTION_KEY_ESC = "key_ESC"
        const val ACTION_KEY_TILDE = "key_TILDE"
        const val PAN_WASD = "wasd"
        const val PAN_INVERTED_WASD = "inverted_wasd"
        const val PAN_ARROW_KEYS = "arrow_keys"
        const val PAN_INVERTED_ARROW_KEYS = "inverted_arrow_keys"
        const val PAN_MIDDLE_MOUSE = "middle_mouse_pan"
        const val PAN_INVERTED_MIDDLE_MOUSE = "inverted_middle_mouse_pan"
        const val PAN_LEFT_CLICK_DRAG = "left_click_drag"
        const val PAN_RIGHT_CLICK_DRAG = "right_click_drag"
        const val ZOOM_SCROLL_WHEEL = "scroll_wheel"
        const val ZOOM_PLUS_MINUS = "plus_minus"
        const val ZOOM_PAGE_UP_DOWN = "page_up_down"
        private const val KEY_TAP_ENABLED = "tapEnabled"
        private const val KEY_TAP_ACTION = "tapAction"
        private const val KEY_DRAG_ENABLED = "dragEnabled"
        private const val KEY_DRAG_ACTION = "dragAction"
        private const val KEY_LONG_PRESS_ENABLED = "longPressEnabled"
        private const val KEY_LONG_PRESS_ACTION = "longPressAction"
        private const val KEY_LONG_PRESS_MOUSE_BEHAVIOR = "longPressMouseBehavior"
        private const val KEY_LONG_PRESS_DELAY = "longPressDelay"
        private const val KEY_DIRECT_HOLD_BEHAVIOR = "directHoldBehavior"
        private const val KEY_DOUBLE_TAP_ENABLED = "doubleTapEnabled"
        private const val KEY_DOUBLE_TAP_DELAY = "doubleTapDelay"
        private const val KEY_TWO_FINGER_DRAG_ENABLED = "twoFingerDragEnabled"
        private const val KEY_TWO_FINGER_DRAG_ACTION = "twoFingerDragAction"
        private const val KEY_PINCH_ENABLED = "pinchEnabled"
        private const val KEY_PINCH_ACTION = "pinchAction"
        private const val KEY_TWO_FINGER_TAP_ENABLED = "twoFingerTapEnabled"
        private const val KEY_TWO_FINGER_TAP_ACTION = "twoFingerTapAction"
        private const val KEY_TWO_FINGER_HOLD_ENABLED = "twoFingerHoldEnabled"
        private const val KEY_TWO_FINGER_HOLD_ACTION = "twoFingerHoldAction"
        private const val KEY_TWO_FINGER_HOLD_MOUSE_BEHAVIOR = "twoFingerHoldMouseBehavior"
        private const val KEY_TWO_FINGER_HOLD_DELAY = "twoFingerHoldDelay"
        private const val KEY_THREE_FINGER_TAP_ENABLED = "threeFingerTapEnabled"
        private const val KEY_THREE_FINGER_TAP_ACTION = "threeFingerTapAction"
        private const val KEY_THREE_FINGER_DRAG_ENABLED = "threeFingerDragEnabled"
        private const val KEY_THREE_FINGER_DRAG_ACTION = "threeFingerDragAction"
        private const val KEY_THREE_FINGER_HOLD_ENABLED = "threeFingerHoldEnabled"
        private const val KEY_THREE_FINGER_HOLD_ACTION = "threeFingerHoldAction"
        private const val KEY_THREE_FINGER_HOLD_MOUSE_BEHAVIOR = "threeFingerHoldMouseBehavior"
        private const val KEY_THREE_FINGER_HOLD_DELAY = "threeFingerHoldDelay"
        private const val KEY_SHOW_GESTURE_DEBUG_OVERLAY = "showGestureDebugOverlay"
        private const val KEY_GESTURE_THRESHOLD = "gestureThreshold"
        private const val KEY_MOUSE_DRAG_MOVEMENT_MODE = "mouseDragMovementMode"

        private fun fallbackDefaults(): TouchGestureConfig = TouchGestureConfig(
            twoFingerHoldEnabled = false,
            threeFingerTapEnabled = false,
            threeFingerHoldEnabled = false,
        )

        fun fromJson(json: String?): TouchGestureConfig {
            if (json.isNullOrBlank()) return fallbackDefaults()
            return try {
                val obj = JSONObject(json)
                TouchGestureConfig(
                    tapEnabled = obj.optBoolean(KEY_TAP_ENABLED, true),
                    tapAction = obj.optString(KEY_TAP_ACTION, ACTION_LEFT_CLICK),
                    dragEnabled = obj.optBoolean(KEY_DRAG_ENABLED, true),
                    dragAction = obj.optString(KEY_DRAG_ACTION, PAN_LEFT_CLICK_DRAG),
                    longPressEnabled = obj.optBoolean(KEY_LONG_PRESS_ENABLED, false),
                    longPressAction = obj.optString(KEY_LONG_PRESS_ACTION, ACTION_RIGHT_CLICK),
                    longPressMouseBehavior = normalizeMouseBehavior(
                        obj.optString(KEY_LONG_PRESS_MOUSE_BEHAVIOR, MOUSE_BEHAVIOR_HOLD)
                    ),
                    longPressDelay = obj.optInt(KEY_LONG_PRESS_DELAY, DEFAULT_DELAY_MS),
                    directHoldBehavior = normalizeDirectHoldBehavior(
                        obj.optString(KEY_DIRECT_HOLD_BEHAVIOR, MOUSE_BEHAVIOR_CLICK)
                    ),
                    doubleTapEnabled = obj.optBoolean(KEY_DOUBLE_TAP_ENABLED, true),
                    doubleTapDelay = obj.optInt(KEY_DOUBLE_TAP_DELAY, DEFAULT_DELAY_MS),
                    twoFingerDragEnabled = obj.optBoolean(KEY_TWO_FINGER_DRAG_ENABLED, true),
                    twoFingerDragAction = obj.optString(KEY_TWO_FINGER_DRAG_ACTION, PAN_ARROW_KEYS),
                    pinchEnabled = obj.optBoolean(KEY_PINCH_ENABLED, true),
                    pinchAction = obj.optString(KEY_PINCH_ACTION, ZOOM_SCROLL_WHEEL),
                    twoFingerTapEnabled = obj.optBoolean(KEY_TWO_FINGER_TAP_ENABLED, true),
                    twoFingerTapAction = obj.optString(KEY_TWO_FINGER_TAP_ACTION, ACTION_RIGHT_CLICK),
                    twoFingerHoldEnabled = obj.optBoolean(KEY_TWO_FINGER_HOLD_ENABLED, false),
                    twoFingerHoldAction = obj.optString(KEY_TWO_FINGER_HOLD_ACTION, ACTION_MIDDLE_CLICK),
                    twoFingerHoldMouseBehavior = normalizeMouseBehavior(
                        obj.optString(KEY_TWO_FINGER_HOLD_MOUSE_BEHAVIOR, MOUSE_BEHAVIOR_HOLD)
                    ),
                    twoFingerHoldDelay = obj.optInt(KEY_TWO_FINGER_HOLD_DELAY, DEFAULT_DELAY_MS),
                    threeFingerTapEnabled = obj.optBoolean(KEY_THREE_FINGER_TAP_ENABLED, false),
                    threeFingerTapAction = obj.optString(KEY_THREE_FINGER_TAP_ACTION, ACTION_SHOW_KEYBOARD),
                    threeFingerDragEnabled = obj.optBoolean(KEY_THREE_FINGER_DRAG_ENABLED, false),
                    threeFingerDragAction = obj.optString(KEY_THREE_FINGER_DRAG_ACTION, PAN_ARROW_KEYS),
                    threeFingerHoldEnabled = obj.optBoolean(KEY_THREE_FINGER_HOLD_ENABLED, false),
                    threeFingerHoldAction = obj.optString(KEY_THREE_FINGER_HOLD_ACTION, ACTION_KEY_ESC),
                    threeFingerHoldMouseBehavior = normalizeMouseBehavior(
                        obj.optString(KEY_THREE_FINGER_HOLD_MOUSE_BEHAVIOR, MOUSE_BEHAVIOR_HOLD)
                    ),
                    threeFingerHoldDelay = obj.optInt(KEY_THREE_FINGER_HOLD_DELAY, DEFAULT_DELAY_MS),
                    showGestureDebugOverlay = obj.optBoolean(KEY_SHOW_GESTURE_DEBUG_OVERLAY, false),
                    gestureThreshold = obj.optInt(KEY_GESTURE_THRESHOLD, DEFAULT_GESTURE_THRESHOLD),
                    mouseDragMovementMode = normalizeMouseDragMovementMode(
                        obj.optString(KEY_MOUSE_DRAG_MOVEMENT_MODE, MOUSE_DRAG_MOVEMENT_DIRECT),
                    ),
                )
            } catch (_: Exception) {
                fallbackDefaults()
            }
        }

        private fun normalizeDirectHoldBehavior(value: String): String {
            return if (value == MOUSE_BEHAVIOR_CLICK || value == MOUSE_BEHAVIOR_NONE) value else MOUSE_BEHAVIOR_HOLD
        }

        private fun normalizeMouseBehavior(value: String): String {
            return if (value == MOUSE_BEHAVIOR_CLICK) MOUSE_BEHAVIOR_CLICK else MOUSE_BEHAVIOR_HOLD
        }

        private fun normalizeMouseDragMovementMode(mode: String?): String {
            return if (mode == MOUSE_DRAG_MOVEMENT_RELATIVE) {
                MOUSE_DRAG_MOVEMENT_RELATIVE
            } else {
                MOUSE_DRAG_MOVEMENT_DIRECT
            }
        }

        val PAN_ACTIONS = listOf(
            PAN_ARROW_KEYS,
            PAN_INVERTED_ARROW_KEYS,
            PAN_WASD,
            PAN_INVERTED_WASD,
            PAN_MIDDLE_MOUSE,
            PAN_INVERTED_MIDDLE_MOUSE,
            PAN_LEFT_CLICK_DRAG,
            PAN_RIGHT_CLICK_DRAG,
        )

        val ZOOM_ACTIONS = listOf(
            ZOOM_SCROLL_WHEEL,
            ZOOM_PLUS_MINUS,
            ZOOM_PAGE_UP_DOWN,
        )

        @JvmStatic
        fun isMouseButtonAction(action: String?): Boolean =
            action == ACTION_LEFT_CLICK || action == ACTION_RIGHT_CLICK || action == ACTION_MIDDLE_CLICK
    }
}
