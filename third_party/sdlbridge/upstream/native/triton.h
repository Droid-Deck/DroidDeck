/*
 * Steam Controller (2026, "Triton") raw passthrough over USB: the wired controller (28de:1302) or its
 * wireless "puck" dongle (28de:1304 / 28de:1305, one controller per HID interface 2..5).
 *
 * Nothing is decoded here. Every state report (buttons, sticks, trackpads with pressure, capacitive stick
 * touch, grip sense, IMU) goes to DroidDeck byte for byte, and everything Steam sends back (haptics,
 * settings, feature reads) goes to the controller unchanged, so Steam drives it exactly like a real one --
 * just over a cable/2.4 GHz link instead of Bluetooth LE.
 *
 * Included once, from main.c (uses its JNI globals). All SDL_hid calls run on the SDL thread.
 */
#pragma once

#define VALVE_VID 0x28de
#define TRITON_MAX_IFACES 4
#define TRITON_REPORT_BYTES 64
#define TRITON_STATE_PAYLOAD 45          /* bytes after the report id, as the Bluetooth descriptor declares */
#define TRITON_LIZARD_REFRESH_MS 3000
#define TRITON_RUMBLE_RESEND_MS 40

static bool triton_pid(Uint16 pid) { return pid == 0x1302 || pid == 0x1304 || pid == 0x1305; }
static bool triton_dongle(Uint16 pid) { return pid == 0x1304 || pid == 0x1305; }

typedef struct {
    SDL_hid_device *dev;
    char path[256];
    int iface;
    Uint16 pid;
} TritonIface;

static TritonIface g_tri[TRITON_MAX_IFACES];
static int g_tri_active = -1;                 /* index in g_tri of the controller being passed through */
static Uint32 g_tri_changes = 0xffffffffu;
static Uint64 g_tri_scan_ms, g_tri_lizard_ms, g_tri_rumble_ms;
static jmethodID g_tri_state, g_tri_conn, g_tri_feature;

/* ---- requests from Java (any thread) -> SDL thread ---- */
enum { TRI_REQ_OUTPUT = 1, TRI_REQ_FEATURE_SET = 2, TRI_REQ_FEATURE_GET = 3 };
typedef struct {
    int type;
    int len;
    jlong request;
    Uint8 data[TRITON_REPORT_BYTES + 1];
} TritonReq;
#define TRI_QUEUE 64
static TritonReq g_tri_queue[TRI_QUEUE];
static int g_tri_head, g_tri_tail;
static SDL_SpinLock g_tri_lock;     /* no init needed: Java may queue before the SDL thread starts */
static _Atomic int g_tri_rl, g_tri_rr, g_tri_rumble_dirty;

static void tri_lock(void) { SDL_LockSpinlock(&g_tri_lock); }
static void tri_unlock(void) { SDL_UnlockSpinlock(&g_tri_lock); }

static void tri_push(int type, const Uint8 *data, int len, jlong request)
{
    if (len < 0) len = 0;
    if (len > TRITON_REPORT_BYTES + 1) len = TRITON_REPORT_BYTES + 1;
    tri_lock();
    int next = (g_tri_tail + 1) % TRI_QUEUE;
    if (next != g_tri_head) {          /* full: drop (Steam re-sends settings and haptics constantly) */
        TritonReq *r = &g_tri_queue[g_tri_tail];
        r->type = type;
        r->len = len;
        r->request = request;
        if (len) memcpy(r->data, data, (size_t)len);
        g_tri_tail = next;
    }
    tri_unlock();
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeTritonOutput(JNIEnv *env, jclass c, jbyteArray data, jboolean feature)
{
    (void)c;
    if (!data) return;
    jsize n = (*env)->GetArrayLength(env, data);
    if (n < 1) return;
    Uint8 buf[TRITON_REPORT_BYTES + 1];
    if (n > (jsize)sizeof(buf)) n = (jsize)sizeof(buf);
    (*env)->GetByteArrayRegion(env, data, 0, n, (jbyte *)buf);
    tri_push(feature ? TRI_REQ_FEATURE_SET : TRI_REQ_OUTPUT, buf, (int)n, 0);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeTritonGetFeature(JNIEnv *env, jclass c, jlong request, jint reportId)
{
    (void)env; (void)c;
    Uint8 id = (Uint8)reportId;
    tri_push(TRI_REQ_FEATURE_GET, &id, 1, request);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeTritonRumble(JNIEnv *env, jclass c, jint left, jint right)
{
    (void)env; (void)c;
    atomic_store(&g_tri_rl, left < 0 ? 0 : left > 65535 ? 65535 : left);
    atomic_store(&g_tri_rr, right < 0 ? 0 : right > 65535 ? 65535 : right);
    atomic_store(&g_tri_rumble_dirty, 1);
}

/* ---- SDL thread -> Java ---- */
static void tri_java_init(JNIEnv *env)
{
    if (g_tri_state) return;
    g_tri_state = (*env)->GetStaticMethodID(env, g_cls, "onTritonState", "(I[B)V");
    g_tri_conn = (*env)->GetStaticMethodID(env, g_cls, "onTritonConnected", "(ZLjava/lang/String;)V");
    g_tri_feature = (*env)->GetStaticMethodID(env, g_cls, "onTritonFeature", "(J[B)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void tri_send_conn(JNIEnv *env, bool on, const char *what)
{
    if (!g_tri_conn) return;
    jstring s = (*env)->NewStringUTF(env, what ? what : "");
    (*env)->CallStaticVoidMethod(env, g_cls, g_tri_conn, (jboolean)on, s);
    (*env)->DeleteLocalRef(env, s);
}

static void tri_send_bytes(JNIEnv *env, jmethodID m, jlong request, int id, const Uint8 *data, int len, bool with_request)
{
    if (!m) return;
    jbyteArray arr = NULL;
    if (data && len > 0) {
        arr = (*env)->NewByteArray(env, len);
        if (!arr) return;
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
    }
    if (with_request) (*env)->CallStaticVoidMethod(env, g_cls, m, request, arr);
    else (*env)->CallStaticVoidMethod(env, g_cls, m, (jint)id, arr);
    if (arr) (*env)->DeleteLocalRef(env, arr);
}

static const char *tri_kind(const TritonIface *t)
{
    return triton_dongle(t->pid) ? "Steam Controller (2026) via wireless puck" : "Steam Controller (2026) via USB cable";
}

static void tri_deactivate(JNIEnv *env, const char *why)
{
    if (g_tri_active < 0) return;
    SDL_Log("triton: passthrough stopped (%s)", why);
    g_tri_active = -1;
    atomic_store(&g_tri_rl, 0);
    atomic_store(&g_tri_rr, 0);
    tri_send_conn(env, false, why);
}

static void tri_close(JNIEnv *env, int i, const char *why)
{
    if (!g_tri[i].dev) return;
    if (g_tri_active == i) tri_deactivate(env, why);
    SDL_hid_close(g_tri[i].dev);
    memset(&g_tri[i], 0, sizeof(g_tri[i]));
}

static void tri_close_all(JNIEnv *env, const char *why)
{
    for (int i = 0; i < TRITON_MAX_IFACES; i++) tri_close(env, i, why);
}

static bool tri_is_open(const char *path)
{
    for (int i = 0; i < TRITON_MAX_IFACES; i++) if (g_tri[i].dev && !strcmp(g_tri[i].path, path)) return true;
    return false;
}

/* Lizard mode (the controller acting as a mouse + keyboard) off: SET_SETTINGS_VALUES, SETTING_LIZARD_MODE = 0.
 * Steam sends this itself once it owns the controller; doing it here as well keeps the phone free of stray
 * mouse/keyboard input before Steam has started. */
static void tri_lizard_off(SDL_hid_device *dev)
{
    Uint8 buf[TRITON_REPORT_BYTES] = { 0x01, 0x87, 0x03, 0x09, 0x00, 0x00 };
    SDL_hid_send_feature_report(dev, buf, sizeof(buf));
}

static void tri_scan(JNIEnv *env)
{
    SDL_hid_device_info *list = SDL_hid_enumerate(VALVE_VID, 0);
    for (SDL_hid_device_info *d = list; d; d = d->next) {
        if (!triton_pid(d->product_id) || !d->path) continue;
        /* The puck carries one controller per interface 2..5; the cable has a single controller interface. */
        if (triton_dongle(d->product_id) && (d->interface_number < 2 || d->interface_number > 5)) continue;
        if (!triton_dongle(d->product_id) && d->usage_page != 0 && d->usage_page < 0xff00) continue;
        if (d->interface_class == 3 && d->interface_subclass == 1) continue;   /* boot keyboard / mouse (lizard mode) */
        if (tri_is_open(d->path)) continue;
        int slot = -1;
        for (int i = 0; i < TRITON_MAX_IFACES; i++) if (!g_tri[i].dev) { slot = i; break; }
        if (slot < 0) break;
        SDL_hid_device *dev = SDL_hid_open_path(d->path);
        if (!dev) {
            SDL_Log("triton: could not open %04x:%04x interface %d: %s", d->vendor_id, d->product_id, d->interface_number, SDL_GetError());
            continue;
        }
        SDL_hid_set_nonblocking(dev, 1);
        g_tri[slot].dev = dev;
        SDL_strlcpy(g_tri[slot].path, d->path, sizeof(g_tri[slot].path));
        g_tri[slot].iface = d->interface_number;
        g_tri[slot].pid = d->product_id;
        SDL_Log("triton: opened %04x:%04x interface %d", d->vendor_id, d->product_id, d->interface_number);
        if (!triton_dongle(d->product_id)) tri_lizard_off(dev);
    }
    SDL_hid_free_enumeration(list);
}

static void tri_run_queue(JNIEnv *env)
{
    for (;;) {
        TritonReq r;
        tri_lock();
        bool have = g_tri_head != g_tri_tail;
        if (have) { r = g_tri_queue[g_tri_head]; g_tri_head = (g_tri_head + 1) % TRI_QUEUE; }
        tri_unlock();
        if (!have) return;
        SDL_hid_device *dev = g_tri_active >= 0 ? g_tri[g_tri_active].dev : NULL;
        if (r.type == TRI_REQ_FEATURE_GET) {
            Uint8 buf[TRITON_REPORT_BYTES + 1] = { 0 };
            buf[0] = r.data[0];
            int n = dev ? SDL_hid_get_feature_report(dev, buf, TRITON_REPORT_BYTES) : -1;
            tri_send_bytes(env, g_tri_feature, r.request, 0, n > 0 ? buf : NULL, n > 0 ? n : 0, true);
        } else if (dev && r.type == TRI_REQ_FEATURE_SET) {
            Uint8 buf[TRITON_REPORT_BYTES] = { 0 };
            int n = r.len > TRITON_REPORT_BYTES ? TRITON_REPORT_BYTES : r.len;
            memcpy(buf, r.data, (size_t)n);
            SDL_hid_send_feature_report(dev, buf, TRITON_REPORT_BYTES);
        } else if (dev && r.type == TRI_REQ_OUTPUT) {
            SDL_hid_write(dev, r.data, (size_t)r.len);
        }
    }
}

/* Game rumble for the passed-through controller (DroidDeck's virtual-pad rumble): haptic rumble report 0x80,
 * re-sent every 40 ms while on, like SDL's own driver does. */
static void tri_rumble(Uint64 now)
{
    if (g_tri_active < 0) return;
    int dirty = atomic_exchange(&g_tri_rumble_dirty, 0);
    int l = atomic_load(&g_tri_rl), r = atomic_load(&g_tri_rr);
    if (!dirty && !(l || r)) return;
    if (!dirty && now - g_tri_rumble_ms < TRITON_RUMBLE_RESEND_MS) return;
    Uint8 msg[10] = { 0x80, 0, 0, 0, (Uint8)l, (Uint8)(l >> 8), 0, (Uint8)r, (Uint8)(r >> 8), 0 };
    SDL_hid_write(g_tri[g_tri_active].dev, msg, sizeof(msg));
    g_tri_rumble_ms = now;
}

/* off_why: NULL = pass the controller through; otherwise why not (SDL3 driver off, or Steam Native Hook-up,
 * which hands the Steam Controller (2026) to Steam as itself instead -- see hookup.h). */
static void triton_poll(JNIEnv *env, Uint64 now, const char *off_why)
{
    tri_java_init(env);
    if (off_why) {
        tri_close_all(env, off_why);
        return;
    }
    Uint32 changes = SDL_hid_device_change_count();
    if (changes != g_tri_changes || now - g_tri_scan_ms > 3000) {
        g_tri_changes = changes;
        g_tri_scan_ms = now;
        tri_scan(env);
    }
    for (int i = 0; i < TRITON_MAX_IFACES; i++) {
        TritonIface *t = &g_tri[i];
        for (int reads = 0; t->dev && reads < 32; reads++) {
            Uint8 buf[TRITON_REPORT_BYTES];
            int n = SDL_hid_read(t->dev, buf, sizeof(buf));
            if (n == 0) break;
            if (n < 0) { tri_close(env, i, "controller unplugged"); break; }
            Uint8 id = buf[0];
            if (id == 0x42 || id == 0x45 || id == 0x47) {
                if (g_tri_active < 0) {
                    g_tri_active = i;
                    SDL_Log("triton: passing through interface %d", t->iface);
                    tri_send_conn(env, true, tri_kind(t));
                    tri_lizard_off(t->dev);
                    g_tri_lizard_ms = now;
                }
                if (g_tri_active != i) continue;
                /* The wired state report (0x42) has the same layout as the Bluetooth one (0x45) that
                 * DroidDeck's Steam Controller node declares, so it is relabelled; 0x47 keeps its id. */
                int len = n - 1 < TRITON_STATE_PAYLOAD ? n - 1 : TRITON_STATE_PAYLOAD;
                tri_send_bytes(env, g_tri_state, 0, id == 0x42 ? 0x45 : id, buf + 1, len, false);
            } else if ((id == 0x79 || id == 0x46) && n >= 2) {
                /* Puck wireless status: 1 = controller went away, 2 = connected. */
                if (buf[1] == 1 && g_tri_active == i) tri_deactivate(env, "controller turned off");
            }
        }
    }
    if (g_tri_active >= 0) {
        tri_run_queue(env);
        tri_rumble(now);
        if (now - g_tri_lizard_ms > TRITON_LIZARD_REFRESH_MS) {
            g_tri_lizard_ms = now;
            tri_lizard_off(g_tri[g_tri_active].dev);
        }
    } else {
        tri_run_queue(env);   /* answers pending feature reads with "no data" */
    }
}

static bool triton_active(void) { return g_tri_active >= 0; }
