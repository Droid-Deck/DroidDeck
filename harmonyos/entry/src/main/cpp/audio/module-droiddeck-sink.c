/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * A PulseAudio sink that writes the guest's audio to a Unix socket.
 *
 * Upstream DroidDeck runs a PulseAudio *server* on the host: the guest's
 * Steam client connects to it through PULSE_SERVER, and the server's sink
 * module writes the audio to the phone's audio HAL. Upstream builds that
 * module from tools/aaudio-sink/module-aaudio-sink.c, against a bionic
 * libpulsecore it cannot use on HarmonyOS.
 *
 * The port keeps upstream's architecture and replaces only the last step.
 * The native module reads PCM from a socket and hands it to ArkTS's
 * audio.AudioRenderer, which is the supported playback path on HarmonyOS.
 * So this module is the same shape as upstream's, with the AAudio stream
 * replaced by that socket: the server listens, the native module connects,
 * and the audio arrives as interleaved float frames.
 *
 * The guest is the PulseAudio *client*, so nothing about the guest changes:
 * its Steam client already speaks Pulse and already has libpulse.
 */

#include <config.h>

#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <pulse/rtclock.h>
#include <pulse/sample.h>
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

PA_MODULE_AUTHOR("DroidDeck");
PA_MODULE_DESCRIPTION("DroidDeck socket output");
PA_MODULE_VERSION("17.0");
PA_MODULE_LOAD_ONCE(false);
PA_MODULE_USAGE(
        "sink_name=<name for the sink> "
        "sink_properties=<properties for the sink> "
        "socket=<path of the socket to write the audio to> "
        "format=<sample format> "
        "rate=<sample rate> "
        "channels=<number of channels> "
        "channel_map=<channel map> "
        "volume=<initial volume, linear, 1.0 = full>");

#define DEFAULT_SINK_NAME "DroidDeckSink"
/* How much audio is rendered and written in one go. */
#define BLOCK_USEC (40 * PA_USEC_PER_MSEC)

static const char* const valid_modargs[] = {
    "sink_name",
    "sink_properties",
    "socket",
    "format",
    "rate",
    "channels",
    "channel_map",
    "volume",
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
    pa_usec_t block_usec;
    pa_usec_t timestamp;

    /** The socket the audio is written to. -1 until a reader connects. */
    int fd;
    /** The listening socket. */
    int listen_fd;
    char *socket_path;
};

static void socket_close(struct userdata *u) {
    if (u->fd >= 0) {
        close(u->fd);
        u->fd = -1;
    }
}

/** Creates the listening socket the native module connects to. */
static int socket_listen(struct userdata *u, const char *path) {
    struct sockaddr_un address;

    if (strlen(path) >= sizeof(address.sun_path)) {
        pa_log("droiddeck-sink: the socket path is too long: %s", path);
        return -1;
    }

    u->listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (u->listen_fd < 0) {
        pa_log("droiddeck-sink: cannot create the socket: %s", strerror(errno));
        return -1;
    }

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);

    unlink(path);
    if (bind(u->listen_fd, (struct sockaddr *) &address, sizeof(address)) < 0) {
        pa_log("droiddeck-sink: cannot bind %s: %s", path, strerror(errno));
        close(u->listen_fd);
        u->listen_fd = -1;
        return -1;
    }
    if (listen(u->listen_fd, 1) < 0) {
        pa_log("droiddeck-sink: cannot listen on %s: %s", path, strerror(errno));
        close(u->listen_fd);
        u->listen_fd = -1;
        unlink(path);
        return -1;
    }

    u->socket_path = pa_xstrdup(path);
    pa_log_info("droiddeck-sink: listening on %s", path);
    return 0;
}

/** Waits for the native module to connect. */
static void socket_accept(struct userdata *u) {
    struct pollfd pfd;
    int fd;

    pfd.fd = u->listen_fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    if (poll(&pfd, 1, 100) <= 0 || !(pfd.revents & POLLIN))
        return;

    fd = accept(u->listen_fd, NULL, NULL);
    if (fd < 0)
        return;

    u->fd = fd;
    pa_log_info("droiddeck-sink: the reader connected");
}

/** Writes one rendered chunk to the socket. Returns 0 when the stream is gone. */
static int write_chunk(struct userdata *u, pa_memchunk *chunk) {
    const void *data;
    size_t length;
    ssize_t written;

    if (!chunk->memblock)
        return 1;

    data = pa_memblock_acquire_chunk(chunk);
    length = chunk->length;

    written = write(u->fd, data, length);

    pa_memblock_release(chunk->memblock);

    if (written < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            pa_log_debug("droiddeck-sink: write failed: %s", strerror(errno));
        socket_close(u);
        chunk->length = 0;
        return 0;
    }

    chunk->index += (size_t) written;
    chunk->length -= (size_t) written;
    return 1;
}

/** Renders the audio the guest produced and writes it out. */
static void process_render(struct userdata *u, pa_usec_t now) {
    size_t ate = 0;

    /* Fill the buffer up to the block size, then write it out. */
    while (u->timestamp < now + u->block_usec) {
        pa_memchunk chunk;
        size_t request_size;

        request_size = pa_usec_to_bytes(now + u->block_usec - u->timestamp, &u->sink->sample_spec);
        request_size = PA_MIN(request_size, u->sink->thread_info.max_request);

        pa_sink_render(u->sink, request_size, &chunk);
        if (!chunk.memblock)
            break;

        /* When the write fails the stream is gone. The loop has to stop
         * there: the chunk is empty, so the timestamp would never move on
         * and the thread would spin at full speed for the rest of the
         * process's life. */
        if (!write_chunk(u, &chunk)) {
            pa_memblock_unref(chunk.memblock);
            break;
        }

        u->timestamp += pa_bytes_to_usec(chunk.length, &u->sink->sample_spec);
        ate += chunk.length;

        pa_memblock_unref(chunk.memblock);

        if (ate >= u->sink->thread_info.max_request)
            break;
    }
}

static void process_rewind(struct userdata *u, pa_usec_t now) {
    size_t rewind_nbytes, in_buffer;
    pa_usec_t delay;

    rewind_nbytes = u->sink->thread_info.rewind_nbytes;

    if (!PA_SINK_IS_OPENED(u->sink->thread_info.state) || rewind_nbytes <= 0)
        goto do_nothing;

    delay = u->timestamp - now;
    if (u->timestamp <= now)
        goto do_nothing;

    in_buffer = pa_usec_to_bytes(delay, &u->sink->sample_spec);
    if (in_buffer <= 0)
        goto do_nothing;

    if (rewind_nbytes > in_buffer)
        rewind_nbytes = in_buffer;

    pa_sink_process_rewind(u->sink, rewind_nbytes);
    u->timestamp -= pa_bytes_to_usec(rewind_nbytes, &u->sink->sample_spec);
    return;

do_nothing:
    pa_sink_process_rewind(u->sink, 0);
}

static int sink_process_msg(pa_msgobject *o, int code, void *data, int64_t offset, pa_memchunk *chunk) {
    struct userdata *u = PA_SINK(o)->userdata;

    switch (code) {
        case PA_SINK_MESSAGE_GET_LATENCY: {
            pa_usec_t now = pa_rtclock_now();
            *((int64_t*) data) = (int64_t) u->timestamp - (int64_t) now;
            return 0;
        }
    }

    return pa_sink_process_msg(o, code, data, offset, chunk);
}

static int sink_set_state_in_io_thread(pa_sink *s, pa_sink_state_t new_state,
                                       pa_suspend_cause_t new_suspend_cause) {
    struct userdata *u = s->userdata;

    if (s->thread_info.state == PA_SINK_SUSPENDED || s->thread_info.state == PA_SINK_INIT) {
        if (PA_SINK_IS_OPENED(new_state))
            u->timestamp = pa_rtclock_now();
    } else if (new_state == PA_SINK_SUSPENDED) {
        socket_close(u);
    }

    return 0;
}

static void thread_func(void *userdata) {
    struct userdata *u = userdata;

    pa_log_debug("droiddeck-sink: IO thread starting");

    if (u->core->realtime_scheduling)
        pa_thread_make_realtime(u->core->realtime_priority);

    pa_thread_mq_install(&u->thread_mq);

    u->timestamp = pa_rtclock_now();

    for (;;) {
        pa_usec_t now = 0;
        int ret;

        if (PA_SINK_IS_OPENED(u->sink->thread_info.state))
            now = pa_rtclock_now();

        if (PA_UNLIKELY(u->sink->thread_info.rewind_requested))
            process_rewind(u, now);

        if (PA_SINK_IS_OPENED(u->sink->thread_info.state)) {
            if (u->fd < 0) {
                /* Nobody is reading the audio yet. */
                socket_accept(u);
            } else if (u->timestamp <= now) {
                process_render(u, now);
            }

            pa_rtpoll_set_timer_absolute(u->rtpoll, u->timestamp);
        } else {
            pa_rtpoll_set_timer_disabled(u->rtpoll);
        }

        if ((ret = pa_rtpoll_run(u->rtpoll)) < 0)
            goto fail;
        if (ret == 0)
            goto finish;
    }

fail:
    pa_asyncmsgq_post(u->thread_mq.outq, PA_MSGOBJECT(u->core), PA_CORE_MESSAGE_UNLOAD_MODULE,
                      u->module, 0, NULL, NULL);
    pa_asyncmsgq_wait_for(u->thread_mq.inq, PA_MESSAGE_SHUTDOWN);

finish:
    pa_log_debug("droiddeck-sink: IO thread shutting down");
}

void pa__done(pa_module *m) {
    struct userdata *u;

    pa_assert(m);

    if (!m->userdata)
        return;

    u = m->userdata;

    if (u->thread) {
        pa_asyncmsgq_send(u->thread_mq.inq, NULL, PA_MESSAGE_SHUTDOWN, NULL, 0, NULL);
        pa_thread_free(u->thread);
        u->thread = NULL;
    }

    pa_thread_mq_done(&u->thread_mq);

    pa_sink_unref(u->sink);
    u->sink = NULL;

    socket_close(u);

    if (u->listen_fd >= 0) {
        close(u->listen_fd);
        u->listen_fd = -1;
    }
    if (u->socket_path) {
        unlink(u->socket_path);
        pa_xfree(u->socket_path);
        u->socket_path = NULL;
    }

    pa_rtpoll_free(u->rtpoll);
    pa_xfree(u);
}

int pa__init(pa_module *m) {
    struct userdata *u = NULL;
    pa_modargs *ma = NULL;
    pa_sink_new_data data;
    pa_sample_spec ss;
    pa_channel_map map;
    pa_cvolume volume;
    const char *socket_path = NULL;
    const char *sink_name = NULL;
    double linear_volume = 1.0;
    size_t nbytes;

    pa_assert(m);

    if (!(ma = pa_modargs_new(m->argument, valid_modargs))) {
        pa_log("droiddeck-sink: failed to parse the module arguments");
        goto fail;
    }

    ss = m->core->default_sample_spec;
    map = m->core->default_channel_map;
    if (pa_modargs_get_sample_spec_and_channel_map(ma, &ss, &map, PA_CHANNEL_MAP_DEFAULT) < 0) {
        pa_log("droiddeck-sink: invalid sample format specification or channel map");
        goto fail;
    }
    /* The native module reads float frames, and ArkTS's AudioRenderer wants
     * them, so float is the format the sink negotiates. */
    if (ss.format != PA_SAMPLE_FLOAT32NE)
        ss.format = PA_SAMPLE_FLOAT32NE;

    socket_path = pa_modargs_get_value(ma, "socket", NULL);
    if (!socket_path) {
        pa_log("droiddeck-sink: the socket argument is required");
        goto fail;
    }
    sink_name = pa_modargs_get_value(ma, "sink_name", NULL);
    if (pa_modargs_get_value_double(ma, "volume", &linear_volume) < 0 ||
        linear_volume < 0.0 || linear_volume > 10.0) {
        pa_log("droiddeck-sink: volume must be a linear factor between 0 and 10");
        goto fail;
    }

    m->userdata = u = pa_xnew0(struct userdata, 1);
    u->core = m->core;
    u->module = m;
    u->ss = ss;
    u->fd = -1;
    u->listen_fd = -1;
    u->block_usec = BLOCK_USEC;
    u->frame_size = pa_frame_size(&ss);
    u->chunk_bytes = pa_usec_to_bytes(u->block_usec, &ss);
    if (u->chunk_bytes <= 0)
        u->chunk_bytes = u->frame_size * 512;

    pa_cvolume_set(&volume, u->ss.channels, pa_sw_volume_from_linear(linear_volume));

    u->rtpoll = pa_rtpoll_new();
    if (!u->rtpoll) {
        pa_log("droiddeck-sink: failed to create the poll");
        goto fail;
    }

    if (pa_thread_mq_init(&u->thread_mq, m->core->mainloop, u->rtpoll) < 0) {
        pa_log("droiddeck-sink: pa_thread_mq_init() failed");
        goto fail;
    }

    pa_sink_new_data_init(&data);
    data.driver = __FILE__;
    data.module = m;
    pa_sink_new_data_set_name(&data, sink_name ? sink_name : DEFAULT_SINK_NAME);
    pa_sink_new_data_set_sample_spec(&data, &ss);
    pa_sink_new_data_set_channel_map(&data, &map);
    pa_sink_new_data_set_volume(&data, &volume);
    pa_proplist_sets(data.proplist, PA_PROP_DEVICE_DESCRIPTION, "DroidDeck Output");

    u->sink = pa_sink_new(m->core, &data, PA_SINK_LATENCY | PA_SINK_DYNAMIC_LATENCY);
    pa_sink_new_data_done(&data);

    if (!u->sink) {
        pa_log("droiddeck-sink: failed to create the sink");
        goto fail;
    }

    u->sink->parent.process_msg = sink_process_msg;
    u->sink->set_state_in_io_thread = sink_set_state_in_io_thread;
    u->sink->userdata = u;

    pa_sink_set_asyncmsgq(u->sink, u->thread_mq.inq);
    pa_sink_set_rtpoll(u->sink, u->rtpoll);

    nbytes = pa_usec_to_bytes(u->block_usec, &u->sink->sample_spec);
    pa_sink_set_max_request(u->sink, nbytes);
    pa_sink_set_latency_range(u->sink, 0, u->block_usec);

    if (socket_listen(u, socket_path) < 0)
        goto fail;

    if (!(u->thread = pa_thread_new("droiddeck-sink", thread_func, u))) {
        pa_log("droiddeck-sink: failed to start the IO thread");
        goto fail;
    }

    pa_log_info("droiddeck-sink: %u Hz, %u channels, %s",
                u->ss.rate, u->ss.channels, pa_sample_format_to_string(u->ss.format));

    if (ma)
        pa_modargs_free(ma);
    return 0;

fail:
    if (ma)
        pa_modargs_free(ma);
    if (u)
        pa__done(m);
    return -1;
}
