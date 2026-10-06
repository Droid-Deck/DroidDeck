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
 *   directaudio-relay --socket <path> [--mic-fifo <path>] [--log]
 *
 * --mic-fifo also serves the microphone to a NON-Wine consumer: the Steam client
 * reads its mic from PulseAudio, so the helper writes the same capture as raw
 * s16le / 48000 Hz / mono into a named pipe that PulseAudio's module-pipe-source
 * turns into a source. See "the microphone" below for how the one stream is
 * shared between games and the pipe.
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

    /* capture: this game's ring, fed by the shared microphone (see g_mic) */
    int in_fd;
    struct da_ring *in;
    int mic_hot;                  /* this game asked for the mic to be started */

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

/* ---- the microphone: one INPUT stream, fanned out ---------------------------
 *
 * Android grants recording to the uid that holds RECORD_AUDIO, which is this
 * process, so this is the one place a microphone can be opened for anyone
 * downstream. There are two kinds of downstream: a GAME, under Proton, whose
 * driver instance asked for a capture ring in its hello; and the STEAM CLIENT,
 * a native program that never touches Wine and reads its microphone from
 * PulseAudio - fed here through a FIFO that module-pipe-source turns into a
 * PulseAudio source (--mic-fifo).
 *
 * Both want the same microphone, so there is one stream and every consumer gets
 * a copy of every block: in-game voice and Steam voice chat are different
 * features and only one of them transmits at a time, so hearing the same mic in
 * both is the behaviour a user expects, not a conflict. The stream is opened
 * (not started) on the first consumer that registers, made hot while at least
 * one consumer WANTS it - a game between its first capture Start and its last
 * capture voice going away, the FIFO while a reader is connected and draining -
 * and stopped when none does, so the OS recording indicator tells the truth.
 *
 * Game rings carry float stereo at the rate the stream was granted (the driver
 * resamples per voice, as its in-process build does). The FIFO carries a FIXED
 * format, s16le / 48000 Hz / mono, resampled here when the grant differs: a
 * pipe has no clock, so the rate PulseAudio is told has to be the rate the bytes
 * really are, whatever the route (a Bluetooth headset mic is often 16 kHz). */

#define MIC_FIFO_RATE      48000
#define MIC_FIFO_RING      (MIC_FIFO_RATE * 2)     /* 2 s of s16 mono */
#define MIC_FIFO_CHUNK     480                     /* 10 ms per write */
#define MIC_FIFO_IDLE_NS   2000000000ull           /* pipe full this long => nobody is reading */

struct mic_fifo
{
    char path[512];
    int fd;                       /* -1 until a reader has the other end open */
    int hot;                      /* counted in g_mic.want */
    pthread_t th;

    /* private SPSC ring, in_cb -> writer thread */
    int16_t *buf;
    volatile uint32_t widx, ridx, wake;
    double rs_pos;                /* resampler read position within the current block */
    float last_l, last_r;         /* the previous block's final frame, for interpolation */
    uint32_t dropped;
};

static struct
{
    pthread_mutex_t lock;
    AAudioStream *cq;
    int32_t in_rate, in_channels;
    aaudio_format_t in_format;
    aaudio_performance_mode_t perf;
    int opened;                   /* a stream exists (may be stopped) */
    int started;                  /* requestStart issued on the current stream */
    int want;                     /* consumers that want it hot */
    int reopen_running, reopen_redo;
    unsigned int cb_count;
    struct mic_fifo *fifo;        /* NULL unless --mic-fifo */
} g_mic = { .lock = PTHREAD_MUTEX_INITIALIZER };

static void mic_request_reopen(const char *why);

/* one granted input frame -> float L/R, whatever the granted format/channels */
static inline void mic_frame(const void *buf, int32_t i, float *L, float *R)
{
    if (g_mic.in_format == AAUDIO_FORMAT_PCM_I16)
    {
        const int16_t *p = (const int16_t *)buf + (size_t)i * g_mic.in_channels;
        *L = p[0] * (1.0f / 32768.0f);
        *R = g_mic.in_channels > 1 ? p[1] * (1.0f / 32768.0f) : *L;
    }
    else
    {
        const float *p = (const float *)buf + (size_t)i * g_mic.in_channels;
        *L = p[0];
        *R = g_mic.in_channels > 1 ? p[1] : p[0];
    }
}

/* a game's capture ring: float stereo at the granted rate, straight copy */
static void mic_feed_ring(struct da_ring *r, const void *audioData, int32_t numFrames)
{
    uint32_t cap = r->cap_frames;
    uint32_t widx = __atomic_load_n(&r->widx, __ATOMIC_RELAXED);
    uint32_t space = cap - (widx - __atomic_load_n(&r->ridx, __ATOMIC_ACQUIRE));
    uint32_t n = (uint32_t)numFrames < space ? (uint32_t)numFrames : space;   /* overrun: drop the newest */
    uint32_t i;

    for (i = 0; i < n; i++)
    {
        uint32_t slot = (widx + i) % cap;
        mic_frame(audioData, (int32_t)i, &r->data[(size_t)slot * 2], &r->data[(size_t)slot * 2 + 1]);
    }
    __atomic_store_n(&r->widx, widx + n, __ATOMIC_RELEASE);
    __atomic_add_fetch(&r->wake, 1, __ATOMIC_RELEASE);
    futex_wake_all(&r->wake);
    if (n < (uint32_t)numFrames) __atomic_add_fetch(&r->underruns, 1, __ATOMIC_RELAXED);
    __atomic_store_n(&r->cb_count, g_mic.cb_count, __ATOMIC_RELAXED);
}

/* the FIFO's ring: s16 mono at MIC_FIFO_RATE, linear resample from the granted
 * rate with the read position carried across blocks */
static void mic_feed_fifo(struct mic_fifo *f, const void *audioData, int32_t numFrames)
{
    double ratio = (double)g_mic.in_rate / MIC_FIFO_RATE;
    uint32_t widx = __atomic_load_n(&f->widx, __ATOMIC_RELAXED);
    uint32_t space = MIC_FIFO_RING - (widx - __atomic_load_n(&f->ridx, __ATOMIC_ACQUIRE));
    uint32_t n = 0;

    if (!f->hot) return;   /* nobody reading: do not fill the ring with stale audio */
    while (f->rs_pos < numFrames && n < space)
    {
        int32_t i0 = (int32_t)f->rs_pos;
        double frac = f->rs_pos - i0;
        float l0, r0, l1, r1, m;

        if (i0 < 0) { l0 = f->last_l; r0 = f->last_r; mic_frame(audioData, 0, &l1, &r1); }
        else
        {
            mic_frame(audioData, i0, &l0, &r0);
            if (i0 + 1 < numFrames) mic_frame(audioData, i0 + 1, &l1, &r1);
            else { l1 = l0; r1 = r0; }
        }
        m = (float)(((l0 + r0) * 0.5) + (((l1 + r1) * 0.5) - ((l0 + r0) * 0.5)) * frac);
        if (m > 1.0f) m = 1.0f; else if (m < -1.0f) m = -1.0f;
        f->buf[(widx + n) % MIC_FIFO_RING] = (int16_t)(m * 32767.0f + (m >= 0 ? 0.5f : -0.5f));
        n++;
        f->rs_pos += ratio;
    }
    if (f->rs_pos < numFrames) f->dropped += (uint32_t)((numFrames - f->rs_pos) / ratio);
    f->rs_pos -= numFrames;
    if (f->rs_pos < -1.0) f->rs_pos = -1.0;
    mic_frame(audioData, numFrames - 1, &f->last_l, &f->last_r);
    __atomic_store_n(&f->widx, widx + n, __ATOMIC_RELEASE);
    __atomic_add_fetch(&f->wake, 1, __ATOMIC_RELEASE);
    futex_wake_all(&f->wake);
}

/* The input callback: one block, every consumer. The consumer list is the
 * client list (those holding a capture ring) plus the FIFO. */
static aaudio_data_callback_result_t in_cb(AAudioStream *aq, void *user,
                                           void *audioData, int32_t numFrames)
{
    struct client *c;

    (void)user;
    if (aq != __atomic_load_n(&g_mic.cq, __ATOMIC_SEQ_CST) && __atomic_load_n(&g_mic.cq, __ATOMIC_SEQ_CST))
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    __atomic_add_fetch(&g_mic.cb_count, 1, __ATOMIC_RELAXED);

    pthread_mutex_lock(&g_clients_lock);
    for (c = g_clients; c; c = c->next)
        if (!c->dead && c->in) mic_feed_ring(c->in, audioData, numFrames);
    pthread_mutex_unlock(&g_clients_lock);

    if (g_mic.fifo) mic_feed_fifo(g_mic.fifo, audioData, numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void in_error_cb(AAudioStream *aq, void *user, aaudio_result_t error)
{
    (void)user;
    if (aq != __atomic_load_n(&g_mic.cq, __ATOMIC_SEQ_CST)) return;
    if (error != AAUDIO_ERROR_DISCONNECTED &&
        error != AAUDIO_ERROR_INVALID_STATE &&
        error != AAUDIO_ERROR_INVALID_HANDLE &&
        error != AAUDIO_ERROR_TIMEOUT)
        return;
    mic_request_reopen("capture stream error");
}

/* Port of capture_open_stream: open (NOT start) 48 kHz / float / stereo with
 * the VOICE_COMMUNICATION preset for platform AEC / noise suppression / AGC. */
static aaudio_result_t open_input(AAudioStream **out)
{
    AAudioStreamBuilder *builder = NULL;
    AAudioStream *aq = NULL;
    aaudio_result_t r;

    r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK || !builder) return r != AAUDIO_OK ? r : AAUDIO_ERROR_NO_MEMORY;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, g_mic.perf);
    AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, DA_RING_CHANNELS);
    AAudioStreamBuilder_setSampleRate(builder, DA_RING_RATE);
    AAudioStreamBuilder_setDataCallback(builder, in_cb, NULL);
    AAudioStreamBuilder_setErrorCallback(builder, in_error_cb, NULL);

    r = AAudioStreamBuilder_openStream(builder, &aq);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK || !aq) return r != AAUDIO_OK ? r : AAUDIO_ERROR_INTERNAL;

    REVENT("mic open: req 48000/float/2ch preset=voicecomm perf=%d - got rate=%d ch=%d fmt=%d",
           g_mic.perf, AAudioStream_getSampleRate(aq), AAudioStream_getChannelCount(aq),
           AAudioStream_getFormat(aq));
    *out = aq;
    return AAUDIO_OK;
}

/* read back the granted geometry and publish it to every game ring: the rate
 * is what the driver resamples from, and it can change across a reopen */
static void note_input_geometry(AAudioStream *aq)
{
    struct client *c;

    g_mic.in_rate = AAudioStream_getSampleRate(aq);
    g_mic.in_channels = AAudioStream_getChannelCount(aq);
    g_mic.in_format = AAudioStream_getFormat(aq);
    if (g_mic.in_rate <= 0) g_mic.in_rate = DA_RING_RATE;
    if (g_mic.in_channels <= 0) g_mic.in_channels = DA_RING_CHANNELS;

    pthread_mutex_lock(&g_clients_lock);
    for (c = g_clients; c; c = c->next)
        if (c->in) __atomic_store_n(&c->in->rate, (uint32_t)g_mic.in_rate, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&g_clients_lock);
}

/* Open the shared input on the first consumer (lazy, and NOT started). Called
 * with g_mic.lock held. A mic that will not open (the uid lacks RECORD_AUDIO)
 * is the caller's problem to report. */
static aaudio_result_t mic_ensure_open_locked(aaudio_performance_mode_t perf)
{
    aaudio_result_t r;

    if (g_mic.opened) return AAUDIO_OK;
    g_mic.perf = perf;
    r = open_input(&g_mic.cq);
    if (r != AAUDIO_OK) return r;
    note_input_geometry(g_mic.cq);
    g_mic.opened = 1;
    return AAUDIO_OK;
}

/* A consumer's wish changes: +1 wants it hot, -1 no longer does. The stream
 * starts on 0 -> 1 and stops on 1 -> 0. requestStart/Stop run outside the lock
 * (they can wait on the callback). */
static void mic_want(int delta, const char *who)
{
    AAudioStream *aq = NULL;
    int start = 0, stop = 0;

    pthread_mutex_lock(&g_mic.lock);
    g_mic.want += delta;
    if (g_mic.want < 0) g_mic.want = 0;
    if (g_mic.cq)
    {
        if (g_mic.want > 0 && !g_mic.started) { g_mic.started = 1; start = 1; aq = g_mic.cq; }
        if (g_mic.want == 0 && g_mic.started) { g_mic.started = 0; stop = 1; aq = g_mic.cq; }
    }
    pthread_mutex_unlock(&g_mic.lock);

    if (start)
    {
        if (AAudioStream_requestStart(aq) != AAUDIO_OK)
        {
            REVENT("mic requestStart failed (%s)", who);
            pthread_mutex_lock(&g_mic.lock);
            g_mic.started = 0;
            pthread_mutex_unlock(&g_mic.lock);
        }
        else REVENT("mic start (%s wants it, %d consumer(s))", who, g_mic.want);
    }
    else if (stop)
    {
        AAudioStream_requestStop(aq);
        REVENT("mic stop (%s was the last consumer)", who);
    }
}

static void *mic_reopen_thread(void *unused)
{
    (void)unused;
    for (;;)
    {
        AAudioStream *old = NULL, *neu = NULL;
        int expected = 0, want_start;

        __atomic_store_n(&g_mic.reopen_redo, 0, __ATOMIC_SEQ_CST);

        pthread_mutex_lock(&g_mic.lock);
        want_start = g_mic.want > 0;
        pthread_mutex_unlock(&g_mic.lock);

        if (open_input(&neu) == AAUDIO_OK)
        {
            pthread_mutex_lock(&g_mic.lock);
            old = g_mic.cq;
            note_input_geometry(neu);
            __atomic_store_n(&g_mic.cq, neu, __ATOMIC_SEQ_CST);
            g_mic.started = 0;
            pthread_mutex_unlock(&g_mic.lock);

            if (want_start)
            {
                if (AAudioStream_requestStart(neu) == AAUDIO_OK)
                {
                    pthread_mutex_lock(&g_mic.lock);
                    g_mic.started = 1;
                    pthread_mutex_unlock(&g_mic.lock);
                }
                else REVENT("mic reopen: requestStart failed");
            }
        }
        else REVENT("mic reopen failed; keeping old stream");

        if (old)
        {
            AAudioStream_requestStop(old);
            AAudioStream_close(old);
        }

        if (__atomic_load_n(&g_mic.reopen_redo, __ATOMIC_SEQ_CST)) { usleep(20000); continue; }
        __atomic_store_n(&g_mic.reopen_running, 0, __ATOMIC_SEQ_CST);
        if (!__atomic_load_n(&g_mic.reopen_redo, __ATOMIC_SEQ_CST)) break;
        if (!__atomic_compare_exchange_n(&g_mic.reopen_running, &expected, 1, 0,
                                         __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
            break;
    }
    return NULL;
}

static void mic_request_reopen(const char *why)
{
    int expected = 0;

    __atomic_store_n(&g_mic.reopen_redo, 1, __ATOMIC_SEQ_CST);
    if (__atomic_compare_exchange_n(&g_mic.reopen_running, &expected, 1, 0,
                                    __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
    {
        pthread_t th;
        REVENT("mic reopen: %s", why);
        if (pthread_create(&th, NULL, mic_reopen_thread, NULL))
            __atomic_store_n(&g_mic.reopen_running, 0, __ATOMIC_SEQ_CST);
        else
            pthread_detach(th);
    }
}

/* a game asked for the mic in its hello: open the shared input, give it a ring */
static int mic_attach_client(struct client *c)
{
    aaudio_result_t r;

    pthread_mutex_lock(&g_mic.lock);
    r = mic_ensure_open_locked(c->perf);
    pthread_mutex_unlock(&g_mic.lock);
    if (r != AAUDIO_OK)
    {
        REVENT("[%d] mic open failed: %d - mic unavailable", c->pid, r);
        return -1;
    }
    if (ring_create((uint32_t)((int64_t)DA_CAPTURE_RING_MS * g_mic.in_rate / 1000),
                    (uint32_t)g_mic.in_rate, &c->in, &c->in_fd) != 0)
        return -1;
    __atomic_store_n(&c->in->state, DA_RING_PLAYING, __ATOMIC_RELEASE);
    return 0;
}

/* the game's first capture Start / last capture voice gone */
static void mic_start(struct client *c)
{
    int go = 0;

    pthread_mutex_lock(&c->lock);
    if (c->in && !c->mic_hot) { c->mic_hot = 1; go = 1; }
    pthread_mutex_unlock(&c->lock);
    if (go) mic_want(+1, c->name);
}

static void mic_stop(struct client *c)
{
    int go = 0;

    pthread_mutex_lock(&c->lock);
    if (c->mic_hot) { c->mic_hot = 0; go = 1; }
    pthread_mutex_unlock(&c->lock);
    if (go) mic_want(-1, c->name);
}

/* ---- the FIFO consumer (Steam client via PulseAudio module-pipe-source) ----
 *
 * The writer thread owns the pipe. A FIFO can only be opened for writing while
 * something holds it for reading, so O_WRONLY|O_NONBLOCK doubles as the "is
 * PulseAudio there?" probe: ENXIO means not yet, retry in half a second. While
 * connected, a write that keeps failing with EAGAIN means the pipe is full and
 * nobody is draining it - PulseAudio has suspended the source because no client
 * is recording - so after MIC_FIFO_IDLE_NS the mic is released and short probe
 * writes of silence are tried instead until one goes through, which is the
 * source waking up. EPIPE means the reader closed: back to probing. */
static void fifo_set_hot(struct mic_fifo *f, int hot)
{
    if (f->hot == hot) return;
    f->hot = hot;
    if (hot)
    {
        /* start clean: whatever queued while idle is stale */
        __atomic_store_n(&f->ridx, __atomic_load_n(&f->widx, __ATOMIC_ACQUIRE), __ATOMIC_RELEASE);
        f->rs_pos = 0.0;
    }
    mic_want(hot ? +1 : -1, "mic-fifo");
}

static void *fifo_thread(void *user)
{
    struct mic_fifo *f = user;
    int16_t chunk[MIC_FIFO_CHUNK];
    uint64_t eagain_since = 0;
    int idle = 0;

    for (;;)
    {
        if (f->fd < 0)
        {
            int fd = open(f->path, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
            if (fd < 0)
            {
                struct timespec ts = { 0, 500 * 1000 * 1000 };
                if (errno != ENXIO && errno != ENOENT)
                    RLOG("mic-fifo: open %s: %d", f->path, errno);
                nanosleep(&ts, NULL);
                continue;
            }
            f->fd = fd;
            eagain_since = 0;
            idle = 0;
            REVENT("mic-fifo: reader connected on %s (s16le %d Hz mono)", f->path, MIC_FIFO_RATE);
            fifo_set_hot(f, 1);
        }

        if (idle)
        {
            /* the source is suspended: probe with 10 ms of silence every 250 ms */
            struct timespec ts = { 0, 250 * 1000 * 1000 };
            ssize_t w;

            memset(chunk, 0, sizeof(chunk));
            w = write(f->fd, chunk, sizeof(chunk));
            if (w > 0)
            {
                REVENT("mic-fifo: reader draining again - mic back on");
                idle = 0;
                eagain_since = 0;
                fifo_set_hot(f, 1);
                continue;
            }
            if (w < 0 && errno != EAGAIN && errno != EINTR)
            {
                REVENT("mic-fifo: reader gone (%d) - waiting for the next one", errno);
                close(f->fd); f->fd = -1;
                idle = 0;
                fifo_set_hot(f, 0);
                continue;
            }
            nanosleep(&ts, NULL);
            continue;
        }

        {
            uint32_t seen = __atomic_load_n(&f->wake, __ATOMIC_ACQUIRE);
            uint32_t ridx = __atomic_load_n(&f->ridx, __ATOMIC_RELAXED);
            uint32_t avail = __atomic_load_n(&f->widx, __ATOMIC_ACQUIRE) - ridx;
            int broke = 0;

            while (avail >= MIC_FIFO_CHUNK && !broke)
            {
                ssize_t w;
                uint32_t i;

                for (i = 0; i < MIC_FIFO_CHUNK; i++) chunk[i] = f->buf[(ridx + i) % MIC_FIFO_RING];
                w = write(f->fd, chunk, sizeof(chunk));
                if (w == (ssize_t)sizeof(chunk))
                {
                    ridx += MIC_FIFO_CHUNK;
                    __atomic_store_n(&f->ridx, ridx, __ATOMIC_RELEASE);
                    avail -= MIC_FIFO_CHUNK;
                    eagain_since = 0;
                }
                else if (w < 0 && errno == EAGAIN)
                {
                    uint64_t now = now_ns();
                    if (!eagain_since) eagain_since = now;
                    else if (now - eagain_since > MIC_FIFO_IDLE_NS)
                    {
                        REVENT("mic-fifo: reader not draining - releasing the mic until it does");
                        idle = 1;
                        fifo_set_hot(f, 0);
                    }
                    break;   /* pipe full: wait for room */
                }
                else if (w < 0 && errno == EINTR) continue;
                else
                {
                    /* EPIPE (reader closed) or a short write we do not expect */
                    REVENT("mic-fifo: write failed (%d) - reader gone", w < 0 ? errno : 0);
                    close(f->fd); f->fd = -1;
                    fifo_set_hot(f, 0);
                    broke = 1;
                }
            }
            if (f->fd >= 0 && !idle)
            {
                struct timespec ts = { 0, 20 * 1000 * 1000 };
                syscall(SYS_futex, &f->wake, FUTEX_WAIT, seen, &ts, NULL, 0);
            }
        }
    }
    return NULL;
}

/* --mic-fifo <path>: open the shared input so the geometry is known and start
 * the writer. The pipe itself belongs to the READER: PulseAudio's
 * module-pipe-source creates it at load and refuses to load (EEXIST) if it is
 * already there, so the helper never creates it - device-proven 2026-09-20,
 * where a helper-made pipe won the race and the module failed. The writer just
 * waits for the path to appear (ENOENT retries like ENXIO). A host that runs
 * the helper with some other reader makes the pipe itself with mkfifo. The mic
 * is NOT made hot here; that happens when a reader shows up. */
static int fifo_setup(const char *path)
{
    struct mic_fifo *f = calloc(1, sizeof(*f));
    struct stat st;
    aaudio_result_t r;

    if (!f) return -1;
    snprintf(f->path, sizeof(f->path), "%s", path);
    f->fd = -1;
    f->buf = calloc(MIC_FIFO_RING, sizeof(int16_t));
    if (!f->buf) { free(f); return -1; }

    if (stat(path, &st) == 0 && !S_ISFIFO(st.st_mode))
    {
        REVENT("mic-fifo: %s exists and is not a FIFO", path);
        free(f->buf); free(f);
        return -1;
    }

    pthread_mutex_lock(&g_mic.lock);
    r = mic_ensure_open_locked(AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    g_mic.fifo = f;
    pthread_mutex_unlock(&g_mic.lock);
    if (r != AAUDIO_OK)
    {
        /* Not fatal: games may still get their rings later if the mic frees up
         * (a reopen after a permission grant is not automatic, though). */
        REVENT("mic-fifo: mic would not open (%d) - is RECORD_AUDIO granted to this uid?", r);
    }
    if (pthread_create(&f->th, NULL, fifo_thread, f) != 0) { REVENT("mic-fifo: thread failed"); return -1; }
    pthread_detach(f->th);
    REVENT("mic-fifo: will write s16le %d Hz mono to %s once a reader creates and opens it "
           "(module-pipe-source file=%s format=s16le rate=%d channels=1)",
           MIC_FIFO_RATE, path, path, MIC_FIFO_RATE);
    return 0;
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
    AAudioStream *aq;

    c->dead = 1;
    /* Unlink first, under the lock the input callback fans out under: once this
     * returns no callback can touch c->in, so the ring below can go. */
    pthread_mutex_lock(&g_clients_lock);
    {
        struct client **pp = &g_clients;
        while (*pp && *pp != c) pp = &(*pp)->next;
        if (*pp) *pp = c->next;
    }
    pthread_mutex_unlock(&g_clients_lock);

    /* A game that died with the mic hot releases its share of it. */
    mic_stop(c);

    /* Let any in-flight reopen worker finish with the stream it is holding. */
    while (__atomic_load_n(&c->reopen_running, __ATOMIC_SEQ_CST))
        usleep(10000);

    pthread_mutex_lock(&c->lock);
    aq = c->aq; c->aq = NULL;
    pthread_mutex_unlock(&c->lock);
    if (aq) { AAudioStream_requestStop(aq); AAudioStream_close(aq); }

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
        if (mic_attach_client(c) != 0)
            ack.flags |= DA_ACK_MIC_FAILED;
        else
        {
            ack.in_cap_frames = (int32_t)c->in->cap_frames;
            ack.in_rate = g_mic.in_rate;
            fds[nfds++] = c->in_fd;
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
    const char *path = NULL, *fifo_path = NULL;
    struct sockaddr_un addr;
    pthread_t th;
    int srv, i;

    for (i = 1; i < argc; i++)
    {
        if (!strcmp(argv[i], "--socket") && i + 1 < argc) path = argv[++i];
        else if (!strcmp(argv[i], "--mic-fifo") && i + 1 < argc) fifo_path = argv[++i];
        else if (!strcmp(argv[i], "--log")) g_verbose = 1;
        else { fprintf(stderr, "usage: directaudio-relay --socket <path> [--mic-fifo <path>] [--log]\n"); return 2; }
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
    if (fifo_path && fifo_setup(fifo_path) != 0)
        REVENT("mic-fifo: disabled");
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
