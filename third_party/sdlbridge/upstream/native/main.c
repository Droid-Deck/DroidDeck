/*
 * SDL3 controller loop. Reads the first gamepad (DualSense, DualSense Edge, Xbox, Switch Pro, ... anything SDL3
 * supports) and pushes frames to Java (NativeBridge.onFrame). Rumble/lightbar requests come back through
 * NativeBridge.nativeSetOutput.
 */
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define FRAME_LEN 22
#define MAX_PADS 4

static JavaVM *g_vm;
static jclass g_cls;
static jmethodID g_frame, g_status, g_gone, g_info, g_caps;

/* Per-slot output state, written from Java threads, read by the SDL thread. */
static _Atomic int g_rl[MAX_PADS], g_rr[MAX_PADS];
static _Atomic int g_rumble_dirty[MAX_PADS];
static _Atomic int g_led_dirty[MAX_PADS];
static _Atomic int g_led_r[MAX_PADS], g_led_g[MAX_PADS], g_led_b[MAX_PADS];  /* per-slot lightbar colour */
static _Atomic int g_player_idx[MAX_PADS];     /* per-slot player LEDs: -1 = off, 0..3 = player 1..4 */
static _Atomic int g_player_dirty[MAX_PADS];

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "com/example/dsedge/NativeBridge");
    if (!c) return JNI_ERR;
    g_cls = (jclass)(*env)->NewGlobalRef(env, c);
    g_frame = (*env)->GetStaticMethodID(env, g_cls, "onFrame", "([FI)V");
    g_status = (*env)->GetStaticMethodID(env, g_cls, "onStatus", "(Ljava/lang/String;)V");
    g_gone = (*env)->GetStaticMethodID(env, g_cls, "onPadGone", "(I)V");
    g_info = (*env)->GetStaticMethodID(env, g_cls, "onPadInfo", "(IIILjava/lang/String;)V");
    g_caps = (*env)->GetStaticMethodID(env, g_cls, "onPadCaps", "(IIIILjava/lang/String;)V");
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* Slot 0: rumble + lightbar colour (the user's light settings). */
JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetLed(
    JNIEnv *env, jclass cls, jint slot, jint r, jint g, jint b)
{
    (void)env; (void)cls;
    if (slot < 0 || slot >= MAX_PADS) return;
    atomic_store(&g_led_r[slot], r); atomic_store(&g_led_g[slot], g); atomic_store(&g_led_b[slot], b);
    atomic_store(&g_led_dirty[slot], 1);
}

/* Rumble for any slot (0..3). */
JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetRumble(
    JNIEnv *env, jclass cls, jint slot, jint rl, jint rr)
{
    (void)env; (void)cls;
    if (slot < 0 || slot >= MAX_PADS) return;
    atomic_store(&g_rl[slot], rl); atomic_store(&g_rr[slot], rr);
    atomic_store(&g_rumble_dirty[slot], 1);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetPlayerLed(JNIEnv *env, jclass cls, jint slot, jint index)
{
    (void)env; (void)cls;
    if (slot < 0 || slot >= MAX_PADS) return;
    atomic_store(&g_player_idx[slot], index);
    atomic_store(&g_player_dirty[slot], 1);
}

static atomic_int g_force = 1;   /* 1 = only accept pads served by SDL's own USB (HIDAPI) driver */
static atomic_int g_rescan = 0;

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetForce(JNIEnv *env, jclass c, jint on)
{
    (void)env; (void)c;
    atomic_store(&g_force, on ? 1 : 0);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeRescan(JNIEnv *env, jclass c)
{
    (void)env; (void)c;
    atomic_store(&g_rescan, 1);
}

static atomic_int g_driver_off = 0;   /* 1 = SDL3 (HIDAPI) driver disabled: release the controller to Android */
static atomic_int g_poll_ms = 2;      /* loop period: 2ms = 500Hz (lower = lower latency, more CPU) */

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetPollMs(JNIEnv *env, jclass c, jint ms)
{
    (void)env; (void)c;
    if (ms < 1) ms = 1;
    if (ms > 16) ms = 16;
    atomic_store(&g_poll_ms, ms);
}

/* Hard on/off for the SDL3 driver. Off disables HIDAPI and releases the controller; on re-enables it. */
JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetDriver(JNIEnv *env, jclass c, jint on)
{
    (void)env; (void)c;
    atomic_store(&g_driver_off, on ? 0 : 1);
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI, on ? "1" : "0");
    atomic_store(&g_rescan, 1);
}

/* A line in DroidDeck's session log ("sdl3bridge: ...") through NativeBridge.onBridgeLog. */
static void engine_log(JNIEnv *env, const char *msg)
{
    SDL_Log("%s", msg);
    jmethodID m = (*env)->GetStaticMethodID(env, g_cls, "onBridgeLog", "(Ljava/lang/String;)V");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    jstring s = (*env)->NewStringUTF(env, msg);
    if (m && s) (*env)->CallStaticVoidMethod(env, g_cls, m, s);
    if (s) (*env)->DeleteLocalRef(env, s);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void send_status(JNIEnv *env, const char *msg)
{
    jstring s = (*env)->NewStringUTF(env, msg);
    (*env)->CallStaticVoidMethod(env, g_cls, g_status, s);
    (*env)->DeleteLocalRef(env, s);
}

static void send_gone(JNIEnv *env, int slot)
{
    (*env)->CallStaticVoidMethod(env, g_cls, g_gone, (jint)slot);
}

static void send_info(JNIEnv *env, int slot, int vid, int pid, const char *name)
{
    jstring s = (*env)->NewStringUTF(env, name ? name : "gamepad");
    (*env)->CallStaticVoidMethod(env, g_cls, g_info, (jint)slot, (jint)vid, (jint)pid, s);
    (*env)->DeleteLocalRef(env, s);
}

#include "triton.h"
#include "hookup.h"

/* SDL button -> bit in the frame mask. Bit layout is shared with the first bridge and DroidDeck's client. */
static const struct { SDL_GamepadButton button; int bit; } BUTTONS[] = {
    { SDL_GAMEPAD_BUTTON_SOUTH, 0 },          /* Cross */
    { SDL_GAMEPAD_BUTTON_EAST, 1 },           /* Circle */
    { SDL_GAMEPAD_BUTTON_WEST, 2 },           /* Square */
    { SDL_GAMEPAD_BUTTON_NORTH, 3 },          /* Triangle */
    { SDL_GAMEPAD_BUTTON_LEFT_SHOULDER, 4 },
    { SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER, 5 },
    { SDL_GAMEPAD_BUTTON_BACK, 8 },           /* Create */
    { SDL_GAMEPAD_BUTTON_START, 9 },          /* Options */
    { SDL_GAMEPAD_BUTTON_LEFT_STICK, 10 },
    { SDL_GAMEPAD_BUTTON_RIGHT_STICK, 11 },
    { SDL_GAMEPAD_BUTTON_GUIDE, 12 },         /* PS */
    { SDL_GAMEPAD_BUTTON_TOUCHPAD, 13 },
    { SDL_GAMEPAD_BUTTON_MISC1, 14 },         /* Mute */
    { SDL_GAMEPAD_BUTTON_DPAD_UP, 15 },
    { SDL_GAMEPAD_BUTTON_DPAD_DOWN, 16 },
    { SDL_GAMEPAD_BUTTON_DPAD_LEFT, 17 },
    { SDL_GAMEPAD_BUTTON_DPAD_RIGHT, 18 },
    { SDL_GAMEPAD_BUTTON_LEFT_PADDLE1, 19 },  /* Edge left back button  */
    { SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1, 20 }, /* Edge right back button */
    { SDL_GAMEPAD_BUTTON_LEFT_PADDLE2, 21 },  /* Edge left Fn  */
    { SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2, 22 }, /* Edge right Fn */
};

/* Extra buttons that don't fit the 0..22 mask safely in a float -> a second mask in f[21]. */
static const struct { SDL_GamepadButton button; int bit; } BUTTONS2[] = {
    { SDL_GAMEPAD_BUTTON_MISC2, 0 },   /* e.g. Flydigi C */
    { SDL_GAMEPAD_BUTTON_MISC3, 1 },   /* e.g. Flydigi Z */
    { SDL_GAMEPAD_BUTTON_MISC4, 2 },
    { SDL_GAMEPAD_BUTTON_MISC5, 3 },
    { SDL_GAMEPAD_BUTTON_MISC6, 4 },
};

static float stick(Sint16 v) { return (float)(((int)v + 32768) >> 8); }                /* 0..255, 128 = centre */
static float trigger(Sint16 v) { return (float)((int)v * 255 / 32767); }               /* 0..255 */

static void send_frame(JNIEnv *env, int slot, SDL_Gamepad *pad, jfloatArray arr)
{
    float f[FRAME_LEN];
    memset(f, 0, sizeof(f));

    int mask = 0;
    for (size_t i = 0; i < SDL_arraysize(BUTTONS); i++) {
        if (SDL_GetGamepadButton(pad, BUTTONS[i].button)) mask |= 1 << BUTTONS[i].bit;
    }
    f[1] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTX));
    f[2] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTY));
    f[3] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHTX));
    f[4] = stick(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHTY));
    f[5] = trigger(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER));
    f[6] = trigger(SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER));
    if (f[5] > 30.0f) mask |= 1 << 6;
    if (f[6] > 30.0f) mask |= 1 << 7;
    f[0] = (float)mask; /* < 2^24, exact in a float */
    int mask2 = 0;
    for (size_t i = 0; i < SDL_arraysize(BUTTONS2); i++) {
        if (SDL_GetGamepadButton(pad, BUTTONS2[i].button)) mask2 |= 1 << BUTTONS2[i].bit;
    }
    f[21] = (float)mask2;

    float v[3];
    if (SDL_GetGamepadSensorData(pad, SDL_SENSOR_GYRO, v, 3)) { f[7] = v[0]; f[8] = v[1]; f[9] = v[2]; }
    if (SDL_GetGamepadSensorData(pad, SDL_SENSOR_ACCEL, v, 3)) { f[10] = v[0]; f[11] = v[1]; f[12] = v[2]; }

    int ntp = SDL_GetNumGamepadTouchpads(pad);
    if (ntp >= 2) {
        /* two trackpads (e.g. Steam Controller): finger slot 0 = pad 0, slot 1 = pad 1 */
        for (int tp = 0; tp < 2; tp++) {
            bool down = false; float x = 0, y = 0, p = 0;
            if (SDL_GetGamepadTouchpadFinger(pad, tp, 0, &down, &x, &y, &p) && down) {
                f[13 + 3 * tp] = 1.0f;
                f[14 + 3 * tp] = x * 1919.0f;
                f[15 + 3 * tp] = y * 1079.0f;
            }
        }
    } else if (ntp == 1) {
        for (int i = 0; i < 2; i++) {
            bool down = false;
            float x = 0, y = 0, p = 0;
            if (SDL_GetGamepadTouchpadFinger(pad, 0, i, &down, &x, &y, &p) && down) {
                f[13 + 3 * i] = 1.0f;
                f[14 + 3 * i] = x * 1919.0f;   /* normalized; the view divides by the same */
                f[15 + 3 * i] = y * 1079.0f;
            }
        }
    }

    int pct = -1;
    SDL_PowerState ps = SDL_GetGamepadPowerInfo(pad, &pct);
    f[19] = (float)pct;
    f[20] = (float)ps;

    (*env)->SetFloatArrayRegion(env, arr, 0, FRAME_LEN, f);
    (*env)->CallStaticVoidMethod(env, g_cls, g_frame, arr, (jint)slot);
}

/* SDL's own USB driver gives the gamepad a device path; Android's basic input path does not.
 * Only the USB driver provides gyro, touchpad and the Edge back buttons. */
static bool pad_is_full_driver(SDL_Gamepad *pad) { return SDL_GetGamepadPath(pad) != NULL; }

static void enable_sensors(SDL_Gamepad *pad)
{
    static const SDL_SensorType types[] = { SDL_SENSOR_GYRO, SDL_SENSOR_ACCEL };
    for (size_t i = 0; i < SDL_arraysize(types); i++) {
        if (SDL_GamepadHasSensor(pad, types[i]) && !SDL_GamepadSensorEnabled(pad, types[i])) {
            SDL_SetGamepadSensorEnabled(pad, types[i], true);
        }
    }
}

static void describe(SDL_Gamepad *pad, char *buf, size_t n)
{
    const char *name = SDL_GetGamepadName(pad);
    SDL_JoystickConnectionState cs = SDL_GetGamepadConnectionState(pad);
    snprintf(buf, n, "Connected: %s [%s] vid=%04x pid=%04x %s | gyro:%s accel:%s touchpads:%d",
             name ? name : "gamepad",
             pad_is_full_driver(pad) ? "SDL3 USB driver: full features"
                                     : "Android input (e.g. Bluetooth): SDL3's own driver can't reach it, some extras may be missing",
             (unsigned)SDL_GetGamepadVendor(pad), (unsigned)SDL_GetGamepadProduct(pad),
             cs == SDL_JOYSTICK_CONNECTION_WIRED ? "wired" : (cs == SDL_JOYSTICK_CONNECTION_WIRELESS ? "wireless" : "link unknown"),
             SDL_GamepadHasSensor(pad, SDL_SENSOR_GYRO) ? "yes" : "no",
             SDL_GamepadHasSensor(pad, SDL_SENSOR_ACCEL) ? "yes" : "no",
             SDL_GetNumGamepadTouchpads(pad));

    /* Every gamepad SDL knows about, so we can tell if the USB-driver version of the controller exists. */
    int cnt = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&cnt);
    SDL_strlcat(buf, " | SDL sees:", n);
    for (int i = 0; ids && i < cnt; i++) {
        const char *nm = SDL_GetGamepadNameForID(ids[i]);
        char one[120];
        snprintf(one, sizeof(one), " [%s: %s]", SDL_GetGamepadPathForID(ids[i]) ? "usb driver" : "android", nm ? nm : "?");
        SDL_strlcat(buf, one, n);
    }
    if (cnt == 0) SDL_strlcat(buf, " nothing", n);
    SDL_free(ids);
}

static char g_last_diag[512];

/* In forced mode an Android-path gamepad (no SDL USB path) is never used. */
static bool usable_id(SDL_JoystickID id)
{
    /* A Steam Controller (2026) is never a bridge player: it is passed through raw (see triton.h). */
    if (SDL_GetGamepadVendorForID(id) == VALVE_VID && triton_pid(SDL_GetGamepadProductForID(id))) return false;
    return !atomic_load(&g_force) || SDL_GetGamepadPathForID(id) != NULL;
}

/* The same physical controller can show up twice: once through SDL's USB driver and once through Android's basic
 * input. The Android copy is a duplicate and is never used as its own player. */
static bool android_duplicate(SDL_JoystickID id)
{
    if (SDL_GetGamepadPathForID(id) != NULL) return false;
    Uint16 vid = SDL_GetGamepadVendorForID(id), pid = SDL_GetGamepadProductForID(id);
    int cnt = 0, full = 0, rank = 0;
    bool seen = false;
    SDL_JoystickID *ids = SDL_GetGamepads(&cnt);
    for (int i = 0; ids && i < cnt; i++) {
        if (SDL_GetGamepadVendorForID(ids[i]) != vid || SDL_GetGamepadProductForID(ids[i]) != pid) continue;
        if (SDL_GetGamepadPathForID(ids[i]) != NULL) { full++; continue; }
        if (ids[i] == id) seen = true;
        else if (!seen) rank++;
    }
    SDL_free(ids);
    return rank < full;
}

static SDL_Gamepad *g_pads[MAX_PADS];
static SDL_JoystickID g_ids[MAX_PADS];

static int rumble_on[MAX_PADS];

static int pad_count(void)
{
    int n = 0;
    for (int i = 0; i < MAX_PADS; i++) if (g_pads[i]) n++;
    return n;
}

static int slot_of(SDL_JoystickID id)
{
    for (int i = 0; i < MAX_PADS; i++) if (g_pads[i] && g_ids[i] == id) return i;
    return -1;
}

static void refresh_status(JNIEnv *env)
{
    char diag[560];
    if (atomic_load(&g_driver_off)) {
        snprintf(diag, sizeof(diag), "SDL3 driver OFF \u2014 controller released to Android (tap the bubble to turn it back on)");
    } else if (g_pads[0]) {
        describe(g_pads[0], diag, sizeof(diag));
        char tail[80];
        snprintf(tail, sizeof(tail), " | players:%d%s", pad_count(),
                 triton_active() ? " | + Steam Controller (2026) raw passthrough" : "");
        SDL_strlcat(diag, tail, sizeof(diag));
        if (hookup_active()) {
            snprintf(tail, sizeof(tail), " | + %d controller(s) handed to Steam natively%s", hookup_count(),
                     hookup_fast_count() ? " (fast path)" : "");
            SDL_strlcat(diag, tail, sizeof(diag));
        } else if (atomic_load(&g_hookup_held)) {
            SDL_strlcat(diag, " | Steam Native Hook-up waits for Steam on DroidDeck's desktop", sizeof(diag));
        }
    } else if (hookup_active()) {
        snprintf(diag, sizeof(diag), "Connected: Steam Native Hook-up | %d controller(s) handed to Steam as the real "
                 "device (Steam's own driver: all buttons, touchpads, gyro, rumble, lights)%s%s", hookup_count(),
                 hookup_fast_count() ? " | fast path: shared memory" : "",
                 triton_active() ? " | + Steam Controller (2026) raw passthrough" : "");
    } else if (triton_active()) {
        snprintf(diag, sizeof(diag), "Connected: %s | raw passthrough to DroidDeck (all sensors, capacitive "
                 "touch, grip sense, trackpad pressure and haptics handled by Steam)", tri_kind(&g_tri[g_tri_active]));
    } else {
        int cnt = 0, ignored = 0;
        SDL_JoystickID *ids = SDL_GetGamepads(&cnt);
        for (int i = 0; ids && i < cnt; i++) if (!usable_id(ids[i])) ignored++;
        SDL_free(ids);
        snprintf(diag, sizeof(diag),
                 "Forced SDL3 USB mode: waiting for the USB driver. Plug the controller in with a USB-C cable, "
                 "then tap 'Force SDL3 USB driver' and allow USB access (or switch Controller source to Auto to "
                 "also use Bluetooth / Android-only controllers). Ignored Android-only controllers: %d", ignored);
        if (!atomic_load(&g_force))
            snprintf(diag, sizeof(diag), "Waiting for a controller: plug any SDL3-supported controller in via USB-C "
                     "(full features) or connect it over Bluetooth");
    }
    if (strcmp(diag, g_last_diag) != 0) {
        SDL_strlcpy(g_last_diag, diag, sizeof(g_last_diag));
        send_status(env, diag);
    }
}

/* Which buttons/axes/touchpads this controller actually has, so the UI can draw a universal layout. */
static void send_caps(JNIEnv *env, int slot, SDL_Gamepad *pad)
{
    int present = 0;
    for (size_t i = 0; i < SDL_arraysize(BUTTONS); i++) {
        if (SDL_GamepadHasButton(pad, BUTTONS[i].button)) present |= 1 << BUTTONS[i].bit;
    }
    int axes = 0;
    if (SDL_GamepadHasAxis(pad, SDL_GAMEPAD_AXIS_LEFTX))         axes |= 1;
    if (SDL_GamepadHasAxis(pad, SDL_GAMEPAD_AXIS_RIGHTX))        axes |= 2;
    if (SDL_GamepadHasAxis(pad, SDL_GAMEPAD_AXIS_LEFT_TRIGGER))  axes |= 4;
    if (SDL_GamepadHasAxis(pad, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER)) axes |= 8;
    int touchpads = SDL_GetNumGamepadTouchpads(pad);

    const char *kind;
    switch (SDL_GetGamepadType(pad)) {
        case SDL_GAMEPAD_TYPE_PS3:
        case SDL_GAMEPAD_TYPE_PS4:
        case SDL_GAMEPAD_TYPE_PS5: kind = "ps"; break;
        case SDL_GAMEPAD_TYPE_XBOX360:
        case SDL_GAMEPAD_TYPE_XBOXONE: kind = "xbox"; break;
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_PRO:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_LEFT:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_RIGHT:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_PAIR: kind = "nintendo"; break;
        case SDL_GAMEPAD_TYPE_STEAM: kind = "steam"; break;
        default: {
            const char *nm = SDL_GetGamepadName(pad);
            kind = (nm && SDL_strcasestr(nm, "steam")) ? "steam" : "generic";
        }
    }
    jstring k = (*env)->NewStringUTF(env, kind);
    (*env)->CallStaticVoidMethod(env, g_cls, g_caps, (jint)slot, (jint)present, (jint)axes, (jint)touchpads, k);
    (*env)->DeleteLocalRef(env, k);
}

static void setup_slot(JNIEnv *env, int slot)
{
    SDL_Gamepad *pad = g_pads[slot];
    enable_sensors(pad);
    atomic_store(&g_led_dirty[slot], 1);
    atomic_store(&g_rumble_dirty[slot], 1);
    atomic_store(&g_player_dirty[slot], 1);
    send_info(env, slot, SDL_GetGamepadVendor(pad), SDL_GetGamepadProduct(pad), SDL_GetGamepadName(pad));
    send_caps(env, slot, pad);
    g_last_diag[0] = 0;
}

static void open_unopened(JNIEnv *env)
{
    if (atomic_load(&g_driver_off)) return;   /* driver off: don't claim anything */
    int n = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&n);
    for (int i = 0; ids && i < n; i++) {
        if (slot_of(ids[i]) >= 0 || !usable_id(ids[i]) || android_duplicate(ids[i])) continue;
        int free_slot = -1;
        for (int s = 0; s < MAX_PADS; s++) if (!g_pads[s]) { free_slot = s; break; }
        if (free_slot < 0) break;
        SDL_Gamepad *p = SDL_OpenGamepad(ids[i]);
        if (!p) continue;
        g_pads[free_slot] = p;
        g_ids[free_slot] = ids[i];
        setup_slot(env, free_slot);
    }
    SDL_free(ids);
}

/* Closes a slot. If it was player 1, the next controller moves up so DroidDeck's primary pad is never empty. */
static void close_slot(JNIEnv *env, int slot)
{
    if (!g_pads[slot]) return;
    SDL_RumbleGamepad(g_pads[slot], 0, 0, 0);
    SDL_CloseGamepad(g_pads[slot]);
    g_pads[slot] = NULL;
    atomic_store(&g_rl[slot], 0);
    atomic_store(&g_rr[slot], 0);
    send_gone(env, slot);
    if (slot == 0) {
        for (int k = 1; k < MAX_PADS; k++) {
            if (!g_pads[k]) continue;
            g_pads[0] = g_pads[k];
            g_ids[0] = g_ids[k];
            g_pads[k] = NULL;
            send_gone(env, k);
            setup_slot(env, 0);
            break;
        }
    }
    g_last_diag[0] = 0;
}

/* Drops controllers that are no longer usable (forced mode switched on, or an Android duplicate of a USB-driver pad). */
static void drop_unusable(JNIEnv *env)
{
    for (int s = 0; s < MAX_PADS; s++) {
        if (!g_pads[s]) continue;
        if (!usable_id(g_ids[s]) || android_duplicate(g_ids[s])) { rumble_on[s] = 0; close_slot(env, s); }
    }
}

/* Close every bridge player and restart SDL's gamepad layer (re-enumerates; ignore hints take effect).
 * Raw handles (Steam Controller passthrough, Steam Native Hook-up) survive: main() holds an SDL_hid_init
 * reference so the HID layer itself stays up. */
static void restart_gamepads(JNIEnv *env)
{
    for (int s = 0; s < MAX_PADS; s++) {
        if (g_pads[s]) { SDL_CloseGamepad(g_pads[s]); g_pads[s] = NULL; send_gone(env, s); }
        rumble_on[s] = 0;
    }
    SDL_QuitSubSystem(SDL_INIT_GAMEPAD);
    SDL_InitSubSystem(SDL_INIT_GAMEPAD);
    g_last_diag[0] = 0;
}

/* SDL only reports a joystick as a gamepad when it has a mapping. SDL ships mappings for thousands of
 * controllers and Android generates one for every Android gamepad, but a rare generic one (arcade stick,
 * cheap USB pad) may have none -- give it a sensible default layout so it still works as a player. */
static void map_unmapped_joystick(SDL_JoystickID id)
{
    if (SDL_IsGamepad(id)) return;
    SDL_Joystick *joy = SDL_OpenJoystick(id);
    if (!joy) return;
    int nb = SDL_GetNumJoystickButtons(joy), na = SDL_GetNumJoystickAxes(joy), nh = SDL_GetNumJoystickHats(joy);
    char guid[64], map[1024];
    SDL_GUIDToString(SDL_GetJoystickGUID(joy), guid, sizeof(guid));
    const char *name = SDL_GetJoystickName(joy);
    char clean[128];
    SDL_strlcpy(clean, name ? name : "Generic controller", sizeof(clean));
    for (char *c = clean; *c; c++) if (*c == ',') *c = ' ';
    SDL_CloseJoystick(joy);
    if (nb == 0 && na == 0) return;
    snprintf(map, sizeof(map), "%s,%s,", guid, clean);
    static const char *btn[] = { "a", "b", "x", "y", "leftshoulder", "rightshoulder", "back", "start",
                                 "leftstick", "rightstick", "guide", "lefttrigger", "righttrigger" };
    char one[48];
    int first_trigger_button = na >= 6 ? 11 : 13;   /* analog triggers when there are 6+ axes */
    for (int b = 0; b < nb && b < (int)SDL_arraysize(btn); b++) {
        if (b >= first_trigger_button) break;
        snprintf(one, sizeof(one), "%s:b%d,", btn[b], b);
        SDL_strlcat(map, one, sizeof(map));
    }
    if (na < 6) {
        for (int b = 11; b < nb && b < 13; b++) {
            snprintf(one, sizeof(one), "%s:b%d,", btn[b], b);
            SDL_strlcat(map, one, sizeof(map));
        }
    }
    static const char *ax[] = { "leftx", "lefty", "rightx", "righty", "lefttrigger", "righttrigger" };
    for (int a = 0; a < na && a < 6; a++) {
        snprintf(one, sizeof(one), "%s:a%d,", ax[a], a);
        SDL_strlcat(map, one, sizeof(map));
    }
    if (nh > 0) SDL_strlcat(map, "dpup:h0.1,dpright:h0.2,dpdown:h0.4,dpleft:h0.8,", sizeof(map));
    if (SDL_AddGamepadMapping(map) >= 0) SDL_Log("bridge: default mapping for unmapped \"%s\": %s", clean, map);
}

int main(int argc, char *argv[])
{
    /* argv[1]: SDL3's gamepad mappings file (absolute path, made by the app). Without it SDL3 looks the file up
     * through the app screen's assets -- headless there is no screen, and that lookup crashes the app. */
    if (argc > 1 && argv[1] && argv[1][0] == '/') SDL_SetHint(SDL_HINT_GAMECONTROLLERCONFIG_FILE, argv[1]);
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_ENHANCED_REPORTS, "1");
    SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS5, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS4, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS5_PLAYER_LED, "1");
    /* Every SDL3 USB (HIDAPI) driver on, including Valve's (Steam Controller 2015 cable/dongle; it is off by
     * default in SDL). Only the Steam Controller (2026) is kept away from SDL's gamepad layer: it is passed
     * through raw instead (triton.h). That hint hides it from gamepads but not from SDL_hid_enumerate. */
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_STEAM, "1");
    SDL_SetHint(SDL_HINT_GAMECONTROLLER_IGNORE_DEVICES, "0x28de/0x1302,0x28de/0x1304,0x28de/0x1305");

    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    if (!env || !g_cls) {
        SDL_Log("SDL3Bridge: no JNI environment (%p) or bridge class (%p); SDL3's loop cannot run", (void *)env, (void *)g_cls);
        return 1;
    }
    engine_log(env, "engine: SDL3's loop started; initialising SDL3's gamepad support");

    if (!SDL_Init(SDL_INIT_GAMEPAD)) {
        char msg[200];
        snprintf(msg, sizeof(msg), "SDL init failed: %s", SDL_GetError());
        send_status(env, msg);
        return 1;
    }
    engine_log(env, "engine: SDL3's gamepad support is up");
    SDL_hid_init();   /* keeps the HID layer alive across gamepad-layer restarts (raw passthrough handles) */
    send_status(env, "Waiting for a controller (plug it in via USB-C)");

    jfloatArray arr = (*env)->NewFloatArray(env, FRAME_LEN);
    Uint64 last_scan = 0, last_tick = 0, last_rumble[MAX_PADS] = {0};

    for (;;) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_EVENT_JOYSTICK_ADDED) {
                map_unmapped_joystick(e.jdevice.which);
            } else if (e.type == SDL_EVENT_GAMEPAD_ADDED) {
                drop_unusable(env);
                open_unopened(env);
            } else if (e.type == SDL_EVENT_GAMEPAD_REMOVED) {
                int s = slot_of(e.gdevice.which);
                if (s >= 0) {
                    rumble_on[s] = 0;
                    close_slot(env, s);
                    if (pad_count() == 0) send_status(env, "Controller disconnected");
                }
            }
        }

        Uint64 now = SDL_GetTicks();

        if (atomic_exchange(&g_rescan, 0)) { /* restart SDL's gamepad/HIDAPI enumeration */
            tri_close_all(env, "rescan");
            restart_gamepads(env);
            send_status(env, "Rescanning controllers...");
        }

        if (now - last_scan > 1000) { /* fallback in case an ADDED event was missed, and housekeeping */
            last_scan = now;
            drop_unusable(env);
            open_unopened(env);
        }

        if (now - last_tick > 1000) {
            last_tick = now;
            for (int s = 0; s < MAX_PADS; s++) if (g_pads[s]) enable_sensors(g_pads[s]);
            refresh_status(env);
        }

        triton_poll(env, now, atomic_load(&g_driver_off) ? "SDL3 driver off" : hookup_triton_off_why());
        hookup_poll(env, now, atomic_load(&g_driver_off) != 0);

        if (pad_count() > 0) SDL_UpdateGamepads();

        for (int s = 0; s < MAX_PADS; s++) {
            SDL_Gamepad *pad = g_pads[s];
            if (!pad) continue;

            if (atomic_exchange(&g_led_dirty[s], 0))
                SDL_SetGamepadLED(pad, (Uint8)atomic_load(&g_led_r[s]), (Uint8)atomic_load(&g_led_g[s]), (Uint8)atomic_load(&g_led_b[s]));
            if (atomic_exchange(&g_player_dirty[s], 0)) SDL_SetGamepadPlayerIndex(pad, atomic_load(&g_player_idx[s]));

            int rl = atomic_load(&g_rl[s]), rr = atomic_load(&g_rr[s]);
            int rdirty = atomic_exchange(&g_rumble_dirty[s], 0);
            if (rl || rr) {
                if (rdirty || now - last_rumble[s] > 400) { /* SDL rumble has a duration, so refresh it */
                    SDL_RumbleGamepad(pad, (Uint16)(rl * 257), (Uint16)(rr * 257), 1000);
                    last_rumble[s] = now;
                }
                rumble_on[s] = 1;
            } else if (rumble_on[s]) {
                SDL_RumbleGamepad(pad, 0, 0, 0);
                rumble_on[s] = 0;
            }

            send_frame(env, s, pad, arr);
        }
        SDL_Delay((Uint32)atomic_load(&g_poll_ms));
    }
    return 0;
}
