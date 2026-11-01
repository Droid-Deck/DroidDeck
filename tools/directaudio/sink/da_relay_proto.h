/*
 * Copyright 2026 The412Banner
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */
#ifndef DA_RELAY_PROTO_H
#define DA_RELAY_PROTO_H

#include <stdint.h>
#include <stddef.h>

#define DA_RELAY_VERSION   1
#define DA_RING_MAGIC      0x44415247u
#define DA_HELLO_MAGIC     0x44414831u
#define DA_ACK_MAGIC       0x44414131u

#define DA_RELAY_ENV           "BANNER_AUDIO_DIRECT_RELAY"
#define DA_RELAY_DEFAULT_NAME  "directaudio-relay"

#define DA_RING_RATE       48000
#define DA_RING_CHANNELS   2

#define DA_RING_CAP_MS           250
#define DA_RING_TARGET_BURSTS    2
#define DA_RING_MAX_TARGET_MS    100

#define DA_CAPTURE_RING_MS       200

enum da_ring_state
{
    DA_RING_OPENING = 0,
    DA_RING_PLAYING = 1,
    DA_RING_ERROR   = 2,
};

struct da_ring
{
    uint32_t magic;
    uint32_t version;
    uint32_t cap_frames;
    uint32_t rate;
    uint32_t channels;
    uint32_t _pad0;

    volatile uint32_t widx;
    volatile uint32_t ridx;

    volatile uint32_t wake;

    volatile uint32_t quit;
    volatile uint32_t state;

    volatile int32_t  target_frames;
    volatile int32_t  hw_buf_frames;
    volatile int32_t  hw_burst;
    volatile uint32_t underruns;
    volatile uint32_t cb_count;
    uint32_t _pad1[3];

    float data[];
};

static inline size_t da_ring_bytes(uint32_t cap_frames)
{
    return sizeof(struct da_ring) + (size_t)cap_frames * DA_RING_CHANNELS * sizeof(float);
}

static inline uint32_t da_ring_avail(const struct da_ring *r)
{
    uint32_t w = __atomic_load_n(&r->widx, __ATOMIC_ACQUIRE);
    uint32_t rd = __atomic_load_n(&r->ridx, __ATOMIC_ACQUIRE);
    return w - rd;
}

static inline uint32_t da_ring_space(const struct da_ring *r)
{
    return r->cap_frames - da_ring_avail(r);
}

#define DA_HELLO_MIC        0x01u
#define DA_HELLO_EXCLUSIVE  0x02u
#define DA_HELLO_ADAPTIVE   0x04u
#define DA_HELLO_DECAY      0x08u
#define DA_HELLO_WATCHDOG   0x10u

struct da_hello
{
    uint32_t magic;
    uint32_t version;
    uint32_t flags;
    int32_t  perf;
    int32_t  target_ms;
    int32_t  max_ms;
    int32_t  target_frames;
    int32_t  max_frames;
    uint32_t stall_ms;
    uint32_t quiet_ms;
    uint32_t punish_ms;
    uint32_t max_backoff;
    int32_t  pid;
    uint32_t log;
    char     runtime_path[256];
    char     name[64];
};

#define DA_ACK_MIC_FAILED   0x01u

struct da_ack
{
    uint32_t magic;
    int32_t  status;
    int32_t  rate;
    int32_t  burst;
    int32_t  buf_frames;
    int32_t  out_cap_frames;
    int32_t  in_cap_frames;
    int32_t  in_rate;
    uint32_t flags;
    uint32_t _pad;
};

enum da_msg_type
{
    DA_MSG_MIC_START = 1,
    DA_MSG_MIC_STOP  = 2,
    DA_MSG_PING      = 3,
};

struct da_msg
{
    uint32_t type;
    int32_t  arg;
};

#endif
