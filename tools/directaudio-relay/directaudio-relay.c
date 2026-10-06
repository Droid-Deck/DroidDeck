/* DirectAudio relay helper - the AAudio side of the driver, out of process.
 *
 * Copyright 2026 The412Banner
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * A small bionic process that owns every AAudio stream on behalf of driver
 * instances that cannot open one themselves (see da_relay_proto.h for why). It
 * listens on a unix socket; each connecting game gets an OUTPUT stream fed from
 * a shared render ring and, when asked, an INPUT stream that fills a shared
 * capture ring. The per-stream behaviour is the in-process driver's, ported
 * line for line where it made sense: LOW_LATENCY by default, adaptive growth on
 * xruns with slow decay, rebuild on route change or a stalled callback, and the
 * live-config mailbox. What is new is the ring hand-off and its own adaptive
 * step: a callback that finds the ring short raises the target the driver keeps
 * queued, one burst at a time, up to a ceiling.
 *
 * The helper must run under the SAME uid as the app that owns the audio
 * permissions (RECORD_AUDIO is checked against the calling uid), which in
 * practice means the host app spawns it exactly as it spawns its PulseAudio
 * daemon today. It exits when its socket is removed or on SIGTERM; a client
 * going away (socket EOF) tears down that client's streams only.
 *
 *   directaudio-relay --socket <path> [--log]
 *
 * Build (NDK r27, API 28, 16 KB-page safe):
 *   aarch64-linux-android28-clang -O2 -Wl,-z,max-page-size=16384 \
 *       directaudio-relay.c -o directaudio-relay -laaudio -llog -pthread
 */
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <linux/futex.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>

#include <aaudio/AAudio.h>
#include <android/log.h>

#include "da_relay_proto.h"

/* ---- logging --------------------------------------------------------------
 * Everything goes to logcat (tag "DA-Relay", the same place the in-process
 * driver's "DirectAudio" events land) and, when --log is given, to stderr so a
 * session log captures it too. Event-level lines are always on: they fire a
 * handful of times per session and are what makes a field report actionable. */
static int g_verbose;

static void rlog(const char *fmt, ...)
{
    va_list ap;
    char buf[512];

    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "DA-Relay", "%s", buf);
    if (g_verbose) fprintf(stderr, "DA-Relay: %s\n", buf);
}
#define REVENT(...) rlog(__VA_ARGS__)
#define RLOG(...)   do { if (g_verbose) rlog(__VA_ARGS__); } while (0)

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void futex_wake_all(volatile uint32_t *word)
{
    syscall(SYS_futex, word, FUTEX_WAKE, INT32_MAX, NULL, NULL, 0);
}

/* ---- tunables (the in-process driver's, same defaults) --------------------- */
#define DA_KNEE               0.75f
#define DA_DEFAULT_MS         12
#define DA_DEFAULT_MAX_MS     100
#define DA_FREEZE_GAP_NS      500000000ull
#define DA_CB_STALL_NS        1000000000ull
#define DA_DECAY_QUIET_NS     10000000000ull
#define DA_DECAY_PUNISH_NS    5000000000ull
#define DA_DECAY_MAX_BACKOFF  32

/* ---- live runtime config (mailbox) ----------------------------------------
 * The path arrives in the first client's hello (every client of one host names
 * the same file). One watcher thread stats it once a second; on change every
 * client's output is rebuilt with the new values, exactly as the in-process
 * driver rebuilds its single stream. */
static char     g_rt_path[256];
static uint64_t g_rt_mtime;
static int      g_rt_ms = -1, g_rt_maxms = -1, g_rt_perf = -1;

static void read_runtime_file(void)
{
    FILE *f;
    char line[128];

    if (!g_rt_path[0] || !(f = fopen(g_rt_path, "r"))) return;
    g_rt_ms = g_rt_maxms = g_rt_perf = -1;
    while (fgets(line, sizeof(line), f))
    {
        char *eq = strchr(line, '=');
        int v;

        if (!eq) continue;
        *eq = 0;
        v = atoi(eq + 1);
        if      (!strcmp(line, "MS"))    g_rt_ms    = v > 0 ? v : -1;
        else if (!strcmp(line, "MAXMS")) g_rt_maxms = v > 0 ? v : -1;
        else if (!strcmp(line, "PERF"))  g_rt_perf  = (v >= 0 && v <= 2) ? v : -1;
    }
    fclose(f);
}

/* ---- a client = one game process --------------------------------------- */
struct client
{
    struct client *next;
    int fd;                       /* the socket */
    int32_t pid;
    char name[64];

    /* launch config, from the hello */
    aaudio_performance_mode_t perf;
    aaudio_sharing_mode_t share;
    int adaptive, decay, watchdog, mic;
    int32_t target_ms, max_ms, target_frames, max_frames;
    uint64_t stall_ns, quiet_ns, punish_ns;
    unsigned int max_backoff;

    /* render: ring + the one OUTPUT stream and its adaptive state */
    pthread_mutex_t lock;
    int out_fd;
    struct da_ring *out;
    AAudioStream *aq;
    int32_t last_xrun;
    uint64_t last_cb_ns, last_xrun_ns, last_decay_ns, wd_last_tick_ns;
    int32_t base_buf_frames, decay_floor;
    unsigned int decay_backoff, cb_count, wd_last_cb;
    int reopen_running, reopen_redo;
    int32_t ring_max_target;      /* ceiling for the ring's own adaptive step */

    /* capture: ring + the one INPUT stream */
    int in_fd;
    struct da_ring *in;
    AAudioStream *cq;
    int32_t in_rate, in_channels;
    aaudio_format_t in_format;
    int mic_started;              /* requestStart issued on the current input */
    int cap_reopen_running, cap_reopen_redo;
    unsigned int cap_cb_count;

    volatile int dead;
};

static pthread_mutex_t g_clients_lock = PTHREAD_MUTEX_INITIALIZER;
static struct client *g_clients;

/* ---- rings --------------------------------------------------------------- */
static int ring_create(uint32_t cap_frames, uint32_t rate, struct da_ring **out, int *fd_out)
{
    size_t bytes = da_ring_bytes(cap_frames);
    int fd = (int)syscall(SYS_memfd_create, "da_ring", 0);
    struct da_ring *r;

    if (fd < 0) { REVENT("memfd_create failed: %d", errno); return -1; }
    if (ftruncate(fd, (off_t)bytes) != 0) { REVENT("ftruncate failed: %d", errno); close(fd); return -1; }
    r = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (r == MAP_FAILED) { REVENT("mmap ring failed: %d", errno); close(fd); return -1; }
    memset(r, 0, sizeof(*r));
    r->magic = DA_RING_MAGIC;
    r->version = DA_RELAY_VERSION;
    r->cap_frames = cap_frames;
    r->rate = rate;
    r->channels = DA_RING_CHANNELS;
    *out = r;
    *fd_out = fd;
    return 0;
}

static void ring_destroy(struct da_ring *r, int fd)
{
    if (r)
    {
        __atomic_store_n(&r->quit, 1, __ATOMIC_SEQ_CST);
        futex_wake_all(&r->wake);
        munmap(r, da_ring_bytes(r->cap_frames));
    }
    if (fd >= 0) close(fd);
}

/* ---- OUTPUT stream ------------------------------------------------------- */
static void request_reopen(struct client *c, const char *why);

/* The AAudio data callback: pull one block from the render ring. This is the
 * in-process mixer_cb with the mixing replaced by a ring read; the bookkeeping
 * after the copy (liveness, adaptive growth, decay) is ported as-is. */
static aaudio_data_callback_result_t out_cb(AAudioStream *aq, void *user,
                                            void *audioData, int32_t numFrames)
{
    struct client *c = user;
    struct da_ring *r = c->out;
    float *out = audioData;
    uint32_t cap = r->cap_frames;
    uint32_t ridx = __atomic_load_n(&r->ridx, __ATOMIC_ACQUIRE);
    uint32_t avail = __atomic_load_n(&r->widx, __ATOMIC_ACQUIRE) - ridx;
    uint32_t n = (uint32_t)numFrames < avail ? (uint32_t)numFrames : avail;
    uint32_t i, first, slot = ridx % cap;
    uint64_t now;

    /* Copy out, at most two memcpy's around the wrap. The soft-knee limiter ran
     * in the game before the samples were queued, so this is a straight copy. */
    first = cap - slot < n ? cap - slot : n;
    memcpy(out, &r->data[(size_t)slot * DA_RING_CHANNELS], (size_t)first * DA_RING_CHANNELS * sizeof(float));
    if (n > first)
        memcpy(out + (size_t)first * DA_RING_CHANNELS, r->data, (size_t)(n - first) * DA_RING_CHANNELS * sizeof(float));
    for (i = n * DA_RING_CHANNELS; i < (uint32_t)numFrames * DA_RING_CHANNELS; i++) out[i] = 0.0f;

    __atomic_store_n(&r->ridx, ridx + n, __ATOMIC_RELEASE);
    __atomic_add_fetch(&r->wake, 1, __ATOMIC_RELEASE);
    futex_wake_all(&r->wake);

    /* Only the live, promoted stream owns the state below (same guard as the
     * in-process build: an outgoing stream still gets a callback or two). */
    if (aq != __atomic_load_n(&c->aq, __ATOMIC_SEQ_CST) && __atomic_load_n(&c->aq, __ATOMIC_SEQ_CST))
        return AAUDIO_CALLBACK_RESULT_CONTINUE;

    now = now_ns();
    {
        uint64_t prev = __atomic_exchange_n(&c->last_cb_ns, now, __ATOMIC_SEQ_CST);
        if (prev && now - prev > DA_FREEZE_GAP_NS)
        {
            c->last_xrun = AAudioStream_getXRunCount(aq);
            c->last_xrun_ns = now;
        }
    }
    __atomic_add_fetch(&c->cb_count, 1, __ATOMIC_SEQ_CST);
    __atomic_store_n(&r->cb_count, c->cb_count, __ATOMIC_RELAXED);

    /* The ring's own adaptive step. A short ring means the game's pump thread
     * did not get scheduled in time - the equivalent of an xrun one stage
     * earlier - so ask it to keep one more burst queued. Only grows; the AAudio
     * buffer below is where decay lives, and the ring stays a small fixed cost
     * on top of it. */
    if (n < (uint32_t)numFrames)
    {
        __atomic_add_fetch(&r->underruns, 1, __ATOMIC_RELAXED);
        if (c->adaptive)
        {
            int32_t burst = AAudioStream_getFramesPerBurst(aq);
            int32_t cur = __atomic_load_n(&r->target_frames, __ATOMIC_RELAXED);
            int32_t want = cur + (burst > 0 ? burst : numFrames);

            if (want > c->ring_max_target) want = c->ring_max_target;
            if (want > cur)
            {
                __atomic_store_n(&r->target_frames, want, __ATOMIC_RELEASE);
                RLOG("[%d] ring grow: target %d -> %d frames (short by %u)", c->pid, cur, want,
                     (uint32_t)numFrames - n);
            }
        }
    }

    if (c->adaptive)
    {
        int32_t xruns = AAudioStream_getXRunCount(aq);
        int32_t burst = AAudioStream_getFramesPerBurst(aq);
        int32_t cur = AAudioStream_getBufferSizeInFrames(aq);

        if (xruns > c->last_xrun)
        {
            int32_t capf = AAudioStream_getBufferCapacityInFrames(aq);
            int32_t want = cur + (burst > 0 ? burst : 1);

            if (c->max_frames > 0 && want > c->max_frames) want = c->max_frames;
            if (want > capf) want = capf;
            if (want > cur) AAudioStream_setBufferSizeInFrames(aq, want);

            if (c->decay && c->last_decay_ns && now - c->last_decay_ns < c->punish_ns)
            {
                if (want > c->decay_floor)
                {
                    c->decay_floor = want;
                    REVENT("[%d] decay floor: %d frames (%d ms) - not probing below it",
                           c->pid, want, want * 1000 / DA_RING_RATE);
                }
                if (c->decay_backoff < c->max_backoff) c->decay_backoff *= 2;
            }
            else RLOG("[%d] grow: buf %d -> %d frames (xruns %d)", c->pid, cur, want, xruns);
            c->last_xrun = xruns;
            c->last_xrun_ns = now;
            __atomic_store_n(&r->hw_buf_frames, AAudioStream_getBufferSizeInFrames(aq), __ATOMIC_RELAXED);
        }
        else if (c->decay && burst > 0)
        {
            uint64_t quiet = c->quiet_ns * c->decay_backoff;
            int32_t floor = c->base_buf_frames;

            if (c->decay_floor > floor) floor = c->decay_floor;
            if (cur > floor && c->last_xrun_ns && now - c->last_xrun_ns > quiet &&
                (!c->last_decay_ns || now - c->last_decay_ns > quiet))
            {
                int32_t want = cur - burst;

                if (want < floor) want = floor;
                if (want < cur)
                {
                    AAudioStream_setBufferSizeInFrames(aq, want);
                    c->last_decay_ns = now;
                    __atomic_store_n(&r->hw_buf_frames, AAudioStream_getBufferSizeInFrames(aq), __ATOMIC_RELAXED);
                    RLOG("[%d] decay: buf %d -> %d frames (floor %d)", c->pid, cur, want, floor);
                }
            }
        }
    }

    if (g_verbose && !(c->cb_count % 1000))
        RLOG("[%d] hb: cb=%u buf=%d cap=%d xruns=%d ring=%u/%d under=%u", c->pid, c->cb_count,
             AAudioStream_getBufferSizeInFrames(aq), AAudioStream_getBufferCapacityInFrames(aq),
             AAudioStream_getXRunCount(aq), avail, r->target_frames, r->underruns);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void out_error_cb(AAudioStream *aq, void *user, aaudio_result_t error)
{
    struct client *c = user;

    if (aq != __atomic_load_n(&c->aq, __ATOMIC_SEQ_CST)) return;
    if (error != AAUDIO_ERROR_DISCONNECTED &&
        error != AAUDIO_ERROR_INVALID_STATE &&
        error != AAUDIO_ERROR_INVALID_HANDLE &&
        error != AAUDIO_ERROR_TIMEOUT)
        return;
    request_reopen(c, "stream error");
}

static void apply_runtime_overrides(struct client *c)
{
    if (g_rt_ms   >= 0) { c->target_ms = g_rt_ms; c->target_frames = 0; }
    if (g_rt_maxms > 0)   c->max_frames = g_rt_maxms * DA_RING_RATE / 1000;
    if      (g_rt_perf == 0) c->perf = AAUDIO_PERFORMANCE_MODE_NONE;
    else if (g_rt_perf == 1) c->perf = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    else if (g_rt_perf == 2) c->perf = AAUDIO_PERFORMANCE_MODE_POWER_SAVING;
}

/* Port of mixer_open_stream: 48 kHz / float / stereo, buffer from the ms or
 * frame form rounded up to a burst, capacity from the ceiling. */
static aaudio_result_t open_output(struct client *c, AAudioStream **out)
{
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *aq = NULL;
    aaudio_result_t r;

    apply_runtime_overrides(c);
    r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK || !builder) return r != AAUDIO_OK ? r : AAUDIO_ERROR_NO_MEMORY;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, c->share);
    AAudioStreamBuilder_setPerformanceMode(builder, c->perf);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, DA_RING_CHANNELS);
    AAudioStreamBuilder_setSampleRate(builder, DA_RING_RATE);
    AAudioStreamBuilder_setDataCallback(builder, out_cb, c);
    AAudioStreamBuilder_setErrorCallback(builder, out_error_cb, c);
    {
        int32_t cap_req = c->max_frames > 0 ? c->max_frames : DA_DEFAULT_MAX_MS * DA_RING_RATE / 1000;
        if (cap_req > 0) AAudioStreamBuilder_setBufferCapacityInFrames(builder, cap_req);
    }

    r = AAudioStreamBuilder_openStream(builder, &aq);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK || !aq) return r != AAUDIO_OK ? r : AAUDIO_ERROR_INTERNAL;

    {
        int32_t capf = AAudioStream_getBufferCapacityInFrames(aq);
        int32_t burst = AAudioStream_getFramesPerBurst(aq);
        int32_t want;

        if (c->target_frames > 0 && c->target_ms <= 0)
            want = c->target_frames;
        else
        {
            int32_t ms = c->target_ms > 0 ? c->target_ms : DA_DEFAULT_MS;
            want = ms * DA_RING_RATE / 1000;
            if (burst > 0)
            {
                int32_t nb = (want + burst - 1) / burst;
                want = (nb < 1 ? 1 : nb) * burst;
            }
        }
        if (want > capf) want = capf;
        if (want > 0) AAudioStream_setBufferSizeInFrames(aq, want);

        /* The ring's initial target: DA_RING_TARGET_BURSTS bursts of the real
         * burst size, ceiling DA_RING_MAX_TARGET_MS. Set on every (re)open so a
         * recovered stream starts low again, like the buffer does. */
        if (burst <= 0) burst = 192;
        c->ring_max_target = DA_RING_MAX_TARGET_MS * DA_RING_RATE / 1000;
        if (c->ring_max_target > (int32_t)c->out->cap_frames) c->ring_max_target = c->out->cap_frames;
        __atomic_store_n(&c->out->target_frames, DA_RING_TARGET_BURSTS * burst, __ATOMIC_RELEASE);
        __atomic_store_n(&c->out->hw_burst, burst, __ATOMIC_RELAXED);
    }
    c->last_xrun = AAudioStream_getXRunCount(aq);
    c->base_buf_frames = AAudioStream_getBufferSizeInFrames(aq);
    c->decay_floor = 0;
    c->decay_backoff = 1;
    c->last_xrun_ns = now_ns();
    c->last_decay_ns = 0;
    __atomic_store_n(&c->out->hw_buf_frames, c->base_buf_frames, __ATOMIC_RELAXED);

    REVENT("[%d] open: buffer %d frames (%d ms) burst %d cap %d perf %d sharing req=%d got=%d"
           " ring target %d - device adds its own output latency",
           c->pid, c->base_buf_frames, c->base_buf_frames * 1000 / DA_RING_RATE,
           AAudioStream_getFramesPerBurst(aq), AAudioStream_getBufferCapacityInFrames(aq),
           AAudioStream_getPerformanceMode(aq), c->share, AAudioStream_getSharingMode(aq),
           c->out->target_frames);

    r = AAudioStream_requestStart(aq);
    if (r != AAUDIO_OK) { AAudioStream_close(aq); return r; }

    __atomic_store_n(&c->last_cb_ns, now_ns(), __ATOMIC_SEQ_CST);
    c->wd_last_tick_ns = 0;
    __atomic_store_n(&c->out->state, DA_RING_PLAYING, __ATOMIC_RELEASE);
    *out = aq;
    return AAUDIO_OK;
}

static void *reopen_thread(void *user)
{
    struct client *c = user;

    for (;;)
    {
        AAudioStream *old = NULL, *neu = NULL;
        int expected = 0;

        __atomic_store_n(&c->reopen_redo, 0, __ATOMIC_SEQ_CST);
        if (c->dead) break;

        if (open_output(c, &neu) == AAUDIO_OK)
        {
            pthread_mutex_lock(&c->lock);
            old = c->aq;
            __atomic_store_n(&c->aq, neu, __ATOMIC_SEQ_CST);
            pthread_mutex_unlock(&c->lock);
        }
        else REVENT("[%d] reopen failed; keeping old stream", c->pid);

        if (old)
        {
            AAudioStream_requestStop(old);
            AAudioStream_close(old);
            RLOG("[%d] reopened output", c->pid);
        }

        if (__atomic_load_n(&c->reopen_redo, __ATOMIC_SEQ_CST)) { usleep(20000); continue; }
        __atomic_store_n(&c->reopen_running, 0, __ATOMIC_SEQ_CST);
        if (!__atomic_load_n(&c->reopen_redo, __ATOMIC_SEQ_CST)) break;
        if (!__atomic_compare_exchange_n(&c->reopen_running, &expected, 1, 0,
                                         __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
            break;
    }
    return NULL;
}

static void request_reopen(struct client *c, const char *why)
{
    int expected = 0;

    __atomic_store_n(&c->reopen_redo, 1, __ATOMIC_SEQ_CST);
    if (__atomic_compare_exchange_n(&c->reopen_running, &expected, 1, 0,
                                    __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
    {
        pthread_t th;
        REVENT("[%d] reopen: %s", c->pid, why);
        if (pthread_create(&th, NULL, reopen_thread, c))
            __atomic_store_n(&c->reopen_running, 0, __ATOMIC_SEQ_CST);
        else
            pthread_detach(th);
    }
}

/* Port of mixer_watchdog, driven from the housekeeping thread once a second
 * (the in-process build ticks it per mmdevapi period; the stall threshold is
 * one second, so a one-second tick still trips within two). */
static void watchdog(struct client *c)
{
    uint64_t now = now_ns(), last_cb, prev_tick;
    unsigned int cb;

    if (!c->watchdog) return;
    if (!__atomic_load_n(&c->aq, __ATOMIC_SEQ_CST)) return;

    prev_tick = c->wd_last_tick_ns;
    c->wd_last_tick_ns = now;
    cb = __atomic_load_n(&c->cb_count, __ATOMIC_SEQ_CST);

    if (!prev_tick || now - prev_tick > 2 * DA_FREEZE_GAP_NS + 1000000000ull)
    {
        c->wd_last_cb = cb;
        __atomic_store_n(&c->last_cb_ns, now, __ATOMIC_SEQ_CST);
        return;
    }
    if (cb != c->wd_last_cb) { c->wd_last_cb = cb; return; }

    last_cb = __atomic_load_n(&c->last_cb_ns, __ATOMIC_SEQ_CST);
    if (!last_cb || now - last_cb < c->stall_ns) return;

    __atomic_store_n(&c->last_cb_ns, now, __ATOMIC_SEQ_CST);
    request_reopen(c, "data callback stalled");
}

/* ---- INPUT stream (microphone) ------------------------------------------- */
static void cap_request_reopen(struct client *c, const char *why);

/* The input callback: convert whatever AAudio granted (float or I16, mono or
 * stereo) to float stereo and queue it. The rate is left as granted; the
 * driver resamples per voice exactly as the in-process capture_fill_voice does. */
static aaudio_data_callback_result_t in_cb(AAudioStream *aq, void *user,
                                           void *audioData, int32_t numFrames)
{
    struct client *c = user;
    struct da_ring *r = c->in;
    uint32_t cap, widx, space, n, i;

    __atomic_add_fetch(&c->cap_cb_count, 1, __ATOMIC_RELAXED);
    if (aq != __atomic_load_n(&c->cq, __ATOMIC_SEQ_CST) && __atomic_load_n(&c->cq, __ATOMIC_SEQ_CST))
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    if (!r) return AAUDIO_CALLBACK_RESULT_CONTINUE;

    cap = r->cap_frames;
    widx = __atomic_load_n(&r->widx, __ATOMIC_RELAXED);
    space = cap - (widx - __atomic_load_n(&r->ridx, __ATOMIC_ACQUIRE));
    n = (uint32_t)numFrames < space ? (uint32_t)numFrames : space;   /* overrun: drop the newest */

    for (i = 0; i < n; i++)
    {
        uint32_t slot = (widx + i) % cap;
        float L, R;

        if (c->in_format == AAUDIO_FORMAT_PCM_I16)
        {
            const int16_t *p = (const int16_t *)audioData + (size_t)i * c->in_channels;
            L = p[0] * (1.0f / 32768.0f);
            R = c->in_channels > 1 ? p[1] * (1.0f / 32768.0f) : L;
        }
        else
        {
            const float *p = (const float *)audioData + (size_t)i * c->in_channels;
            L = p[0];
            R = c->in_channels > 1 ? p[1] : p[0];
        }
        r->data[(size_t)slot * 2] = L;
        r->data[(size_t)slot * 2 + 1] = R;
    }
    __atomic_store_n(&r->widx, widx + n, __ATOMIC_RELEASE);
    __atomic_add_fetch(&r->wake, 1, __ATOMIC_RELEASE);
    futex_wake_all(&r->wake);
    if (n < (uint32_t)numFrames) __atomic_add_fetch(&r->underruns, 1, __ATOMIC_RELAXED);
    __atomic_store_n(&r->cb_count, c->cap_cb_count, __ATOMIC_RELAXED);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void in_error_cb(AAudioStream *aq, void *user, aaudio_result_t error)
{
    struct client *c = user;

    if (aq != __atomic_load_n(&c->cq, __ATOMIC_SEQ_CST)) return;
    if (error != AAUDIO_ERROR_DISCONNECTED &&
        error != AAUDIO_ERROR_INVALID_STATE &&
        error != AAUDIO_ERROR_INVALID_HANDLE &&
        error != AAUDIO_ERROR_TIMEOUT)
        return;
    cap_request_reopen(c, "capture stream error");
}

/* Port of capture_open_stream: open (NOT start) 48 kHz / float / stereo with
 * the VOICE_COMMUNICATION preset for platform AEC / noise suppression / AGC. */
static aaudio_result_t open_input(struct client *c, AAudioStream **out)
{
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *aq = NULL;
    aaudio_result_t r;

    r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK || !builder) return r != AAUDIO_OK ? r : AAUDIO_ERROR_NO_MEMORY;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, c->perf);
    AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, DA_RING_CHANNELS);
    AAudioStreamBuilder_setSampleRate(builder, DA_RING_RATE);
    AAudioStreamBuilder_setDataCallback(builder, in_cb, c);
    AAudioStreamBuilder_setErrorCallback(builder, in_error_cb, c);

    r = AAudioStreamBuilder_openStream(builder, &aq);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK || !aq) return r != AAUDIO_OK ? r : AAUDIO_ERROR_INTERNAL;

    REVENT("[%d] capture open: req 48000/float/2ch preset=voicecomm perf=%d - got rate=%d ch=%d fmt=%d",
           c->pid, c->perf, AAudioStream_getSampleRate(aq), AAudioStream_getChannelCount(aq),
           AAudioStream_getFormat(aq));
    *out = aq;
    return AAUDIO_OK;
}

static void note_input_geometry(struct client *c, AAudioStream *aq)
{
    c->in_rate = AAudioStream_getSampleRate(aq);
    c->in_channels = AAudioStream_getChannelCount(aq);
    c->in_format = AAudioStream_getFormat(aq);
    if (c->in_rate <= 0) c->in_rate = DA_RING_RATE;
    if (c->in_channels <= 0) c->in_channels = DA_RING_CHANNELS;
}

static void *cap_reopen_thread(void *user)
{
    struct client *c = user;

    for (;;)
    {
        AAudioStream *old = NULL, *neu = NULL;
        int expected = 0, want_start;

        __atomic_store_n(&c->cap_reopen_redo, 0, __ATOMIC_SEQ_CST);
        if (c->dead) break;

        pthread_mutex_lock(&c->lock);
        want_start = c->mic_started;
        pthread_mutex_unlock(&c->lock);

        if (open_input(c, &neu) == AAUDIO_OK)
        {
            pthread_mutex_lock(&c->lock);
            old = c->cq;
            note_input_geometry(c, neu);
            /* The ring's rate is what the driver resamples from. A reopen on a
             * different route can change it (a BT headset mic at 16 kHz); the
             * driver re-reads the header per block, so publish before promoting. */
            if (c->in) __atomic_store_n(&c->in->rate, (uint32_t)c->in_rate, __ATOMIC_RELEASE);
            __atomic_store_n(&c->cq, neu, __ATOMIC_SEQ_CST);
            c->mic_started = 0;
            pthread_mutex_unlock(&c->lock);

            if (want_start)
            {
                if (AAudioStream_requestStart(neu) == AAUDIO_OK)
                {
                    pthread_mutex_lock(&c->lock);
                    c->mic_started = 1;
                    pthread_mutex_unlock(&c->lock);
                }
                else REVENT("[%d] capture reopen: requestStart failed", c->pid);
            }
        }
        else REVENT("[%d] capture reopen failed; keeping old stream", c->pid);

        if (old)
        {
            AAudioStream_requestStop(old);
            AAudioStream_close(old);
        }

        if (__atomic_load_n(&c->cap_reopen_redo, __ATOMIC_SEQ_CST)) { usleep(20000); continue; }
        __atomic_store_n(&c->cap_reopen_running, 0, __ATOMIC_SEQ_CST);
        if (!__atomic_load_n(&c->cap_reopen_redo, __ATOMIC_SEQ_CST)) break;
        if (!__atomic_compare_exchange_n(&c->cap_reopen_running, &expected, 1, 0,
                                         __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
            break;
    }
    return NULL;
}

static void cap_request_reopen(struct client *c, const char *why)
{
    int expected = 0;

    __atomic_store_n(&c->cap_reopen_redo, 1, __ATOMIC_SEQ_CST);
    if (__atomic_compare_exchange_n(&c->cap_reopen_running, &expected, 1, 0,
                                    __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
    {
        pthread_t th;
        REVENT("[%d] capture reopen: %s", c->pid, why);
        if (pthread_create(&th, NULL, cap_reopen_thread, c))
            __atomic_store_n(&c->cap_reopen_running, 0, __ATOMIC_SEQ_CST);
        else
            pthread_detach(th);
    }
}

static void mic_start(struct client *c)
{
    AAudioStream *aq = NULL;

    pthread_mutex_lock(&c->lock);
    if (c->cq && !c->mic_started) { c->mic_started = 1; aq = c->cq; }
    pthread_mutex_unlock(&c->lock);
    if (!aq) return;
    if (AAudioStream_requestStart(aq) != AAUDIO_OK)
    {
        REVENT("[%d] capture requestStart failed", c->pid);
        pthread_mutex_lock(&c->lock);
        c->mic_started = 0;
        pthread_mutex_unlock(&c->lock);
    }
    else REVENT("[%d] capture start", c->pid);
}

static void mic_stop(struct client *c)
{
    AAudioStream *aq = NULL;

    pthread_mutex_lock(&c->lock);
    if (c->cq && c->mic_started) { c->mic_started = 0; aq = c->cq; }
    pthread_mutex_unlock(&c->lock);
    if (!aq) return;
    AAudioStream_requestStop(aq);
    REVENT("[%d] capture stop: no voices", c->pid);
}

/* ---- client lifecycle ---------------------------------------------------- */
static int read_full(int fd, void *buf, size_t len)
{
    char *p = buf;
    while (len)
    {
        ssize_t n = read(fd, p, len);
        if (n < 0) { if (errno == EINTR) continue; return -1; }
        if (n == 0) return -1;
        p += n; len -= (size_t)n;
    }
    return 0;
}

static int send_ack(int sock, const struct da_ack *ack, const int *fds, int nfds)
{
    struct msghdr msg;
    struct iovec iov;
    char cbuf[CMSG_SPACE(sizeof(int) * 2)];
    struct cmsghdr *cm;

    memset(&msg, 0, sizeof(msg));
    memset(cbuf, 0, sizeof(cbuf));
    iov.iov_base = (void *)ack;
    iov.iov_len = sizeof(*ack);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    if (nfds > 0)
    {
        msg.msg_control = cbuf;
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
        cm = CMSG_FIRSTHDR(&msg);
        cm->cmsg_level = SOL_SOCKET;
        cm->cmsg_type = SCM_RIGHTS;
        cm->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
        memcpy(CMSG_DATA(cm), fds, sizeof(int) * nfds);
    }
    return sendmsg(sock, &msg, MSG_NOSIGNAL) == (ssize_t)sizeof(*ack) ? 0 : -1;
}

static void client_teardown(struct client *c)
{
    AAudioStream *aq, *cq;

    c->dead = 1;
    pthread_mutex_lock(&g_clients_lock);
    {
        struct client **pp = &g_clients;
        while (*pp && *pp != c) pp = &(*pp)->next;
        if (*pp) *pp = c->next;
    }
    pthread_mutex_unlock(&g_clients_lock);

    /* Let any in-flight reopen worker finish with the stream it is holding. */
    while (__atomic_load_n(&c->reopen_running, __ATOMIC_SEQ_CST) ||
           __atomic_load_n(&c->cap_reopen_running, __ATOMIC_SEQ_CST))
        usleep(10000);

    pthread_mutex_lock(&c->lock);
    aq = c->aq; c->aq = NULL;
    cq = c->cq; c->cq = NULL;
    pthread_mutex_unlock(&c->lock);
    if (aq) { AAudioStream_requestStop(aq); AAudioStream_close(aq); }
    if (cq) { AAudioStream_requestStop(cq); AAudioStream_close(cq); }

    REVENT("[%d] gone (%s): cb=%u ring underruns=%u", c->pid, c->name, c->cb_count,
           c->out ? c->out->underruns : 0u);
    ring_destroy(c->out, c->out_fd);
    ring_destroy(c->in, c->in_fd);
    if (c->fd >= 0) close(c->fd);
    pthread_mutex_destroy(&c->lock);
    free(c);
}

static void *client_thread(void *user)
{
    struct client *c = user;
    struct da_hello h;
    struct da_ack ack;
    int fds[2] = { -1, -1 }, nfds = 0;
    aaudio_result_t r;

    if (read_full(c->fd, &h, sizeof(h)) != 0 || h.magic != DA_HELLO_MAGIC)
    {
        /* Usually the driver's test_connect probe: connect, close, no hello. */
        RLOG("connection closed before a hello (probe?)");
        close(c->fd); c->fd = -1;
        client_teardown(c);
        return NULL;
    }
    if (h.version != DA_RELAY_VERSION)
    {
        REVENT("protocol mismatch: driver %u, helper %u - refusing", h.version, DA_RELAY_VERSION);
        memset(&ack, 0, sizeof(ack));
        ack.magic = DA_ACK_MAGIC;
        ack.status = AAUDIO_ERROR_UNIMPLEMENTED;
        send_ack(c->fd, &ack, NULL, 0);
        close(c->fd); c->fd = -1;
        client_teardown(c);
        return NULL;
    }

    c->pid = h.pid;
    memcpy(c->name, h.name, sizeof(c->name));
    c->name[sizeof(c->name) - 1] = 0;
    c->perf = h.perf == 0 ? AAUDIO_PERFORMANCE_MODE_NONE :
              h.perf == 2 ? AAUDIO_PERFORMANCE_MODE_POWER_SAVING : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    c->share = (h.flags & DA_HELLO_EXCLUSIVE) ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED;
    c->adaptive = !!(h.flags & DA_HELLO_ADAPTIVE);
    c->decay = !!(h.flags & DA_HELLO_DECAY);
    c->watchdog = !!(h.flags & DA_HELLO_WATCHDOG);
    c->mic = !!(h.flags & DA_HELLO_MIC);
    c->target_ms = h.target_ms;
    c->max_ms = h.max_ms;
    c->target_frames = h.target_frames;
    c->max_frames = h.max_frames;
    if (c->max_ms > 0) c->max_frames = c->max_ms * DA_RING_RATE / 1000;
    c->stall_ns  = h.stall_ms  ? (uint64_t)h.stall_ms  * 1000000ull : DA_CB_STALL_NS;
    c->quiet_ns  = h.quiet_ms  ? (uint64_t)h.quiet_ms  * 1000000ull : DA_DECAY_QUIET_NS;
    c->punish_ns = h.punish_ms ? (uint64_t)h.punish_ms * 1000000ull : DA_DECAY_PUNISH_NS;
    c->max_backoff = h.max_backoff ? h.max_backoff : DA_DECAY_MAX_BACKOFF;
    if (h.log) g_verbose = 1;
    if (h.runtime_path[0] && !g_rt_path[0])
    {
        memcpy(g_rt_path, h.runtime_path, sizeof(g_rt_path));
        g_rt_path[sizeof(g_rt_path) - 1] = 0;
        read_runtime_file();
    }

    REVENT("[%d] hello from %s: perf=%d share=%d adaptive=%d decay=%d watchdog=%d mic=%d"
           " ms=%d maxms=%d bf=%d mbf=%d", c->pid, c->name, h.perf, c->share, c->adaptive,
           c->decay, c->watchdog, c->mic, c->target_ms, c->max_ms, c->target_frames, c->max_frames);

    memset(&ack, 0, sizeof(ack));
    ack.magic = DA_ACK_MAGIC;

    /* render ring + output stream */
    if (ring_create(DA_RING_CAP_MS * DA_RING_RATE / 1000, DA_RING_RATE, &c->out, &c->out_fd) != 0)
    {
        ack.status = AAUDIO_ERROR_NO_MEMORY;
        goto reply;
    }
    r = open_output(c, &c->aq);
    if (r != AAUDIO_OK)
    {
        REVENT("[%d] output open failed: %d", c->pid, r);
        ack.status = r;
        goto reply;
    }
    ack.rate = AAudioStream_getSampleRate(c->aq);
    ack.burst = AAudioStream_getFramesPerBurst(c->aq);
    ack.buf_frames = AAudioStream_getBufferSizeInFrames(c->aq);
    ack.out_cap_frames = (int32_t)c->out->cap_frames;
    fds[nfds++] = c->out_fd;

    /* capture ring + input stream, only when asked. A mic that will not open
     * (RECORD_AUDIO not granted to the uid) is reported, not fatal: the game
     * gets its output and the driver invalidates its capture endpoint. */
    if (c->mic)
    {
        r = open_input(c, &c->cq);
        if (r != AAUDIO_OK)
        {
            REVENT("[%d] capture open failed: %d - mic unavailable", c->pid, r);
            ack.flags |= DA_ACK_MIC_FAILED;
        }
        else
        {
            note_input_geometry(c, c->cq);
            if (ring_create((uint32_t)((int64_t)DA_CAPTURE_RING_MS * c->in_rate / 1000),
                            (uint32_t)c->in_rate, &c->in, &c->in_fd) != 0)
            {
                AAudioStream_close(c->cq); c->cq = NULL;
                ack.flags |= DA_ACK_MIC_FAILED;
            }
            else
            {
                ack.in_cap_frames = (int32_t)c->in->cap_frames;
                ack.in_rate = c->in_rate;
                __atomic_store_n(&c->in->state, DA_RING_PLAYING, __ATOMIC_RELEASE);
                fds[nfds++] = c->in_fd;
            }
        }
    }

reply:
    if (send_ack(c->fd, &ack, fds, nfds) != 0 || ack.status != 0)
    {
        if (ack.status == 0) REVENT("[%d] ack send failed: %d", c->pid, errno);
        client_teardown(c);
        return NULL;
    }
    REVENT("[%d] serving: out ring %d frames, in ring %d frames @ %d", c->pid,
           ack.out_cap_frames, ack.in_cap_frames, ack.in_rate);

    /* Now just listen: mic start/stop, and EOF when the game exits. */
    for (;;)
    {
        struct da_msg m;

        if (read_full(c->fd, &m, sizeof(m)) != 0) break;
        switch (m.type)
        {
        case DA_MSG_MIC_START: mic_start(c); break;
        case DA_MSG_MIC_STOP:  mic_stop(c);  break;
        case DA_MSG_PING:      break;
        default: RLOG("[%d] unknown msg %u", c->pid, m.type); break;
        }
    }
    client_teardown(c);
    return NULL;
}

/* ---- housekeeping: watchdog tick + mailbox watch, once a second ------------- */
static void *housekeeping_thread(void *unused)
{
    struct timespec iv = { 1, 0 };

    (void)unused;
    for (;;)
    {
        struct client *c;
        int reload = 0;

        nanosleep(&iv, NULL);

        if (g_rt_path[0])
        {
            struct stat st;
            if (stat(g_rt_path, &st) == 0)
            {
                uint64_t m = (uint64_t)st.st_mtim.tv_sec * 1000000000ull + (uint64_t)st.st_mtim.tv_nsec;
                if (m != g_rt_mtime)
                {
                    g_rt_mtime = m;
                    read_runtime_file();
                    REVENT("runtime: reload ms=%d maxms=%d perf=%d", g_rt_ms, g_rt_maxms, g_rt_perf);
                    reload = 1;
                }
            }
        }

        pthread_mutex_lock(&g_clients_lock);
        for (c = g_clients; c; c = c->next)
        {
            if (c->dead) continue;
            watchdog(c);
            if (reload && __atomic_load_n(&c->aq, __ATOMIC_SEQ_CST))
                request_reopen(c, "runtime config change");
        }
        pthread_mutex_unlock(&g_clients_lock);
    }
    return NULL;
}

/* ---- main ---------------------------------------------------------------- */
static volatile sig_atomic_t g_stop;
static void on_term(int sig) { (void)sig; g_stop = 1; }

int main(int argc, char **argv)
{
    const char *path = NULL;
    struct sockaddr_un addr;
    pthread_t th;
    int srv, i;

    for (i = 1; i < argc; i++)
    {
        if (!strcmp(argv[i], "--socket") && i + 1 < argc) path = argv[++i];
        else if (!strcmp(argv[i], "--log")) g_verbose = 1;
        else { fprintf(stderr, "usage: directaudio-relay --socket <path> [--log]\n"); return 2; }
    }
    if (!path)
    {
        const char *rt = getenv("XDG_RUNTIME_DIR");
        static char def[512];
        if (!rt) { fprintf(stderr, "directaudio-relay: --socket required\n"); return 2; }
        snprintf(def, sizeof(def), "%s/%s", rt, DA_RELAY_DEFAULT_NAME);
        path = def;
    }
    if (strlen(path) >= sizeof(addr.sun_path)) { fprintf(stderr, "socket path too long\n"); return 2; }

    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);

    srv = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (srv < 0) { REVENT("socket: %d", errno); return 1; }
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strcpy(addr.sun_path, path);
    unlink(path);
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0) { REVENT("bind %s: %d", path, errno); return 1; }
    chmod(path, 0666);
    if (listen(srv, 8) != 0) { REVENT("listen: %d", errno); return 1; }

    if (pthread_create(&th, NULL, housekeeping_thread, NULL) == 0) pthread_detach(th);
    REVENT("listening on %s (protocol v%d, pid %d)", path, DA_RELAY_VERSION, (int)getpid());

    while (!g_stop)
    {
        struct client *c;
        int fd = accept4(srv, NULL, NULL, SOCK_CLOEXEC);

        if (fd < 0)
        {
            if (errno == EINTR) continue;
            REVENT("accept: %d", errno);
            break;
        }
        c = calloc(1, sizeof(*c));
        if (!c) { close(fd); continue; }
        pthread_mutex_init(&c->lock, NULL);
        c->fd = fd;
        c->out_fd = c->in_fd = -1;
        pthread_mutex_lock(&g_clients_lock);
        c->next = g_clients;
        g_clients = c;
        pthread_mutex_unlock(&g_clients_lock);
        if (pthread_create(&th, NULL, client_thread, c) == 0) pthread_detach(th);
        else client_teardown(c);
    }

    REVENT("exiting");
    close(srv);
    unlink(path);
    return 0;
}
