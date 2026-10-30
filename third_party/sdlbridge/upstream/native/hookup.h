/*
 * "Steam Native Hook-up": instead of reading a controller as a gamepad and re-presenting it, hand Steam the
 * REAL controller. SDL3 is still used to find out which USB HID interface is a game controller (any
 * controller SDL3's USB driver supports); that interface is then released by SDL's gamepad layer and opened
 * raw, and every HID report travels unchanged between the controller and DroidDeck's hidraw node -- with the
 * controller's real vendor/product id, name, serial and report descriptor -- so Steam's own driver for that
 * controller runs, exactly like on a PC: a Vader 4 Pro with its back buttons, C/Z and gyro, a DualSense Edge
 * with its touchpad, mute, paddles and Fn buttons, an Xbox controller, a Switch Pro controller, ...
 *
 * Controllers SDL3 can only reach through Android's input (Bluetooth) cannot be handed over raw; they keep
 * working as before (DroidDeck's Steam Deck pad). Included once from main.c; SDL_hid calls run on the SDL thread.
 */
#pragma once

#include <pthread.h>
#include <sys/resource.h>
#include "hkshm.h"

#define HOOKUP_SLOTS 4
#define HOOKUP_REPORT_MAX 128

typedef struct {
    SDL_hid_device *dev;
    char path[256];
    Uint16 vid, pid;
    int iface;
} HookupDev;

static HookupDev g_hk[HOOKUP_SLOTS];
static _Atomic int g_hookup_on;
static _Atomic int g_hookup_held;          /* on, but waiting for Steam (DroidDeck's desktop without Steam running) */
static int g_hookup_applied = -1;          /* last mode the SDL thread acted on */
static char g_hk_types[1024];              /* "0xVVVV/0xPPPP,..." controller types handed to Steam */
static int g_hk_iface_of_type[32];         /* interface number SDL used, per entry in g_hk_types (index order) */
static Uint16 g_hk_type_vid[32], g_hk_type_pid[32];
static int g_hk_type_count;
static Uint64 g_hk_scan_ms;
static Uint32 g_hk_changes = 0xffffffffu;
static jmethodID g_hk_added, g_hk_input, g_hk_removed, g_hk_reply, g_hk_prefetch, g_hk_log;

/* ---- fast path (hkshm.h): reports go straight into DroidDeck's shared file, read by a dedicated thread ----
 * g_hk_io is held by the reader thread for each pass over the controllers, and by anyone who remaps the file
 * or releases a controller, so neither disappears under the reader. */
static HkShm g_hks = { NULL, -1, 0 };
static pthread_mutex_t g_hk_io = PTHREAD_MUTEX_INITIALIZER;
/* Held by the writer thread while it uses the controllers (its USB writes can take a few ms, so not under g_hk_io);
 * releasing a controller takes it first. Order: g_hk_wr, then g_hk_io. */
static pthread_mutex_t g_hk_wr = PTHREAD_MUTEX_INITIALIZER;
static _Atomic int g_hk_writer_started;
static _Atomic int g_hk_gone[HOOKUP_SLOTS];        /* reader saw the controller go away; SDL thread closes it */
static _Atomic int g_hk_reader_started;
static _Atomic int g_hk_fast_now[HOOKUP_SLOTS];    /* slot currently served through the shared file */
static int g_hk_fast_logged[HOOKUP_SLOTS];          /* SDL thread: last fast/slow state logged per slot */
static uint64_t g_hk_get_last[HOOKUP_SLOTS];        /* writer thread: last feature read taken from the file */
static Uint64 g_hk_alive_ms;

/* Logged to logcat AND sent to DroidDeck, which writes it into the session's pad.log ("sdl3bridge: ..."). */
static void hk_log(JNIEnv *env, const char *fmt, ...)
{
    char line[400];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    SDL_Log("%s", line);
    if (env && g_hk_log) {
        jstring s = (*env)->NewStringUTF(env, line);
        if (s) {
            (*env)->CallStaticVoidMethod(env, g_cls, g_hk_log, s);
            (*env)->DeleteLocalRef(env, s);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
}

/* ---- requests from Java (any thread) -> SDL thread ---- */
enum { HK_OUT = 0, HK_FEATURE_SET = 1, HK_GET_FEATURE = 2, HK_GET_INPUT = 3 };
typedef struct {
    int slot, type, len;
    jlong request;
    Uint8 data[HOOKUP_REPORT_MAX];
} HookupReq;
#define HK_QUEUE 128
static HookupReq g_hk_queue[HK_QUEUE];
static int g_hk_head, g_hk_tail;
static SDL_SpinLock g_hk_lock;

static void hk_push(int slot, int type, const Uint8 *data, int len, jlong request)
{
    if (slot < 0 || slot >= HOOKUP_SLOTS) return;
    if (len < 0) len = 0;
    if (len > HOOKUP_REPORT_MAX) len = HOOKUP_REPORT_MAX;
    SDL_LockSpinlock(&g_hk_lock);
    int next = (g_hk_tail + 1) % HK_QUEUE;
    if (next != g_hk_head) {
        HookupReq *r = &g_hk_queue[g_hk_tail];
        r->slot = slot; r->type = type; r->len = len; r->request = request;
        if (len) memcpy(r->data, data, (size_t)len);
        g_hk_tail = next;
    }
    SDL_UnlockSpinlock(&g_hk_lock);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeSetHookup(JNIEnv *env, jclass c, jint on)
{
    (void)env; (void)c;
    atomic_store(&g_hookup_held, on == 2);   /* DroidDeck's desktop without Steam: wait (controllers stay gamepads) */
    atomic_store(&g_hookup_on, on == 1);
}

/* DroidDeck handed over its shared hook-up file (any thread). Returns 0 when the fast path is on. */
JNIEXPORT jint JNICALL Java_com_example_dsedge_NativeBridge_nativeHookupMapShm(JNIEnv *env, jclass c, jint fd)
{
    (void)c;
    HkShm m = { NULL, -1, 0 };
    int r = hks_map(&m, fd);
    pthread_mutex_lock(&g_hk_io);
    int same = r == 0 && g_hks.base && g_hks.ino == m.ino && hks_alive(&g_hks);
    if (r == 0 && !same) {
        hks_unmap(&g_hks);
        g_hks = m;
    }
    pthread_mutex_unlock(&g_hk_io);
    if (same) hks_unmap(&m);   /* already mapped: keep the old mapping */
    if (r == 0 && !same)
        hk_log(env, "hookup: fast path on: reports go straight into DroidDeck's shared memory (no messages, Steam woken at once)");
    else if (r != 0)
        hk_log(env, "hookup: fast path unavailable (shared file could not be mapped, %d); using messages", r);
    return r;
}

/* Inode of the mapped live file (0: none) -- DroidDeck compares it with its own file to know if it must resend. */
JNIEXPORT jlong JNICALL Java_com_example_dsedge_NativeBridge_nativeHookupShmInode(JNIEnv *env, jclass c)
{
    (void)env; (void)c;
    pthread_mutex_lock(&g_hk_io);
    jlong ino = hks_alive(&g_hks) ? (jlong)g_hks.ino : 0;
    pthread_mutex_unlock(&g_hk_io);
    return ino;
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeHookupOutput(JNIEnv *env, jclass c, jint slot, jint kind, jbyteArray data)
{
    (void)c;
    if (!data) return;
    jsize n = (*env)->GetArrayLength(env, data);
    if (n < 1) return;
    Uint8 buf[HOOKUP_REPORT_MAX];
    if (n > HOOKUP_REPORT_MAX) n = HOOKUP_REPORT_MAX;
    (*env)->GetByteArrayRegion(env, data, 0, n, (jbyte *)buf);
    hk_push(slot, kind == 1 ? HK_FEATURE_SET : HK_OUT, buf, (int)n, 0);
}

JNIEXPORT void JNICALL Java_com_example_dsedge_NativeBridge_nativeHookupGet(JNIEnv *env, jclass c, jint slot, jlong request,
                                                                             jint reportId, jboolean input, jint length)
{
    (void)env; (void)c;
    Uint8 buf[2] = { (Uint8)reportId, (Uint8)(length > HOOKUP_REPORT_MAX ? HOOKUP_REPORT_MAX : length < 1 ? 1 : length) };
    hk_push(slot, input ? HK_GET_INPUT : HK_GET_FEATURE, buf, 2, request);
}

/* ---- SDL thread -> Java ---- */
static void hk_java_init(JNIEnv *env)
{
    if (g_hk_added) return;
    g_hk_added = (*env)->GetStaticMethodID(env, g_cls, "onHookupAdded", "(ILjava/lang/String;III)V");
    g_hk_input = (*env)->GetStaticMethodID(env, g_cls, "onHookupInput", "(I[B)V");
    g_hk_removed = (*env)->GetStaticMethodID(env, g_cls, "onHookupRemoved", "(I)V");
    g_hk_reply = (*env)->GetStaticMethodID(env, g_cls, "onHookupReply", "(IJ[B)V");
    g_hk_prefetch = (*env)->GetStaticMethodID(env, g_cls, "prefetchHookupDescriptor", "(Ljava/lang/String;)V");
    g_hk_log = (*env)->GetStaticMethodID(env, g_cls, "onBridgeLog", "(Ljava/lang/String;)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static jbyteArray hk_bytes(JNIEnv *env, const Uint8 *data, int len)
{
    if (!data || len <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
    return arr;
}

static bool hookup_active(void)
{
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev) return true;
    return false;
}

static int hookup_count(void)
{
    int n = 0;
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev) n++;
    return n;
}

/* Hooked-up controllers whose reports currently go straight into DroidDeck's shared memory. */
static int hookup_fast_count(void)
{
    int n = 0;
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev && atomic_load(&g_hk_fast_now[i])) n++;
    return n;
}

static void hk_close(JNIEnv *env, int i, const char *why)
{
    if (!g_hk[i].dev) return;
    hk_log(env, "hookup: slot %d %04x:%04x released (%s)", i, g_hk[i].vid, g_hk[i].pid, why);
    pthread_mutex_lock(&g_hk_wr);          /* the writer thread is not using it now, and won't see it again */
    pthread_mutex_lock(&g_hk_io);          /* nor is the reader thread */
    SDL_hid_device *dev = g_hk[i].dev;
    memset(&g_hk[i], 0, sizeof(g_hk[i]));
    atomic_store(&g_hk_gone[i], 0);
    atomic_store(&g_hk_fast_now[i], 0);
    pthread_mutex_unlock(&g_hk_io);
    pthread_mutex_unlock(&g_hk_wr);
    g_hk_fast_logged[i] = 0;
    SDL_hid_close(dev);
    if (g_hk_removed) (*env)->CallStaticVoidMethod(env, g_cls, g_hk_removed, (jint)i);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static bool hk_path_open(const char *path)
{
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (g_hk[i].dev && !strcmp(g_hk[i].path, path)) return true;
    return false;
}

/*
 * Reader thread: takes each report the moment SDL's USB thread has queued it (checked every 250 us instead of
 * once per main-loop pass) and writes it straight into Steam's FIFO, waking Steam's reader. When the fast path
 * is not available for a slot, the report goes the old way (Java -> message to DroidDeck).
 */
static int SDLCALL hk_reader(void *unused)
{
    (void)unused;
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), -10);   /* like Android's URGENT_DISPLAY; ignored if refused */
    /* Latency meter, every controller: do reports come from Android's USB layer one by one, or in bursts (several
     * waiting at once = they were held up before the bridge got them)? Logged every 10 s: the first time, then
     * when bursts or long gaps show up. */
    uint32_t m_reports[HOOKUP_SLOTS] = {0}, m_passes[HOOKUP_SLOTS] = {0}, m_burst[HOOKUP_SLOTS] = {0};
    uint64_t m_last_us[HOOKUP_SLOTS] = {0}, m_gap_us[HOOKUP_SLOTS] = {0}, m_since_us = 0;
    int m_logged[HOOKUP_SLOTS] = {0};
    for (;;) {
        int got = 0, any = 0;
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        uint64_t now_us = (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
        pthread_mutex_lock(&g_hk_io);
        for (int i = 0; i < HOOKUP_SLOTS; i++) {
            SDL_hid_device *dev = g_hk[i].dev;
            if (!dev || atomic_load(&g_hk_gone[i])) continue;
            any = 1;
            int fast = hks_owns(&g_hks, i, g_hk[i].vid, g_hk[i].pid);
            atomic_store(&g_hk_fast_now[i], fast);
            uint32_t pass = 0;
            for (int reads = 0; reads < 64; reads++) {
                Uint8 buf[HOOKUP_REPORT_MAX];
                int n = SDL_hid_read(dev, buf, sizeof(buf));
                if (n == 0) break;
                if (n < 0) { atomic_store(&g_hk_gone[i], 1); break; }
                got++;
                pass++;
                if (fast) {
                    hks_write_in(&g_hks, i, buf, n);
                } else if (env && g_hk_input) {
                    jbyteArray arr = hk_bytes(env, buf, n);
                    if (arr) (*env)->CallStaticVoidMethod(env, g_cls, g_hk_input, (jint)i, arr);
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    if (arr) (*env)->DeleteLocalRef(env, arr);
                }
            }
            if (pass) {
                m_reports[i] += pass;
                m_passes[i]++;
                if (pass > m_burst[i]) m_burst[i] = pass;
                if (m_last_us[i] && now_us - m_last_us[i] > m_gap_us[i]) m_gap_us[i] = now_us - m_last_us[i];
                m_last_us[i] = now_us;
            }
        }
        pthread_mutex_unlock(&g_hk_io);
        if (!m_since_us) m_since_us = now_us;
        if (now_us - m_since_us >= 10000000u) {
            double secs = (now_us - m_since_us) / 1e6;
            for (int i = 0; i < HOOKUP_SLOTS; i++) {
                if (!m_passes[i]) continue;
                if (env && (!m_logged[i]++ || m_burst[i] > 2 || m_gap_us[i] > 8000))
                    hk_log(env, "latency: slot %d: %.0f reports/s from USB, up to %u waiting at once (avg %.2f), "
                           "longest gap %.1f ms", i, m_reports[i] / secs, m_burst[i],
                           (double)m_reports[i] / m_passes[i], m_gap_us[i] / 1000.0);
                m_reports[i] = m_passes[i] = m_burst[i] = 0;
                m_gap_us[i] = 0;
            }
            m_since_us = now_us;
        }
        if (!got) {
            struct timespec t = { 0, any ? 250000L : 20000000L };   /* 250 us while controllers are handed over */
            nanosleep(&t, NULL);
        }
    }
    return 0;
}

/*
 * Writer thread: Steam's output reports (rumble, lights, settings) and feature reads for the slots the bridge
 * serves through the shared file, sent to the controller as soon as Steam queues them (checked every 500 us).
 * Each USB write takes a few ms; done in the SDL main loop they waited for its next pass and for each other,
 * so under heavy rumble Steam's queue filled up and Steam had to wait for room.
 */
static int SDLCALL hk_writer(void *unused)
{
    (void)unused;
    setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), -10);
    for (;;) {
        int did = 0, any = 0;
        pthread_mutex_lock(&g_hk_wr);
        for (int i = 0; i < HOOKUP_SLOTS; i++) {
            HksOut outs[16];
            int nout = 0, want_get = 0, report_id = 0, input = 0, length = 0;
            uint64_t request = 0;
            pthread_mutex_lock(&g_hk_io);
            SDL_hid_device *dev = g_hk[i].dev;
            if (dev && !atomic_load(&g_hk_gone[i]) && hks_owns(&g_hks, i, g_hk[i].vid, g_hk[i].pid)) {
                nout = hks_take_out(&g_hks, i, outs, (int)SDL_arraysize(outs));
                want_get = hks_get_pending(&g_hks, i, &g_hk_get_last[i], &request, &report_id, &input, &length);
            }
            pthread_mutex_unlock(&g_hk_io);
            if (!dev) continue;
            any = 1;
            for (int k = 0; k < nout; k++) {
                if (outs[k].kind == 1) SDL_hid_send_feature_report(dev, outs[k].data, (size_t)outs[k].len);
                else SDL_hid_write(dev, outs[k].data, (size_t)outs[k].len);
            }
            if (want_get) {
                Uint8 buf[HOOKUP_REPORT_MAX + 1] = { 0 };
                buf[0] = (Uint8)report_id;
                int n = input ? SDL_hid_get_input_report(dev, buf, (size_t)length)
                              : SDL_hid_get_feature_report(dev, buf, (size_t)length);
                pthread_mutex_lock(&g_hk_io);
                if (hks_owns(&g_hks, i, g_hk[i].vid, g_hk[i].pid)) hks_get_reply(&g_hks, i, request, buf, n);
                pthread_mutex_unlock(&g_hk_io);
            }
            did += nout + want_get;
        }
        pthread_mutex_unlock(&g_hk_wr);
        if (!did) {
            struct timespec t = { 0, any ? 500000L : 20000000L };
            nanosleep(&t, NULL);
        }
    }
    return 0;
}

static void hk_start_reader(void)
{
    if (!atomic_exchange(&g_hk_writer_started, 1)) {
        SDL_Thread *w = SDL_CreateThread(hk_writer, "hookup-writer", NULL);
        if (w) SDL_DetachThread(w);
        else atomic_store(&g_hk_writer_started, 0);
    }
    if (atomic_exchange(&g_hk_reader_started, 1)) return;
    SDL_Thread *t = SDL_CreateThread(hk_reader, "hookup-reader", NULL);
    if (t) SDL_DetachThread(t);
    else atomic_store(&g_hk_reader_started, 0);
}

/* SDL thread, every loop: the heartbeat that tells DroidDeck and Steam's side the bridge serves the slot itself. */
static void hk_serve_fast(JNIEnv *env)
{
    uint64_t now = hks_now_ms();
    for (int i = 0; i < HOOKUP_SLOTS; i++) {
        if (!g_hk[i].dev) continue;
        pthread_mutex_lock(&g_hk_io);
        int fast = hks_owns(&g_hks, i, g_hk[i].vid, g_hk[i].pid);
        if (fast) {
            hks_heartbeat(&g_hks, i, now);
            int ms = atomic_load(&g_poll_ms);
            hks_set_rate(&g_hks, i, ms > 0 ? (uint32_t)(1000 / ms) : 0u);   /* Poll rate also paces Hook-up */
        }
        pthread_mutex_unlock(&g_hk_io);
        if (fast != g_hk_fast_logged[i]) {
            g_hk_fast_logged[i] = fast;
            if (fast) hk_log(env, "hookup: slot %d served through shared memory (fast path)", i);
        }
    }
}

static int hk_free_slot(void)
{
    for (int i = 0; i < HOOKUP_SLOTS; i++) if (!g_hk[i].dev) return i;
    return -1;
}

/* Read the HID report descriptor while nobody holds the interface (Android refuses an interface request on an
 * interface another connection has claimed), so Steam gets the controller's real one. */
static void hk_prefetch(JNIEnv *env, const char *path)
{
    if (!g_hk_prefetch) return;
    jstring p = (*env)->NewStringUTF(env, path);
    (*env)->CallStaticVoidMethod(env, g_cls, g_hk_prefetch, p);
    (*env)->DeleteLocalRef(env, p);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static bool hk_adopt(JNIEnv *env, int slot, SDL_hid_device *dev, const char *path, Uint16 vid, Uint16 pid, int iface);

static bool hk_open(JNIEnv *env, const char *path, Uint16 vid, Uint16 pid, int iface)
{
    if (hk_path_open(path)) return true;
    int slot = hk_free_slot();
    if (slot < 0) return false;
    hk_prefetch(env, path);
    SDL_hid_device *dev = SDL_hid_open_path(path);
    if (!dev) {
        hk_log(env, "hookup: could not open %s: %s", path, SDL_GetError());
        return false;
    }
    SDL_hid_set_nonblocking(dev, 1);
    return hk_adopt(env, slot, dev, path, vid, pid, iface);
}

/* A raw-opened controller interface becomes hook-up slot `slot` (reader thread + DroidDeck's hidraw node). */
static bool hk_adopt(JNIEnv *env, int slot, SDL_hid_device *dev, const char *path, Uint16 vid, Uint16 pid, int iface)
{
    pthread_mutex_lock(&g_hk_io);
    SDL_strlcpy(g_hk[slot].path, path, sizeof(g_hk[slot].path));
    g_hk[slot].vid = vid;
    g_hk[slot].pid = pid;
    g_hk[slot].iface = iface;
    atomic_store(&g_hk_gone[slot], 0);
    g_hk_get_last[slot] = 0;
    g_hk[slot].dev = dev;
    pthread_mutex_unlock(&g_hk_io);
    hk_start_reader();
    hk_log(env, "hookup: slot %d is %04x:%04x interface %d (%s), handing it to DroidDeck", slot, vid, pid, iface, path);
    if (g_hk_added) {
        jstring p = (*env)->NewStringUTF(env, path);
        (*env)->CallStaticVoidMethod(env, g_cls, g_hk_added, (jint)slot, p, (jint)vid, (jint)pid, (jint)iface);
        (*env)->DeleteLocalRef(env, p);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            hk_log(env, "hookup: slot %d: describing the controller to DroidDeck failed (Java exception, see logcat)", slot);
        }
    }
    return true;
}

/* SDL's gamepad layer must let go of the hooked-up controller types: the ignore hint (which also keeps the
 * Steam Controller 2026, passed through by triton.h, away) plus a restart of the gamepad subsystem. */
static void hk_apply_ignore_list(JNIEnv *env)
{
    char hint[1200] = "0x28de/0x1302,0x28de/0x1304,0x28de/0x1305";
    if (atomic_load(&g_hookup_on) && g_hk_types[0]) {
        SDL_strlcat(hint, ",", sizeof(hint));
        SDL_strlcat(hint, g_hk_types, sizeof(hint));
    }
    SDL_SetHint(SDL_HINT_GAMECONTROLLER_IGNORE_DEVICES, hint);
}

static bool hk_type_known(Uint16 vid, Uint16 pid)
{
    for (int i = 0; i < g_hk_type_count; i++) if (g_hk_type_vid[i] == vid && g_hk_type_pid[i] == pid) return true;
    return false;
}

static void hk_add_type(Uint16 vid, Uint16 pid, int iface)
{
    if (hk_type_known(vid, pid) || g_hk_type_count >= (int)SDL_arraysize(g_hk_type_vid)) return;
    char one[24];
    snprintf(one, sizeof(one), "%s0x%04x/0x%04x", g_hk_types[0] ? "," : "", vid, pid);
    SDL_strlcat(g_hk_types, one, sizeof(g_hk_types));
    g_hk_type_vid[g_hk_type_count] = vid;
    g_hk_type_pid[g_hk_type_count] = pid;
    g_hk_iface_of_type[g_hk_type_count] = iface;
    g_hk_type_count++;
}

static int hk_type_iface(Uint16 vid, Uint16 pid)
{
    for (int i = 0; i < g_hk_type_count; i++) if (g_hk_type_vid[i] == vid && g_hk_type_pid[i] == pid) return g_hk_iface_of_type[i];
    return -1;
}

static void restart_gamepads(JNIEnv *env);   /* main.c */

/* Interface number of an SDL hid path ("<usb device>/<vid>/<pid>/<interface index>" on Android). */
static int hk_path_iface(const char *path)
{
    const char *slash = strrchr(path, '/');
    return slash ? SDL_atoi(slash + 1) : 0;
}

/* Hand every SDL3 USB-driver gamepad (and later copies of those controller types) over to Steam. */
static void hookup_take_gamepads(JNIEnv *env)
{
    bool handed = false;
    int cnt = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&cnt);
    for (int i = 0; ids && i < cnt; i++) {
        const char *path = SDL_GetGamepadPathForID(ids[i]);
        if (!path) continue;   /* Android input (Bluetooth): cannot be handed over raw */
        Uint16 vid = SDL_GetGamepadVendorForID(ids[i]), pid = SDL_GetGamepadProductForID(ids[i]);
        if (hk_type_known(vid, pid)) continue;
        hk_log(env, "hookup: taking %04x:%04x \"%s\" from SDL's gamepad layer", vid, pid,
               SDL_GetGamepadNameForID(ids[i]) ? SDL_GetGamepadNameForID(ids[i]) : "?");
        hk_add_type(vid, pid, hk_path_iface(path));
        handed = true;
    }
    SDL_free(ids);
    if (handed) {
        hk_log(env, "hookup: restarting SDL's gamepad layer so it lets go (ignored: %s)", g_hk_types);
        hk_apply_ignore_list(env);
        restart_gamepads(env);     /* SDL closes them; they stay ignored; the scan below opens them raw */
        g_hk_changes = 0xffffffffu;
    }
}

static void hookup_scan(JNIEnv *env)
{
    if (!g_hk_type_count) return;
    SDL_hid_device_info *list = SDL_hid_enumerate(0, 0);
    for (SDL_hid_device_info *d = list; d; d = d->next) {
        if (!d->path || !hk_type_known(d->vendor_id, d->product_id)) continue;
        int want = hk_type_iface(d->vendor_id, d->product_id);
        int iface = hk_path_iface(d->path);
        if (want >= 0 && iface != want) continue;
        if (d->bus_type == SDL_HID_API_BUS_BLUETOOTH) continue;
        if (!hk_path_open(d->path)) hk_log(env, "hookup: found %04x:%04x at %s, opening it raw", d->vendor_id, d->product_id, d->path);
        hk_open(env, d->path, d->vendor_id, d->product_id, iface);
    }
    SDL_hid_free_enumeration(list);
}

static void hookup_run_queue(JNIEnv *env)
{
    for (;;) {
        HookupReq r;
        SDL_LockSpinlock(&g_hk_lock);
        bool have = g_hk_head != g_hk_tail;
        if (have) { r = g_hk_queue[g_hk_head]; g_hk_head = (g_hk_head + 1) % HK_QUEUE; }
        SDL_UnlockSpinlock(&g_hk_lock);
        if (!have) return;
        SDL_hid_device *dev = g_hk[r.slot].dev;
        if (r.type == HK_GET_FEATURE || r.type == HK_GET_INPUT) {
            Uint8 buf[HOOKUP_REPORT_MAX + 1] = { 0 };
            int want = r.data[1] ? r.data[1] : HOOKUP_REPORT_MAX;
            buf[0] = r.data[0];
            int n = -1;
            if (dev) n = r.type == HK_GET_INPUT ? SDL_hid_get_input_report(dev, buf, (size_t)want)
                                                : SDL_hid_get_feature_report(dev, buf, (size_t)want);
            jbyteArray arr = n > 0 ? hk_bytes(env, buf, n) : NULL;
            if (g_hk_reply) (*env)->CallStaticVoidMethod(env, g_cls, g_hk_reply, (jint)r.slot, r.request, arr);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            if (arr) (*env)->DeleteLocalRef(env, arr);
        } else if (dev && r.type == HK_FEATURE_SET) {
            SDL_hid_send_feature_report(dev, r.data, (size_t)r.len);
        } else if (dev && r.type == HK_OUT) {
            SDL_hid_write(dev, r.data, (size_t)r.len);
        }
    }
}

/*
 * Steam Controller (2026): cable 28de:1302, wireless puck 28de:1304/1305 (one controller per interface 2..5).
 * SDL's gamepad layer never opens it (it is always on the ignore list; outside hook-up, triton.h passes it
 * through to DroidDeck's own Steam Controller node), so hook-up finds it itself. Android doesn't say which of
 * its HID interfaces is the controller, so the candidates are opened and watched, and the one that carries a
 * controller -- state reports, or the puck announcing a connected controller -- is handed to Steam as itself,
 * exactly like on a PC (Steam drives lizard mode, haptics, trackpads, grip sense, IMU). Only interfaces with a
 * controller get a hook-up slot, so a puck doesn't use up all four.
 */
#define HK_WATCH_MAX 6
typedef struct {
    SDL_hid_device *dev;
    char path[256];
    Uint16 pid;
    int iface;
} HkWatch;
static HkWatch g_hkw[HK_WATCH_MAX];
static int g_hkw_full_logged;

static bool hk_triton_puck(Uint16 pid) { return pid == 0x1304 || pid == 0x1305; }

static bool hk_watching(const char *path)
{
    for (int w = 0; w < HK_WATCH_MAX; w++) if (g_hkw[w].dev && !strcmp(g_hkw[w].path, path)) return true;
    return false;
}

static void hk_watch_close_all(void)
{
    for (int w = 0; w < HK_WATCH_MAX; w++) {
        if (g_hkw[w].dev) SDL_hid_close(g_hkw[w].dev);
        memset(&g_hkw[w], 0, sizeof(g_hkw[w]));
    }
}

static void hk_valve_scan(JNIEnv *env)
{
    SDL_hid_device_info *list = SDL_hid_enumerate(VALVE_VID, 0);
    for (SDL_hid_device_info *d = list; d; d = d->next) {
        if (!d->path || !triton_pid(d->product_id)) continue;
        if (hk_triton_puck(d->product_id) && (d->interface_number < 2 || d->interface_number > 5)) continue;
        if (d->interface_class == 3 && d->interface_subclass == 1) continue;   /* boot keyboard / mouse (lizard mode) */
        if (hk_path_open(d->path) || hk_watching(d->path)) continue;
        int w = -1;
        for (int k = 0; k < HK_WATCH_MAX; k++) if (!g_hkw[k].dev) { w = k; break; }
        if (w < 0) break;
        hk_prefetch(env, d->path);   /* before it is opened: the descriptor can only be read while nobody holds it */
        SDL_hid_device *dev = SDL_hid_open_path(d->path);
        if (!dev) {
            hk_log(env, "hookup: could not open Steam Controller (2026) interface %d: %s", d->interface_number, SDL_GetError());
            continue;
        }
        SDL_hid_set_nonblocking(dev, 1);
        g_hkw[w].dev = dev;
        SDL_strlcpy(g_hkw[w].path, d->path, sizeof(g_hkw[w].path));
        g_hkw[w].pid = d->product_id;
        g_hkw[w].iface = d->interface_number;
        if (!hk_triton_puck(d->product_id)) tri_lizard_off(dev);   /* no stray mouse input until Steam owns it */
        hk_log(env, "hookup: Steam Controller (2026) %s, interface %d: waiting for controller data",
               hk_triton_puck(d->product_id) ? "puck" : "cable", d->interface_number);
    }
    SDL_hid_free_enumeration(list);
}

static void hk_valve_poll(JNIEnv *env)
{
    for (int w = 0; w < HK_WATCH_MAX; w++) {
        for (int reads = 0; g_hkw[w].dev && reads < 16; reads++) {
            Uint8 buf[HOOKUP_REPORT_MAX];
            int n = SDL_hid_read(g_hkw[w].dev, buf, sizeof(buf));
            if (n == 0) break;
            if (n < 0) {
                hk_log(env, "hookup: Steam Controller (2026) interface %d gone", g_hkw[w].iface);
                SDL_hid_close(g_hkw[w].dev);
                memset(&g_hkw[w], 0, sizeof(g_hkw[w]));
                break;
            }
            Uint8 id = buf[0];
            bool controller = id == 0x42 || id == 0x45 || id == 0x47 || ((id == 0x79 || id == 0x46) && n >= 2 && buf[1] == 2);
            if (!controller) continue;
            int slot = hk_free_slot();
            if (slot < 0) {
                if (!g_hkw_full_logged) hk_log(env, "hookup: a Steam Controller (2026) is ready but all %d hook-up slots are in use", HOOKUP_SLOTS);
                g_hkw_full_logged = 1;
                break;
            }
            g_hkw_full_logged = 0;
            HkWatch h = g_hkw[w];
            memset(&g_hkw[w], 0, sizeof(g_hkw[w]));   /* the handle moves to the hook-up slot */
            hk_log(env, "hookup: Steam Controller (2026) on %s interface %d goes to Steam natively",
                   hk_triton_puck(h.pid) ? "the puck," : "the cable,", h.iface);
            hk_adopt(env, slot, h.dev, h.path, VALVE_VID, h.pid, h.iface);
        }
    }
}

/* Why triton.h must not pass a Steam Controller (2026) through right now (NULL: it may). Hook-up hands it to
 * Steam itself while on -- and until it has actually released it after being switched off, so the two never
 * hold the same interface. */
static const char *hookup_triton_off_why(void)
{
    return atomic_load(&g_hookup_on) || g_hookup_applied == 1 ? "handed to Steam natively (Steam Native Hook-up)" : NULL;
}

static void hookup_poll(JNIEnv *env, Uint64 now, bool driver_off)
{
    hk_java_init(env);
    int on = atomic_load(&g_hookup_on) && !driver_off;
    if (on != g_hookup_applied) {
        int was = g_hookup_applied;
        g_hookup_applied = on;
        hk_log(env, "hookup: Steam Native Hook-up %s", on ? "ON" : "OFF");
        if (on) g_hk_changes = 0xffffffffu;   /* look for controllers (incl. a Steam Controller 2026) right away */
        if (!on && was == 1) {
            for (int i = 0; i < HOOKUP_SLOTS; i++) hk_close(env, i, "Steam Native Hook-up off");
            hk_watch_close_all();
            g_tri_changes = 0xffffffffu;   /* triton.h takes a Steam Controller (2026) back at once */
            g_hk_types[0] = 0;
            g_hk_type_count = 0;
            hk_apply_ignore_list(env);
            restart_gamepads(env);   /* SDL takes the controllers back as gamepads */
        }
    }
    if (!on) {
        hookup_run_queue(env);   /* answer stale reads */
        return;
    }
    static Uint64 last_take;
    if (now - last_take > 250) { last_take = now; hookup_take_gamepads(env); }
    Uint32 changes = SDL_hid_device_change_count();
    if (changes != g_hk_changes || now - g_hk_scan_ms > 2000) {
        g_hk_changes = changes;
        g_hk_scan_ms = now;
        hookup_scan(env);
        hk_valve_scan(env);
    }
    hk_valve_poll(env);
    /* reports are read by hk_reader(); here: controllers it saw go away, and Steam's side of the fast path */
    for (int i = 0; i < HOOKUP_SLOTS; i++)
        if (g_hk[i].dev && atomic_load(&g_hk_gone[i])) hk_close(env, i, "unplugged");
    if (now - g_hk_alive_ms > 500) {
        g_hk_alive_ms = now;
        int dropped = 0;
        pthread_mutex_lock(&g_hk_io);
        if (g_hks.base && !hks_alive(&g_hks)) { hks_unmap(&g_hks); dropped = 1; }
        pthread_mutex_unlock(&g_hk_io);
        if (dropped) hk_log(env, "hookup: DroidDeck's session ended; fast path off until it hands over its new shared file");
    }
    hk_serve_fast(env);
    hookup_run_queue(env);
}
