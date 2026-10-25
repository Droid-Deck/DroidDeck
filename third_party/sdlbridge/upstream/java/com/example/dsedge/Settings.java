package com.example.dsedge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User settings, shared by the screen and the background service (same process).
 * Gyro / touchpad / back-button remap / lightbar are per player (0..3); the rest are global.
 */
final class Settings {
    static final int PLAYERS = 4;

    static final int TOUCH_SPLIT = 0, TOUCH_SINGLE = 1, TOUCH_OFF = 2;
    static final String[] TOUCH_NAMES = {
        "Split (decoupled): left half = left trackpad, right half = right trackpad",
        "Single: whole touchpad = right trackpad",
        "Off"
    };

    /** Remap targets: 4 Deck grips + the two trackpads' touch / click. */
    static final String[] REMAP_TARGETS = {
        "L4", "R4", "L5", "R5",
        "Left trackpad click", "Right trackpad click",
        "Left trackpad touch", "Right trackpad touch"
    };
    static final int REMAP_COUNT = 8;
    static final int[] TARGET_BITS = { 19, 20, 21, 22 };   // grip targets (first 4) -> mask bits

    /** Any controller button can be a source. sel: -1 none, 0 = main mask, 1 = extra mask (MISC2..6). */
    static final String[] SRC_NAMES = {
        "None",
        "A / Cross", "B / Circle", "X / Square", "Y / Triangle",
        "L1", "R1", "L2", "R2",
        "Create / Back / Share", "Options / Start / Menu", "L3", "R3",
        "Guide / PS", "Touchpad click", "Mute / Misc1",
        "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right",
        "Left paddle (L4)", "Right paddle (R4)", "Left Fn (L5)", "Right Fn (R5)",
        "Extra C / Misc2", "Extra Z / Misc3", "Misc4", "Misc5", "Misc6"
    };
    static final int[] SRC_SEL = { -1,  0,0,0,0,  0,0,0,0,  0,0,0,0,  0,0,0,  0,0,0,0,  0,0,0,0,  1,1,1,1,1 };
    static final int[] SRC_BIT = { -1,  0,1,2,3,  4,5,6,7,  8,9,10,11, 12,13,14, 15,16,17,18, 19,20,21,22, 0,1,2,3,4 };
    static final String[] PLAYER_NAMES = { "Off", "Player 1", "Player 2", "Player 3", "Player 4" };
    static final String[] AXIS_NAMES = { "Controller X (pitch)", "Controller Y (yaw)", "Controller Z (roll)" };
    static final String[] GYRO_INV_NAMES = { "None", "X", "Y", "Z", "X + Y", "X + Z", "Y + Z", "X + Y + Z" };

    // ---- per-player settings (index 0..3) ----
    static final int[] touchMode = fill(TOUCH_SPLIT);
    static final int[][] remap = new int[PLAYERS][REMAP_COUNT];
    static final boolean[] touchInvX = new boolean[PLAYERS];
    static final boolean[] touchInvY = new boolean[PLAYERS];
    static final boolean[] touchSwap = new boolean[PLAYERS];
    static final int[] gyroGain = fill(100);
    static final int[] gyroInvert = new int[PLAYERS];
    static final int[] gyroDeadzone = new int[PLAYERS];
    static final float[][] gyroBias = new float[PLAYERS][3];
    static final int[][] gyroMap = new int[PLAYERS][3];
    static final boolean[] accelFollows = fill(true);
    static final int[] lightR = new int[PLAYERS];
    static final int[] lightG = new int[PLAYERS];
    static final int[] lightB = fill(255);
    static final int[] brightness = fill(100);
    static final int[] playerLed = new int[PLAYERS];

    // ---- global settings ----
    // Auto by default: any SDL3-supported controller is used -- through SDL3's own USB driver when possible
    // (full features), otherwise through Android's input (e.g. Bluetooth). An Android duplicate of a controller
    // SDL3 already drives over USB is still ignored, so nothing is doubled.
    static volatile boolean forceUsb = false;
    static volatile boolean driverEnabled = true;
    static volatile int presentAs = 0;
    static final String[] PRESENT_NAMES = { "Steam Deck (default)", "DualSense (native)", "DualSense Edge (native)", "DualShock 4 (native)",
                                            "Steam Controller 2026 (native)", "Steam Controller 2015 (native)",
                                            "Steam Native Hook-up (Steam sees the real controller)" };
    // DroidDeck pad types: 0 Deck, 4 DualSense, 5 DualSense Edge, 3 DualShock 4, 1 Steam Controller 2026 (28de:1302),
    // 6 Steam Controller 2015 (28de:1102)
    // Hook-up hands USB controllers to Steam as themselves; any controller it can't (Bluetooth) stays a Deck pad.
    static final int[] PRESENT_PAD_TYPE = { 0, 4, 5, 3, 1, 6, 0 };
    static final int PRESENT_HOOKUP = 6;
    static boolean isHookup() { return presentAs == PRESENT_HOOKUP; }
    /** The "Present controller as" choice to go back to when hook-up is switched off from the bubble. */
    static volatile int lastPresentAs = 0;
    /** Was the SDL3 driver on before the bubble switched hook-up on? Switching hook-up off goes back to it. */
    static volatile boolean driverBeforeHookup = true;
    static final int PAD_TYPE_STEAM_2015 = 6;
    static boolean isSonyPadType(int t) { return t >= 3 && t <= 5; }
    static volatile int pollMs = 2;
    static final String[] RATE_NAMES = { "250 Hz (low CPU)", "500 Hz (recommended)", "1000 Hz (max)" };
    static final int[] RATE_MS = { 4, 2, 1 };
    static volatile boolean bubble = false;
    static volatile int bubbleX = 24, bubbleY = 160;

    private static SharedPreferences prefs;

    private Settings() {}

    private static int[] fill(int v) { int[] a = new int[PLAYERS]; java.util.Arrays.fill(a, v); return a; }
    private static boolean[] fill(boolean v) { boolean[] a = new boolean[PLAYERS]; java.util.Arrays.fill(a, v); return a; }

    static synchronized void init(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences(Host.prefs(c, "bridge"), Context.MODE_PRIVATE);
        for (int p = 0; p < PLAYERS; p++) {
            String s = "_p" + p;
            touchMode[p] = clamp(prefs.getInt("touchMode" + s, TOUCH_SPLIT), 0, 2);
            for (int i = 0; i < REMAP_COUNT; i++) remap[p][i] = clamp(prefs.getInt("remap" + i + s, 0), 0, SRC_NAMES.length - 1);
            touchInvX[p] = prefs.getBoolean("touchInvX" + s, false);
            touchInvY[p] = prefs.getBoolean("touchInvY" + s, false);
            touchSwap[p] = prefs.getBoolean("touchSwap" + s, false);
            gyroGain[p] = clamp(prefs.getInt("gyroGain" + s, 100), 1, 800);
            gyroInvert[p] = clamp(prefs.getInt("gyroInvert" + s, 0), 0, 7);
            gyroDeadzone[p] = clamp(prefs.getInt("gyroDeadzone" + s, 0), 0, 50);
            gyroBias[p][0] = prefs.getFloat("gyroBiasX" + s, 0f);
            gyroBias[p][1] = prefs.getFloat("gyroBiasY" + s, 0f);
            gyroBias[p][2] = prefs.getFloat("gyroBiasZ" + s, 0f);
            for (int i = 0; i < 3; i++) gyroMap[p][i] = clamp(prefs.getInt("gyroMap" + i + s, i), 0, 2);
            accelFollows[p] = prefs.getBoolean("accelFollows" + s, true);
            lightR[p] = clamp(prefs.getInt("lightR" + s, 0), 0, 255);
            lightG[p] = clamp(prefs.getInt("lightG" + s, 0), 0, 255);
            lightB[p] = clamp(prefs.getInt("lightB" + s, 255), 0, 255);
            brightness[p] = clamp(prefs.getInt("brightness" + s, 100), 0, 100);
            playerLed[p] = clamp(prefs.getInt("playerLed" + s, 0), 0, 4);
        }
        forceUsb = prefs.getBoolean("forceUsbV2", false);
        driverEnabled = prefs.getBoolean("driverEnabled", true);
        presentAs = clamp(prefs.getInt("presentAs", 0), 0, PRESENT_NAMES.length - 1);
        lastPresentAs = clamp(prefs.getInt("lastPresentAs", 0), 0, PRESENT_NAMES.length - 1);
        if (lastPresentAs == PRESENT_HOOKUP) lastPresentAs = 0;
        driverBeforeHookup = prefs.getBoolean("driverBeforeHookup", true);
        pollMs = clamp(prefs.getInt("pollMs", 2), 1, 16);
        bubble = prefs.getBoolean("bubble", false);
        bubbleX = prefs.getInt("bubbleX", 24);
        bubbleY = prefs.getInt("bubbleY", 160);
    }

    static void save() {
        SharedPreferences pr = prefs;
        if (pr == null) return;
        SharedPreferences.Editor e = pr.edit();
        for (int p = 0; p < PLAYERS; p++) {
            String s = "_p" + p;
            e.putInt("touchMode" + s, touchMode[p]);
            for (int i = 0; i < REMAP_COUNT; i++) e.putInt("remap" + i + s, remap[p][i]);
            e.putBoolean("touchInvX" + s, touchInvX[p]).putBoolean("touchInvY" + s, touchInvY[p]).putBoolean("touchSwap" + s, touchSwap[p]);
            e.putInt("gyroGain" + s, gyroGain[p]).putInt("gyroInvert" + s, gyroInvert[p]).putInt("gyroDeadzone" + s, gyroDeadzone[p]);
            e.putFloat("gyroBiasX" + s, gyroBias[p][0]).putFloat("gyroBiasY" + s, gyroBias[p][1]).putFloat("gyroBiasZ" + s, gyroBias[p][2]);
            for (int i = 0; i < 3; i++) e.putInt("gyroMap" + i + s, gyroMap[p][i]);
            e.putBoolean("accelFollows" + s, accelFollows[p]);
            e.putInt("lightR" + s, lightR[p]).putInt("lightG" + s, lightG[p]).putInt("lightB" + s, lightB[p]);
            e.putInt("brightness" + s, brightness[p]).putInt("playerLed" + s, playerLed[p]);
        }
        e.putBoolean("forceUsbV2", forceUsb);
        e.putBoolean("driverEnabled", driverEnabled);
        e.putInt("presentAs", presentAs);
        e.putInt("lastPresentAs", lastPresentAs);
        e.putBoolean("driverBeforeHookup", driverBeforeHookup);
        e.putInt("pollMs", pollMs);
        e.putBoolean("bubble", bubble).putInt("bubbleX", bubbleX).putInt("bubbleY", bubbleY);
        e.apply();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Is the source button [srcIndex] currently pressed, given the two button masks? */
    static boolean srcDown(int mask, int mask2, int srcIndex) {
        if (srcIndex <= 0 || srcIndex >= SRC_NAMES.length) return false;
        int m = SRC_SEL[srcIndex] == 1 ? mask2 : mask;
        return ((m >> SRC_BIT[srcIndex]) & 1) != 0;
    }

    /** Applies the 4 grip remaps (targets 0..3) to the mask for player [p]; a mapped source stops acting as itself. */
    static int applyGripRemap(int mask, int mask2, int p) {
        if (p < 0 || p >= PLAYERS) p = 0;
        int[] rm = remap[p];
        int add = 0, consumed = 0;
        for (int t = 0; t < 4; t++) {
            int srcIndex = rm[t];
            if (srcDown(mask, mask2, srcIndex)) {
                add |= 1 << TARGET_BITS[t];
                if (SRC_SEL[srcIndex] == 0) consumed |= 1 << SRC_BIT[srcIndex];
            }
        }
        return (mask | add) & ~consumed;
    }
}
