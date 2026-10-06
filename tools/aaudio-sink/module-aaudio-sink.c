/*
 * module-aaudio-sink: a PulseAudio 13 sink on Android's AAudio, for the DroidDeck app.
 *
 * The daemon's IO thread pulls rendered audio from the sink one burst at a time and hands it to
 * a blocking AAudio output stream; the stream's own buffer paces the thread, and the latency the
 * sink reports comes from the stream's playback timestamp. With adaptive=1 the buffer grows by a
 * burst after each underrun, up to a cap, so a device that cannot keep a small buffer fed settles
 * on one it can.
 *
 * Module arguments keep the names the app has always passed (performance_mode, adaptive, volume,
 * buffer_frames, max_buffer_frames) so the daemon's config line did not have to change.
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#ifdef HAVE_CONFIG_H
#include <config.h>
#endif

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <aaudio/AAudio.h>

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

PA_MODULE_AUTHOR("The412Banner");
PA_MODULE_DESCRIPTION("Android AAudio output");
PA_MODULE_VERSION(PACKAGE_VERSION);
PA_MODULE_LOAD_ONCE(false);
PA_MODULE_USAGE(
        "sink_name=<name for the sink> "
        "sink_properties=<properties for the sink> "
        "format=<sample format> "
        "rate=<sample rate> "
        "channels=<number of channels> "
        "channel_map=<channel map> "
        "volume=<initial volume, linear, 1.0 = full> "
        "performance_mode=<0 none, 1 low latency, 2 power saving> "
        "adaptive=<grow the buffer after underruns: 0 or 1> "
        "buffer_frames=<initial buffer size in frames, 0 = two bursts> "
        "max_buffer_frames=<largest the buffer may grow to, 0 = the stream's capacity>");

/* The name the app's daemon config addresses (set-default-sink). */
#define DEFAULT_SINK_NAME "AAudioSink"
/* Underruns are polled every so many writes, not every write. */
#define XRUN_CHECK_EVERY 8
#define FALLBACK_BURST_FRAMES 192
/* The buffer starts at whichever is larger: this many bursts, or this much time. The daemon runs
 * under proot, where every system call is traced, and a buffer of two bursts (8 ms) left the
 * client's menu sounds choppy on device; 30 ms is inaudible for a menu and games have their own
 * path. adaptive=1 still grows it further after real underruns. */
#define MIN_BUFFER_BURSTS 2
#define MIN_BUFFER_USEC (30 * PA_USEC_PER_MSEC)
/* Android's own audio threads run at nice -16 (THREAD_PRIORITY_AUDIO); an app may ask for it. */
#define AUDIO_NICE (-16)
#define STATS_EVERY_USEC (30 * PA_USEC_PER_SEC)

static const char* const valid_modargs[] = {
    "sink_name",
    "sink_properties",
    "format",
    "rate",
    "channels",
    "channel_map",
    "volume",
    "performance_mode",
    "adaptive",
    "buffer_frames",
    "max_buffer_frames",
    NULL
};

struct userdata {
    pa_core *core;
    pa_module *module;
    pa_sink *sink;

    pa_thread *thread;
    pa_thread_mq thread_mq;
    pa_rtpoll *rtpoll;

    pa_sample_spec ss;
    size_t frame_size;
    size_t chunk_bytes;
    aaudio_performance_mode_t performance_mode;

    AAudioStream *stream;
    bool started;
    int32_t burst_frames;
    int32_t buffer_frames;
    int32_t max_buffer_frames;
    int32_t requested_buffer_frames;
    bool adaptive;
    /* rate= was given: ask the device for exactly that instead of its own rate. */
    bool rate_requested;
    int32_t xruns_seen;
    unsigned writes_since_xrun_check;
    int32_t chunk_frames;
    pa_memchunk pending;
    pa_usec_t stats_at;
    int32_t xruns_reported;
};

static pa_usec_t frames_to_usec(const struct userdata *u, int64_t frames) {
    return (pa_usec_t) (frames * (int64_t) PA_USEC_PER_SEC / (int64_t) u->ss.rate);
}

static int32_t usec_to_frames(const struct userdata *u, pa_usec_t usec) {
    return (int32_t) ((uint64_t) usec * u->ss.rate / PA_USEC_PER_SEC);
}

/* Each write hands the device half the buffer, whole bursts, at least one: fewer, larger
 * trips through proot's tracing than a burst at a time, with half the buffer of slack left. */
static void set_chunk(struct userdata *u) {
    int32_t frames = u->buffer_frames / 2;
    frames -= frames % u->burst_frames;
    if (frames < u->burst_frames)
        frames = u->burst_frames;
    u->chunk_frames = frames;
    u->chunk_bytes = (size_t) frames * u->frame_size;
}

static void drop_pending(struct userdata *u) {
    if (u->pending.memblock)
        pa_memblock_unref(u->pending.memblock);
    pa_memchunk_reset(&u->pending);
}

static void close_stream(struct userdata *u) {
    if (!u->stream)
        return;
    if (u->started)
        AAudioStream_requestStop(u->stream);
    AAudioStream_close(u->stream);
    u->stream = NULL;
    u->started = false;
}

static void apply_buffer_size(struct userdata *u, int32_t frames) {
    int32_t got;

    if (frames < u->burst_frames)
        frames = u->burst_frames;
    if (u->max_buffer_frames > 0 && frames > u->max_buffer_frames)
        frames = u->max_buffer_frames;
    got = AAudioStream_setBufferSizeInFrames(u->stream, frames);
    u->buffer_frames = got > 0 ? got : AAudioStream_getBufferSizeInFrames(u->stream);
    set_chunk(u);
}

/* Opens the stream for u->ss; on return u->ss holds what the device actually gave. */
static int open_stream(struct userdata *u) {
    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t r;
    int32_t capacity;

    if ((r = AAudio_createStreamBuilder(&builder)) != AAUDIO_OK) {
        pa_log("AAudio_createStreamBuilder: %s", AAudio_convertResultToText(r));
        return -1;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, u->performance_mode);
    AAudioStreamBuilder_setFormat(builder, u->ss.format == PA_SAMPLE_FLOAT32NE ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    /* The device's own rate unless one was asked for: at any other rate Android falls back to a
     * legacy stream with 20 ms bursts and room for three of them, and the daemon under proot
     * cannot keep that fed. The sink takes whatever rate comes back; PulseAudio resamples clients. */
    AAudioStreamBuilder_setSampleRate(builder, u->rate_requested ? (int32_t) u->ss.rate : AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setChannelCount(builder, (int32_t) u->ss.channels);

    r = AAudioStreamBuilder_openStream(builder, &u->stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        pa_log("AAudioStreamBuilder_openStream: %s", AAudio_convertResultToText(r));
        u->stream = NULL;
        return -1;
    }

    u->ss.rate = (uint32_t) AAudioStream_getSampleRate(u->stream);
    u->ss.channels = (uint8_t) AAudioStream_getChannelCount(u->stream);
    u->ss.format = AAudioStream_getFormat(u->stream) == AAUDIO_FORMAT_PCM_FLOAT ? PA_SAMPLE_FLOAT32NE : PA_SAMPLE_S16NE;
    u->frame_size = pa_frame_size(&u->ss);

    u->burst_frames = AAudioStream_getFramesPerBurst(u->stream);
    if (u->burst_frames <= 0)
        u->burst_frames = FALLBACK_BURST_FRAMES;
    capacity = AAudioStream_getBufferCapacityInFrames(u->stream);
    if (u->max_buffer_frames <= 0 || (capacity > 0 && u->max_buffer_frames > capacity))
        u->max_buffer_frames = capacity;
    if (u->requested_buffer_frames > 0)
        apply_buffer_size(u, u->requested_buffer_frames);
    else
        apply_buffer_size(u, PA_MAX(MIN_BUFFER_BURSTS * u->burst_frames, usec_to_frames(u, MIN_BUFFER_USEC)));
    u->xruns_seen = AAudioStream_getXRunCount(u->stream);
    u->xruns_reported = u->xruns_seen;
    u->stats_at = pa_rtclock_now();

    pa_log("aaudio-sink: stream open: %u Hz, %u ch, %s, burst %d frames, buffer %d of %d frames (%u ms), %d frames per write, performance mode %d",
           u->ss.rate, u->ss.channels, pa_sample_format_to_string(u->ss.format),
           u->burst_frames, u->buffer_frames, u->max_buffer_frames, (unsigned) (frames_to_usec(u, u->buffer_frames) / PA_USEC_PER_MSEC),
           u->chunk_frames, (int) u->performance_mode);
    return 0;
}

static int start_stream(struct userdata *u) {
    aaudio_result_t r;

    if (u->started)
        return 0;
    if ((r = AAudioStream_requestStart(u->stream)) != AAUDIO_OK) {
        pa_log("AAudioStream_requestStart: %s", AAudio_convertResultToText(r));
        return -1;
    }
    u->started = true;
    return 0;
}

static void stop_stream(struct userdata *u) {
    if (!u->started)
        return;
    AAudioStream_requestStop(u->stream);
    u->started = false;
}

/* How much audio the device still holds: frames written less frames it reports played. */
static pa_usec_t stream_latency(struct userdata *u) {
    int64_t position = 0, when_ns = 0;

    if (u->stream && u->started && AAudioStream_getTimestamp(u->stream, CLOCK_MONOTONIC, &position, &when_ns) == AAUDIO_OK) {
        struct timespec now;
        int64_t now_ns, played, pending;

        clock_gettime(CLOCK_MONOTONIC, &now);
        now_ns = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
        played = position + (now_ns - when_ns) * (int64_t) u->ss.rate / 1000000000LL;
        pending = AAudioStream_getFramesWritten(u->stream) - played;
        if (pending < 0)
            pending = 0;
        return frames_to_usec(u, pending);
    }
    return frames_to_usec(u, u->buffer_frames);
}

static void check_underruns(struct userdata *u) {
    int32_t xruns;

    if (++u->writes_since_xrun_check < XRUN_CHECK_EVERY)
        return;
    u->writes_since_xrun_check = 0;

    xruns = AAudioStream_getXRunCount(u->stream);
    /* A line every so often while underruns keep coming, so a session log shows the count. */
    if (xruns != u->xruns_reported && pa_rtclock_now() - u->stats_at >= STATS_EVERY_USEC) {
        pa_log("aaudio-sink: %d underrun(s) so far, buffer %d frames", xruns, u->buffer_frames);
        u->xruns_reported = xruns;
        u->stats_at = pa_rtclock_now();
    }
    if (xruns <= u->xruns_seen)
        return;
    u->xruns_seen = xruns;
    if (!u->adaptive || u->buffer_frames >= u->max_buffer_frames)
        return;

    apply_buffer_size(u, u->buffer_frames + u->burst_frames);
    pa_sink_set_fixed_latency_within_thread(u->sink, frames_to_usec(u, u->buffer_frames));
    pa_sink_set_max_request_within_thread(u->sink, u->chunk_bytes);
    pa_log("aaudio-sink: underrun %d: buffer now %d frames (%u ms)", xruns, u->buffer_frames, (unsigned) (frames_to_usec(u, u->buffer_frames) / PA_USEC_PER_MSEC));
}

/* One burst from the sink into the device. Returns <0 when the stream is gone for good. */
static int write_one_burst(struct userdata *u) {
    const void *data;
    aaudio_result_t written = 0;

    for (;;) {
        if (!u->pending.memblock) {
            int64_t space = u->buffer_frames - (AAudioStream_getFramesWritten(u->stream) - AAudioStream_getFramesRead(u->stream));

            if (space < u->burst_frames)
                break;
            pa_sink_render_full(u->sink, (size_t) PA_MIN(space, (int64_t) u->chunk_frames) * u->frame_size, &u->pending);
        }
        data = pa_memblock_acquire_chunk(&u->pending);
        written = AAudioStream_write(u->stream, data, (int32_t) (u->pending.length / u->frame_size), 0);
        pa_memblock_release(u->pending.memblock);
        if (written < 0)
            break;
        u->pending.index += (size_t) written * u->frame_size;
        u->pending.length -= (size_t) written * u->frame_size;
        if (u->pending.length > 0)
            break;
        drop_pending(u);
    }

    if (written >= 0) {
        check_underruns(u);
        return 0;
    }

    /* The device went away (headphones, a route change). Reopen once with the same settings; if
     * the new stream differs in rate or format there is nothing sane to do but give up. */
    pa_log("aaudio-sink: AAudioStream_write: %s; reopening the stream", AAudio_convertResultToText(written));
    {
        pa_sample_spec before = u->ss;

        close_stream(u);
        u->rate_requested = true;
        if (open_stream(u) < 0)
            return -1;
        if (!pa_sample_spec_equal(&before, &u->ss)) {
            pa_log("the reopened stream changed format; unloading");
            return -1;
        }
        if (start_stream(u) < 0)
            return -1;
    }
    return 0;
}

static int sink_process_msg(pa_msgobject *o, int code, void *data, int64_t offset, pa_memchunk *chunk) {
    struct userdata *u = PA_SINK(o)->userdata;

    switch (code) {
        case PA_SINK_MESSAGE_GET_LATENCY:
            *((pa_usec_t*) data) = stream_latency(u);
            return 0;
    }
    return pa_sink_process_msg(o, code, data, offset, chunk);
}

/* Runs in the IO thread: an opened sink means a running stream and a busy loop; a suspended
 * one means a stopped stream and a thread asleep until a message arrives. */
static int sink_set_state_in_io_thread_cb(pa_sink *s, pa_sink_state_t new_state, pa_suspend_cause_t new_suspend_cause) {
    struct userdata *u = s->userdata;

    if (PA_SINK_IS_OPENED(new_state)) {
        if (start_stream(u) < 0)
            return -1;
        pa_rtpoll_set_timer_relative(u->rtpoll, 0);
    } else if (new_state == PA_SINK_SUSPENDED) {
        stop_stream(u);
        drop_pending(u);
        pa_rtpoll_set_timer_disabled(u->rtpoll);
    }
    return 0;
}

static void thread_func(void *userdata) {
    struct userdata *u = userdata;

    pa_log_debug("IO thread starting");
    pa_thread_mq_install(&u->thread_mq);
    /* Ahead of everything else in the app, as Android's own audio threads are; realtime on top
     * of that where the kernel allows (it does not, for an app, but it costs nothing to ask). */
    if (setpriority(PRIO_PROCESS, (id_t) syscall(SYS_gettid), AUDIO_NICE) < 0)
        pa_log_debug("aaudio-sink: could not raise the IO thread's priority (%s)", strerror(errno));
    if (u->core->realtime_scheduling)
        pa_thread_make_realtime(u->core->realtime_priority);

    for (;;) {
        int ret;

        if (PA_SINK_IS_OPENED(u->sink->thread_info.state) && u->started) {
            /* Nothing written can be taken back from the device, so a rewind is just acknowledged. */
            if (u->sink->thread_info.rewind_requested)
                pa_sink_process_rewind(u->sink, 0);
            if (write_one_burst(u) < 0)
                goto fail;
            pa_rtpoll_set_timer_relative(u->rtpoll, PA_MAX(frames_to_usec(u, u->burst_frames) / 2, PA_USEC_PER_MSEC));
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
    pa_sample_spec ss;
    pa_channel_map map;
    pa_cvolume volume;
    uint32_t performance_mode = 1, buffer_frames = 0, max_buffer_frames = 0;
    double linear_volume = 1.0;
    bool adaptive = true;

    pa_assert(m);

    if (!(ma = pa_modargs_new(m->argument, valid_modargs))) {
        pa_log("Failed to parse module arguments.");
        goto fail;
    }

    ss = m->core->default_sample_spec;
    map = m->core->default_channel_map;
    if (pa_modargs_get_sample_spec_and_channel_map(ma, &ss, &map, PA_CHANNEL_MAP_DEFAULT) < 0) {
        pa_log("Invalid sample format specification or channel map");
        goto fail;
    }
    /* AAudio speaks 16-bit or float; anything else is rendered as 16-bit. */
    if (ss.format != PA_SAMPLE_FLOAT32NE)
        ss.format = PA_SAMPLE_S16NE;

    if (pa_modargs_get_value_u32(ma, "performance_mode", &performance_mode) < 0 || performance_mode > 2) {
        pa_log("performance_mode must be 0, 1 or 2");
        goto fail;
    }
    if (pa_modargs_get_value_boolean(ma, "adaptive", &adaptive) < 0) {
        pa_log("adaptive must be 0 or 1");
        goto fail;
    }
    if (pa_modargs_get_value_u32(ma, "buffer_frames", &buffer_frames) < 0 ||
        pa_modargs_get_value_u32(ma, "max_buffer_frames", &max_buffer_frames) < 0) {
        pa_log("buffer_frames and max_buffer_frames must be whole numbers");
        goto fail;
    }
    if (pa_modargs_get_value_double(ma, "volume", &linear_volume) < 0 || linear_volume < 0.0 || linear_volume > 10.0) {
        pa_log("volume must be a linear factor between 0 and 10");
        goto fail;
    }

    m->userdata = u = pa_xnew0(struct userdata, 1);
    u->core = m->core;
    u->module = m;
    u->ss = ss;
    u->performance_mode = performance_mode == 0 ? AAUDIO_PERFORMANCE_MODE_NONE
                        : performance_mode == 2 ? AAUDIO_PERFORMANCE_MODE_POWER_SAVING
                        : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    u->adaptive = adaptive;
    u->rate_requested = pa_modargs_get_value(ma, "rate", NULL) != NULL;
    u->requested_buffer_frames = (int32_t) buffer_frames;
    u->max_buffer_frames = (int32_t) max_buffer_frames;
    u->rtpoll = pa_rtpoll_new();
    if (pa_thread_mq_init(&u->thread_mq, m->core->mainloop, u->rtpoll) < 0) {
        pa_log("pa_thread_mq_init() failed.");
        goto fail;
    }

    if (open_stream(u) < 0)
        goto fail;
    if (u->ss.channels != ss.channels)
        pa_channel_map_init_auto(&map, u->ss.channels, PA_CHANNEL_MAP_DEFAULT);

    pa_sink_new_data_init(&data);
    data.driver = __FILE__;
    data.module = m;
    pa_sink_new_data_set_name(&data, pa_modargs_get_value(ma, "sink_name", DEFAULT_SINK_NAME));
    pa_sink_new_data_set_sample_spec(&data, &u->ss);
    pa_sink_new_data_set_channel_map(&data, &map);
    pa_cvolume_set(&volume, u->ss.channels, pa_sw_volume_from_linear(linear_volume));
    pa_sink_new_data_set_volume(&data, &volume);
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_STRING, "aaudio");
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_DESCRIPTION, "Android audio output");
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_CLASS, "sound");
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_API, "aaudio");
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
    u->sink->set_state_in_io_thread = sink_set_state_in_io_thread_cb;
    u->sink->userdata = u;

    pa_sink_set_asyncmsgq(u->sink, u->thread_mq.inq);
    pa_sink_set_rtpoll(u->sink, u->rtpoll);
    pa_sink_set_max_request(u->sink, u->chunk_bytes);
    pa_sink_set_fixed_latency(u->sink, frames_to_usec(u, u->buffer_frames));

    if (!(u->thread = pa_thread_new("aaudio-sink", thread_func, u))) {
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
    drop_pending(u);
    close_stream(u);
    if (u->rtpoll)
        pa_rtpoll_free(u->rtpoll);
    pa_xfree(u);
}
