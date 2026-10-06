/*
 * module-directaudio-sink: a PulseAudio 13 sink that hands the daemon's mixed output to the
 * DirectAudio relay, the bionic helper that owns the AAudio stream on the Android side.
 *
 * The daemon runs under proot, where every system call is traced; an AAudio stream driven from
 * in here is at the mercy of that. The relay is not: it is the same helper that plays games'
 * DirectAudio, with its adaptive buffer, route-change reopen and watchdog. This module is a
 * producer for its render ring (da_relay_proto.h): float stereo at the ring's rate, kept topped
 * up to the target the relay asks for, one futex wait per burst.
 *
 * The relay comes up after the daemon, so the sink loads without it and connects when the socket
 * appears; until then it runs as a clocked null sink so clients are not stalled.
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

#include <errno.h>
#include <linux/futex.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <pulse/rtclock.h>
#include <pulse/timeval.h>
#include <pulse/util.h>
#include <pulse/xmalloc.h>

#include <pulsecore/core-util.h>
#include <pulsecore/log.h>
#include <pulsecore/macro.h>
#include <pulsecore/modargs.h>
#include <pulsecore/module.h>
#include <pulsecore/rtpoll.h>
#include <pulsecore/sink.h>
#include <pulsecore/thread-mq.h>
#include <pulsecore/thread.h>

#include "da_relay_proto.h"

PA_MODULE_AUTHOR("The412Banner");
PA_MODULE_DESCRIPTION("Android audio output through the DirectAudio relay");
PA_MODULE_VERSION(PACKAGE_VERSION);
PA_MODULE_LOAD_ONCE(false);
PA_MODULE_USAGE(
        "socket=<the relay's unix socket> "
        "sink_name=<name for the sink> "
        "sink_properties=<properties for the sink> "
        "volume=<initial volume, linear, 1.0 = full> "
        "performance_mode=<0 none, 1 low latency, 2 power saving> "
        "adaptive=<let the relay grow its buffer after underruns: 0 or 1> "
        "buffer_ms=<device buffer the relay starts with, in ms; rounded up to whole bursts>");

#define DEFAULT_SINK_NAME "DirectAudio"
#define RECONNECT_USEC (500 * PA_USEC_PER_MSEC)
#define OFFLINE_TICK_USEC (20 * PA_USEC_PER_MSEC)
#define STATS_EVERY_USEC (30 * PA_USEC_PER_SEC)
/* Never queue more than this ahead of the relay's target, whatever it asks for. */
#define MAX_AHEAD_MS 250
/* Always keep at least this much queued. The relay starts its target at two bursts (8 ms), which
 * a producer inside proot misses while the client is loading: two underruns in the first seconds
 * on the FIT, steady stutter on a slower phone. 40 ms of slack costs 40 ms of latency at most. */
#define MIN_AHEAD_MS 40
/* The device buffer asked of the relay. Its own default is 12 ms rounded up to whole bursts: three
 * 4 ms bursts on a device that grants a fast stream, but ONE 20 ms burst where Android grants a
 * legacy one (an AYN Thor, Android 13), and a single burst has nothing to cover a late callback.
 * 24 ms is two bursts there, Android's recommended minimum; the relay still grows it on xruns. */
#define DEFAULT_BUFFER_MS 24
#define AUDIO_NICE (-16)

static const char* const valid_modargs[] = {
    "socket", "sink_name", "sink_properties", "volume", "performance_mode", "adaptive", "buffer_ms", NULL
};

struct userdata {
    pa_core *core;
    pa_module *module;
    pa_sink *sink;

    pa_thread *thread;
    pa_thread_mq thread_mq;
    pa_rtpoll *rtpoll;

    char *socket_path;
    int perf;
    bool adaptive;
    uint32_t buffer_ms;
    pa_sample_spec ss;
    size_t frame_size;

    int fd;
    struct da_ring *ring;
    size_t ring_bytes;
    int32_t burst;
    pa_usec_t next_connect;
    pa_usec_t offline_at;
    pa_usec_t stats_at;
    uint32_t underruns_reported;
    pa_usec_t settled_at;
};

static void disconnect_relay(struct userdata *u) {
    if (u->ring) {
        __atomic_store_n(&u->ring->quit, 1, __ATOMIC_RELEASE);
        munmap(u->ring, u->ring_bytes);
        u->ring = NULL;
    }
    if (u->fd >= 0) {
        close(u->fd);
        u->fd = -1;
    }
}

/* One try at the socket; on success the render ring is mapped and u->burst known. */
static int connect_relay(struct userdata *u) {
    struct sockaddr_un addr;
    struct da_hello hello;
    struct da_ack ack;
    struct msghdr msg;
    struct iovec iov;
    struct cmsghdr *cm;
    union { struct cmsghdr align; char buf[CMSG_SPACE(sizeof(int) * 2)]; } cmsg;
    int fds[2] = { -1, -1 };
    ssize_t n;

    if (strlen(u->socket_path) >= sizeof(addr.sun_path))
        return -1;
    if ((u->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0)) < 0)
        return -1;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strcpy(addr.sun_path, u->socket_path);
    if (connect(u->fd, (struct sockaddr*) &addr, sizeof(addr)) < 0)
        goto fail;

    memset(&hello, 0, sizeof(hello));
    hello.magic = DA_HELLO_MAGIC;
    hello.version = DA_RELAY_VERSION;
    hello.flags = u->adaptive ? DA_HELLO_ADAPTIVE : 0;
    hello.perf = u->perf;
    hello.target_ms = (int32_t) u->buffer_ms;
    hello.pid = (int32_t) getpid();
    pa_snprintf(hello.name, sizeof(hello.name), "pulseaudio");
    if (pa_loop_write(u->fd, &hello, sizeof(hello), NULL) != (ssize_t) sizeof(hello))
        goto fail;

    memset(&msg, 0, sizeof(msg));
    iov.iov_base = &ack;
    iov.iov_len = sizeof(ack);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg.buf;
    msg.msg_controllen = sizeof(cmsg.buf);
    do {
        n = recvmsg(u->fd, &msg, MSG_WAITALL);
    } while (n < 0 && errno == EINTR);
    if (n != (ssize_t) sizeof(ack) || ack.magic != DA_ACK_MAGIC)
        goto fail;
    for (cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm)) {
        if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS) {
            size_t count = (cm->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            if (count > 2) count = 2;
            memcpy(fds, CMSG_DATA(cm), count * sizeof(int));
        }
    }
    if (fds[1] >= 0)
        close(fds[1]);  /* a capture ring, not for this sink */
    if (ack.status != 0 || fds[0] < 0 || ack.out_cap_frames <= 0) {
        pa_log("directaudio-sink: the relay refused (status %d)", ack.status);
        goto fail;
    }
    u->ring_bytes = da_ring_bytes((uint32_t) ack.out_cap_frames);
    u->ring = mmap(NULL, u->ring_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fds[0], 0);
    close(fds[0]);
    if (u->ring == MAP_FAILED) {
        u->ring = NULL;
        goto fail;
    }
    if (u->ring->magic != DA_RING_MAGIC || u->ring->version != DA_RELAY_VERSION || u->ring->channels != DA_RING_CHANNELS) {
        pa_log("directaudio-sink: ring header mismatch");
        goto fail;
    }
    u->burst = ack.burst > 0 ? ack.burst : 192;
    if (u->ring->rate != u->ss.rate)
        pa_log("directaudio-sink: the relay's ring runs at %u Hz, the sink at %u; expect resampling artefacts", u->ring->rate, u->ss.rate);
    u->stats_at = pa_rtclock_now();
    u->settled_at = u->stats_at + 10 * PA_USEC_PER_SEC;
    u->underruns_reported = u->ring->underruns;
    pa_log("directaudio-sink: connected to the relay: %d Hz, burst %d, device buffer %d frames (asked %u ms), ring %d frames, target %d",
           ack.rate, ack.burst, ack.buf_frames, u->buffer_ms, ack.out_cap_frames, (int) u->ring->target_frames);
    return 0;

fail:
    if (fds[0] >= 0) close(fds[0]);
    disconnect_relay(u);
    return -1;
}

static pa_usec_t frames_to_usec(const struct userdata *u, int64_t frames) {
    return (pa_usec_t) (frames * (int64_t) PA_USEC_PER_SEC / (int64_t) u->ss.rate);
}

static pa_usec_t sink_latency(struct userdata *u) {
    if (!u->ring)
        return 0;
    return frames_to_usec(u, (int64_t) da_ring_avail(u->ring) + (u->ring->hw_buf_frames > 0 ? u->ring->hw_buf_frames : 0));
}

/* Renders `frames` frames from the sink (silence when it is not open) into the ring. */
static void produce(struct userdata *u, uint32_t frames) {
    struct da_ring *r = u->ring;
    uint32_t w = __atomic_load_n(&r->widx, __ATOMIC_ACQUIRE);
    uint32_t pos = w % r->cap_frames;
    uint32_t first = PA_MIN(frames, r->cap_frames - pos);
    float *dst = r->data + (size_t) pos * DA_RING_CHANNELS;
    size_t bytes = (size_t) frames * u->frame_size;

    if (PA_SINK_IS_OPENED(u->sink->thread_info.state)) {
        pa_memchunk chunk;
        const uint8_t *src;

        if (u->sink->thread_info.rewind_requested)
            pa_sink_process_rewind(u->sink, 0);
        pa_sink_render_full(u->sink, bytes, &chunk);
        src = pa_memblock_acquire_chunk(&chunk);
        memcpy(dst, src, (size_t) first * u->frame_size);
        if (frames > first)
            memcpy(r->data, src + (size_t) first * u->frame_size, (size_t) (frames - first) * u->frame_size);
        pa_memblock_release(chunk.memblock);
        pa_memblock_unref(chunk.memblock);
    } else {
        memset(dst, 0, (size_t) first * u->frame_size);
        if (frames > first)
            memset(r->data, 0, (size_t) (frames - first) * u->frame_size);
    }
    __atomic_store_n(&r->widx, w + frames, __ATOMIC_RELEASE);
}

/* Tops the ring up to the relay's target, then sleeps until the relay drains a burst. */
static int service_ring(struct userdata *u) {
    struct da_ring *r = u->ring;
    uint32_t avail, target, space, wake;
    struct timespec ts;

    if (__atomic_load_n(&r->quit, __ATOMIC_ACQUIRE) || __atomic_load_n(&r->state, __ATOMIC_ACQUIRE) == DA_RING_ERROR) {
        pa_log("directaudio-sink: the relay closed the ring; reconnecting");
        return -1;
    }
    target = (uint32_t) (r->target_frames > 0 ? r->target_frames : 2 * u->burst);
    if (target < u->ss.rate / 1000 * MIN_AHEAD_MS)
        target = u->ss.rate / 1000 * MIN_AHEAD_MS;
    if (target > (uint32_t) (u->ss.rate / 1000 * MAX_AHEAD_MS))
        target = (uint32_t) (u->ss.rate / 1000 * MAX_AHEAD_MS);
    avail = da_ring_avail(r);
    space = da_ring_space(r);
    if (avail < target) {
        uint32_t want = PA_MIN(target - avail, space);
        if (want > 0)
            produce(u, want);
    }
    /* Once, ten seconds in: what the relay's buffer has settled at, so a session log shows it. */
    if (u->settled_at && pa_rtclock_now() >= u->settled_at) {
        pa_log("directaudio-sink: after 10 s: device buffer %d frames, ring target %d, relay underruns %u",
               (int) r->hw_buf_frames, (int) r->target_frames, r->underruns);
        u->settled_at = 0;
    }
    if (r->underruns != u->underruns_reported && pa_rtclock_now() - u->stats_at >= STATS_EVERY_USEC) {
        pa_log("directaudio-sink: relay reports %u underrun(s), target %d frames, device buffer %d", r->underruns, (int) r->target_frames, (int) r->hw_buf_frames);
        u->underruns_reported = r->underruns;
        u->stats_at = pa_rtclock_now();
    }
    /* Sleep until the relay's callback bumps the wake word, at most one burst period. */
    wake = __atomic_load_n(&r->wake, __ATOMIC_ACQUIRE);
    ts.tv_sec = 0;
    ts.tv_nsec = (long) (frames_to_usec(u, u->burst) * 1000);
    syscall(SYS_futex, &r->wake, FUTEX_WAIT, wake, &ts, NULL, 0);
    return 0;
}

static int sink_process_msg(pa_msgobject *o, int code, void *data, int64_t offset, pa_memchunk *chunk) {
    struct userdata *u = PA_SINK(o)->userdata;

    switch (code) {
        case PA_SINK_MESSAGE_GET_LATENCY:
            *((pa_usec_t*) data) = sink_latency(u);
            return 0;
    }
    return pa_sink_process_msg(o, code, data, offset, chunk);
}

static void thread_func(void *userdata) {
    struct userdata *u = userdata;

    pa_log_debug("IO thread starting");
    pa_thread_mq_install(&u->thread_mq);
    if (setpriority(PRIO_PROCESS, (id_t) syscall(SYS_gettid), AUDIO_NICE) < 0)
        pa_log_debug("directaudio-sink: could not raise the IO thread's priority (%s)", strerror(errno));
    if (u->core->realtime_scheduling)
        pa_thread_make_realtime(u->core->realtime_priority);
    pa_rtpoll_set_timer_relative(u->rtpoll, 0);

    for (;;) {
        int ret;
        pa_usec_t now = pa_rtclock_now();

        if (!u->ring && now >= u->next_connect) {
            if (connect_relay(u) < 0) {
                u->next_connect = now + RECONNECT_USEC;
                u->offline_at = now;
            }
        }
        if (u->ring) {
            if (service_ring(u) < 0) {
                disconnect_relay(u);
                u->next_connect = pa_rtclock_now() + RECONNECT_USEC;
                u->offline_at = u->next_connect;
            }
            pa_rtpoll_set_timer_relative(u->rtpoll, 0);
        } else {
            /* No relay yet: a clocked null sink, so clients keep flowing and time keeps passing. */
            if (PA_SINK_IS_OPENED(u->sink->thread_info.state) && now >= u->offline_at) {
                pa_memchunk chunk;

                if (u->sink->thread_info.rewind_requested)
                    pa_sink_process_rewind(u->sink, 0);
                pa_sink_render_full(u->sink, pa_usec_to_bytes(OFFLINE_TICK_USEC, &u->ss), &chunk);
                pa_memblock_unref(chunk.memblock);
                u->offline_at = now + OFFLINE_TICK_USEC;
            }
            pa_rtpoll_set_timer_absolute(u->rtpoll, PA_MIN(u->offline_at, u->next_connect));
        }
        if ((ret = pa_rtpoll_run(u->rtpoll)) < 0)
            goto fail;
        if (ret == 0)
            goto finish;
    }

fail:
    pa_asyncmsgq_post(u->thread_mq.outq, PA_MSGOBJECT(u->core), PA_CORE_MESSAGE_UNLOAD_MODULE, u->module, 0, NULL, NULL);
    pa_asyncmsgq_wait_for(u->thread_mq.inq, PA_MESSAGE_SHUTDOWN);

finish:
    pa_log_debug("IO thread shutting down");
}

void pa__done(pa_module *m);

int pa__init(pa_module *m) {
    struct userdata *u = NULL;
    pa_modargs *ma = NULL;
    pa_sink_new_data data;
    pa_channel_map map;
    pa_cvolume volume;
    uint32_t perf = 1, buffer_ms = DEFAULT_BUFFER_MS;
    double linear_volume = 1.0;
    bool adaptive = true;
    const char *path;

    pa_assert(m);

    if (!(ma = pa_modargs_new(m->argument, valid_modargs))) {
        pa_log("Failed to parse module arguments.");
        goto fail;
    }
    if (!(path = pa_modargs_get_value(ma, "socket", NULL))) {
        pa_log("socket= is required: the DirectAudio relay's unix socket");
        goto fail;
    }
    if (pa_modargs_get_value_u32(ma, "performance_mode", &perf) < 0 || perf > 2) {
        pa_log("performance_mode must be 0, 1 or 2");
        goto fail;
    }
    if (pa_modargs_get_value_u32(ma, "buffer_ms", &buffer_ms) < 0 || buffer_ms > 200) {
        pa_log("buffer_ms must be between 0 and 200");
        goto fail;
    }
    if (pa_modargs_get_value_boolean(ma, "adaptive", &adaptive) < 0) {
        pa_log("adaptive must be 0 or 1");
        goto fail;
    }
    if (pa_modargs_get_value_double(ma, "volume", &linear_volume) < 0 || linear_volume < 0.0 || linear_volume > 10.0) {
        pa_log("volume must be a linear factor between 0 and 10");
        goto fail;
    }

    m->userdata = u = pa_xnew0(struct userdata, 1);
    u->core = m->core;
    u->module = m;
    u->fd = -1;
    u->socket_path = pa_xstrdup(path);
    u->perf = (int) perf;
    u->adaptive = adaptive;
    u->buffer_ms = buffer_ms;
    /* The ring's format, fixed by the relay: float stereo at 48 kHz. */
    u->ss.format = PA_SAMPLE_FLOAT32NE;
    u->ss.rate = DA_RING_RATE;
    u->ss.channels = DA_RING_CHANNELS;
    u->frame_size = pa_frame_size(&u->ss);
    u->burst = 192;
    pa_channel_map_init_stereo(&map);
    u->rtpoll = pa_rtpoll_new();
    if (pa_thread_mq_init(&u->thread_mq, m->core->mainloop, u->rtpoll) < 0) {
        pa_log("pa_thread_mq_init() failed.");
        goto fail;
    }

    pa_sink_new_data_init(&data);
    data.driver = __FILE__;
    data.module = m;
    pa_sink_new_data_set_name(&data, pa_modargs_get_value(ma, "sink_name", DEFAULT_SINK_NAME));
    pa_sink_new_data_set_sample_spec(&data, &u->ss);
    pa_sink_new_data_set_channel_map(&data, &map);
    pa_cvolume_set(&volume, u->ss.channels, pa_sw_volume_from_linear(linear_volume));
    pa_sink_new_data_set_volume(&data, &volume);
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_STRING, "directaudio");
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_DESCRIPTION, "Android audio output (DirectAudio)");
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_CLASS, "sound");
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_API, "directaudio");
    if (pa_modargs_get_proplist(ma, "sink_properties", data.proplist, PA_UPDATE_REPLACE) < 0) {
        pa_log("Invalid properties");
        pa_sink_new_data_done(&data);
        goto fail;
    }
    u->sink = pa_sink_new(m->core, &data, PA_SINK_HARDWARE | PA_SINK_LATENCY);
    pa_sink_new_data_done(&data);
    if (!u->sink) {
        pa_log("Failed to create sink.");
        goto fail;
    }
    u->sink->parent.process_msg = sink_process_msg;
    u->sink->userdata = u;
    pa_sink_set_asyncmsgq(u->sink, u->thread_mq.inq);
    pa_sink_set_rtpoll(u->sink, u->rtpoll);
    pa_sink_set_max_request(u->sink, pa_usec_to_bytes(MAX_AHEAD_MS * PA_USEC_PER_MSEC, &u->ss));
    pa_sink_set_fixed_latency(u->sink, 40 * PA_USEC_PER_MSEC);

    if (!(u->thread = pa_thread_new("directaudio-sink", thread_func, u))) {
        pa_log("Failed to create thread.");
        goto fail;
    }
    pa_sink_put(u->sink);
    pa_modargs_free(ma);
    return 0;

fail:
    if (ma)
        pa_modargs_free(ma);
    pa__done(m);
    return -1;
}

int pa__get_n_used(pa_module *m) {
    struct userdata *u;

    pa_assert(m);
    pa_assert_se(u = m->userdata);
    return pa_sink_linked_by(u->sink);
}

void pa__done(pa_module *m) {
    struct userdata *u;

    pa_assert(m);
    if (!(u = m->userdata))
        return;
    if (u->sink)
        pa_sink_unlink(u->sink);
    if (u->thread) {
        pa_asyncmsgq_send(u->thread_mq.inq, NULL, PA_MESSAGE_SHUTDOWN, NULL, 0, NULL);
        pa_thread_free(u->thread);
    }
    pa_thread_mq_done(&u->thread_mq);
    if (u->sink)
        pa_sink_unref(u->sink);
    disconnect_relay(u);
    if (u->rtpoll)
        pa_rtpoll_free(u->rtpoll);
    pa_xfree(u->socket_path);
    pa_xfree(u);
}
