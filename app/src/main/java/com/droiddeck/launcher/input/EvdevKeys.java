package com.droiddeck.launcher.input;

import android.view.KeyEvent;

public final class EvdevKeys {
    private EvdevKeys() {}

    public static int fromKeyCode(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_A: return 30; case KeyEvent.KEYCODE_B: return 48;
            case KeyEvent.KEYCODE_C: return 46; case KeyEvent.KEYCODE_D: return 32;
            case KeyEvent.KEYCODE_E: return 18; case KeyEvent.KEYCODE_F: return 33;
            case KeyEvent.KEYCODE_G: return 34; case KeyEvent.KEYCODE_H: return 35;
            case KeyEvent.KEYCODE_I: return 23; case KeyEvent.KEYCODE_J: return 36;
            case KeyEvent.KEYCODE_K: return 37; case KeyEvent.KEYCODE_L: return 38;
            case KeyEvent.KEYCODE_M: return 50; case KeyEvent.KEYCODE_N: return 49;
            case KeyEvent.KEYCODE_O: return 24; case KeyEvent.KEYCODE_P: return 25;
            case KeyEvent.KEYCODE_Q: return 16; case KeyEvent.KEYCODE_R: return 19;
            case KeyEvent.KEYCODE_S: return 31; case KeyEvent.KEYCODE_T: return 20;
            case KeyEvent.KEYCODE_U: return 22; case KeyEvent.KEYCODE_V: return 47;
            case KeyEvent.KEYCODE_W: return 17; case KeyEvent.KEYCODE_X: return 45;
            case KeyEvent.KEYCODE_Y: return 21; case KeyEvent.KEYCODE_Z: return 44;
            case KeyEvent.KEYCODE_1: return 2;  case KeyEvent.KEYCODE_2: return 3;
            case KeyEvent.KEYCODE_3: return 4;  case KeyEvent.KEYCODE_4: return 5;
            case KeyEvent.KEYCODE_5: return 6;  case KeyEvent.KEYCODE_6: return 7;
            case KeyEvent.KEYCODE_7: return 8;  case KeyEvent.KEYCODE_8: return 9;
            case KeyEvent.KEYCODE_9: return 10; case KeyEvent.KEYCODE_0: return 11;
            case KeyEvent.KEYCODE_ENTER: return 28; case KeyEvent.KEYCODE_NUMPAD_ENTER: return 28;
            case KeyEvent.KEYCODE_SPACE: return 57; case KeyEvent.KEYCODE_TAB: return 15;
            case KeyEvent.KEYCODE_DEL: return 14;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111; case KeyEvent.KEYCODE_ESCAPE: return 1;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42; case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29; case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;
            case KeyEvent.KEYCODE_ALT_LEFT: return 56; case KeyEvent.KEYCODE_ALT_RIGHT: return 100;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 58;
            case KeyEvent.KEYCODE_DPAD_UP: return 103; case KeyEvent.KEYCODE_DPAD_DOWN: return 108;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105; case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_MOVE_HOME: return 102; case KeyEvent.KEYCODE_MOVE_END: return 107;
            case KeyEvent.KEYCODE_PAGE_UP: return 104; case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_INSERT: return 110;
            case KeyEvent.KEYCODE_GRAVE: return 41; case KeyEvent.KEYCODE_MINUS: return 12;
            case KeyEvent.KEYCODE_EQUALS: return 13; case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 27; case KeyEvent.KEYCODE_BACKSLASH: return 43;
            case KeyEvent.KEYCODE_SEMICOLON: return 39; case KeyEvent.KEYCODE_APOSTROPHE: return 40;
            case KeyEvent.KEYCODE_SLASH: return 53; case KeyEvent.KEYCODE_COMMA: return 51;
            case KeyEvent.KEYCODE_PERIOD: return 52;
            case KeyEvent.KEYCODE_F1: return 59; case KeyEvent.KEYCODE_F2: return 60;
            case KeyEvent.KEYCODE_F3: return 61; case KeyEvent.KEYCODE_F4: return 62;
            case KeyEvent.KEYCODE_F5: return 63; case KeyEvent.KEYCODE_F6: return 64;
            case KeyEvent.KEYCODE_F7: return 65; case KeyEvent.KEYCODE_F8: return 66;
            case KeyEvent.KEYCODE_F9: return 67; case KeyEvent.KEYCODE_F10: return 68;
            case KeyEvent.KEYCODE_F11: return 87; case KeyEvent.KEYCODE_F12: return 88;
            default: return -1;
        }
    }

    public static int unshiftedChar(int ch) {
        if (ch >= 'A' && ch <= 'Z') return Character.toLowerCase(ch);
        int at = SHIFTED_CHARS.indexOf(ch);
        return at >= 0 ? PLAIN_CHARS.charAt(at) : 0;
    }

    private static final String SHIFTED_CHARS = "!@#$%^&*()_+{}|:\"<>?~";
    private static final String PLAIN_CHARS   = "1234567890-=[]\\;',./`";

    public static int keycodeForChar(int ch) {
        if (ch >= 'a' && ch <= 'z') return KeyEvent.KEYCODE_A + (ch - 'a');
        if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
        switch (ch) {
            case '-':  return KeyEvent.KEYCODE_MINUS;
            case '=':  return KeyEvent.KEYCODE_EQUALS;
            case '[':  return KeyEvent.KEYCODE_LEFT_BRACKET;
            case ']':  return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case '\\': return KeyEvent.KEYCODE_BACKSLASH;
            case ';':  return KeyEvent.KEYCODE_SEMICOLON;
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case ',':  return KeyEvent.KEYCODE_COMMA;
            case '.':  return KeyEvent.KEYCODE_PERIOD;
            case '/':  return KeyEvent.KEYCODE_SLASH;
            case '`':  return KeyEvent.KEYCODE_GRAVE;
            case ' ':  return KeyEvent.KEYCODE_SPACE;
            default:   return 0;
        }
    }
}
