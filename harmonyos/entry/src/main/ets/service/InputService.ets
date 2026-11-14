import { Logger } from '../common/Logger';
import { CompositorBridge } from '../native/CompositorBridge';
import { NativeBridge } from '../native/NativeBridge';

/**
 * Feeds the device's input into the Linux guest.
 *
 * Upstream DroidDeck reads Android's `MotionEvent` and `KeyEvent` and
 * translates them into evdev codes that the guest's input stack
 * understands (`input/EvdevKeys.java`, `input/PadBridge.java`). HarmonyOS
 * has no `MotionEvent`, so the port reads ArkUI's own touch and key
 * events and produces the same evdev codes, which then travel the same
 * two paths upstream uses:
 *
 *   * into the compositor, so the guest's Wayland clients see the
 *     pointer and keyboard;
 *   * into the virtual gamepad, so full-screen games see a pad.
 */

/** The compositor's touch actions. */
const TOUCH_DOWN: number = 0;
const TOUCH_MOVE: number = 1;
const TOUCH_UP: number = 2;
const TOUCH_CANCEL: number = 3;

/**
 * ArkUI key code to Linux evdev code.
 *
 * The values are the same ones upstream's `EvdevKeys` produces for the
 * equivalent Android key codes, so the guest sees identical input.
 */
const EVDEV_KEYS: Record<number, number> = {
  // Letters.
  2017: 30, // A
  2018: 48, // B
  2019: 46, // C
  2020: 32, // D
  2021: 18, // E
  2022: 33, // F
  2023: 34, // G
  2024: 35, // H
  2025: 23, // I
  2026: 36, // J
  2027: 37, // K
  2028: 38, // L
  2029: 50, // M
  2030: 49, // N
  2031: 24, // O
  2032: 25, // P
  2033: 16, // Q
  2034: 19, // R
  2035: 31, // S
  2036: 20, // T
  2037: 22, // U
  2038: 47, // V
  2039: 17, // W
  2040: 45, // X
  2041: 21, // Y
  2042: 44, // Z

  // Digits.
  2000: 11, // 0
  2001: 2,  // 1
  2002: 3,  // 2
  2003: 4,  // 3
  2004: 5,  // 4
  2005: 6,  // 5
  2006: 7,  // 6
  2007: 8,  // 7
  2008: 9,  // 8
  2009: 10, // 9

  // Editing and whitespace.
  2054: 28,  // Enter
  2050: 57,  // Space
  2049: 15,  // Tab
  2055: 14,  // Delete
  2070: 1,   // Escape
  2: 111,    // Backspace

  // Modifiers.
  2047: 42,  // Shift
  2072: 29,  // Control
  2045: 56,  // Alt
  58: 58,    // Caps lock (the evdev code and the ArkUI code agree)

  // Arrow keys.
  2012: 103, // Up
  2013: 108, // Down
  2014: 105, // Left
  2015: 106, // Right

  // Function keys.
  2090: 59,  // F1
  2091: 60,  // F2
  2092: 61,  // F3
  2093: 62,  // F4
  2094: 63,  // F5
  2095: 64,  // F6
  2096: 65,  // F7
  2097: 66,  // F8
  2098: 67,  // F9
  2099: 68,  // F10
  2100: 87,  // F11
  2101: 88   // F12
};

export class InputService {
  /** The evdev code for an ArkUI key code, or -1 when it is not mapped. */
  static evdevFor(keyCode: number): number {
    const evdev = EVDEV_KEYS[keyCode];
    return evdev === undefined ? -1 : evdev;
  }

  /**
   * Forwards a key event to the guest.
   *
   * @param event the ArkUI key event
   * @returns true when the event was consumed
   */
  static handleKey(event: KeyEvent): boolean {
    const evdev = InputService.evdevFor(event.keyCode);
    if (evdev < 0) {
      return false;
    }
    const pressed = event.type === KeyType.Down ? 1 : 0;
    CompositorBridge.sendKey(evdev, pressed);
    NativeBridge.inputEvent('button', evdev, pressed);
    return true;
  }

  /**
   * Forwards a touch event to the guest.
   *
   * @param event the ArkUI touch event
   */
  static handleTouch(event: TouchEvent): void {
    const touches = event.touches;
    if (touches === undefined || touches.length === 0) {
      if (event.type === TouchType.Cancel) {
        CompositorBridge.sendTouch(TOUCH_CANCEL, -1, 0, 0);
      }
      return;
    }

    const touch = touches[0];
    let action: number = TOUCH_MOVE;
    if (event.type === TouchType.Down) {
      action = TOUCH_DOWN;
    } else if (event.type === TouchType.Up) {
      action = TOUCH_UP;
    } else if (event.type === TouchType.Cancel) {
      action = TOUCH_CANCEL;
    }

    CompositorBridge.sendTouch(action, touch.id, touch.x, touch.y);
  }

  /** Cancels any touch the guest still believes is down. */
  static cancelTouch(): void {
    CompositorBridge.sendTouch(TOUCH_CANCEL, -1, 0, 0);
    Logger.debug('the touch was cancelled');
  }
}
