#ifndef HEADPOSE_SHM_H
#define HEADPOSE_SHM_H
/*
 * The head pose Cardboard mode tracks, shared with the guest: a 128-byte file the compositor maps
 * at <files>/.wayland-rt/droiddeck-headpose, which the guest sees as /run/droiddeck/droiddeck-headpose.
 * Written by the app (cardboard.c) at the sensor's rate; read by guest VR code (the SteamVR driver in
 * tools/vr) without any call into the app.
 *
 * Seqlock: the writer makes seq odd, writes the rest, then makes it even again. A reader copies the
 * struct and keeps the copy only if seq was even and unchanged across the copy.
 *
 * Axes are OpenVR's (and the cardboard shader's): x right, y up, z back (-z is straight ahead).
 * rot maps head directions to the recentred world, row-major. time_ns is CLOCK_MONOTONIC, the same
 * clock inside the guest (one kernel).
 */
#include <stdint.h>

#define DROIDDECK_HEADPOSE_MAGIC 0x50484444u /* "DDHP" */
#define DROIDDECK_HEADPOSE_VERSION 1u
#define DROIDDECK_HEADPOSE_FILE "droiddeck-headpose"

enum {
    DROIDDECK_HEADPOSE_LIVE = 1u << 0,    /* a rotation sensor is feeding it (else identity) */
    DROIDDECK_HEADPOSE_ENABLED = 1u << 1, /* Cardboard mode is on */
};

struct droiddeck_headpose {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t flags;
    float rot[9];
    float ipd_m;
    int64_t time_ns;
    /* What the lens pass expects of a side-by-side source, so a VR renderer can match it: one eye's
     * half of the frame covers tan_half_fov up and down and eye_aspect times that left and right. */
    float tan_half_fov;
    float eye_aspect;
    uint32_t eye_w, eye_h; /* one eye's viewport on the panel, pixels (0 = not drawn yet) */
    uint32_t reserved[12];
};

#ifdef __cplusplus
static_assert(sizeof(struct droiddeck_headpose) == 128, "headpose layout");
#else
_Static_assert(sizeof(struct droiddeck_headpose) == 128, "headpose layout");
#endif

#endif
