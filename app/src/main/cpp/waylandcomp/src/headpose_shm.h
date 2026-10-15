#ifndef HEADPOSE_SHM_H
#define HEADPOSE_SHM_H
#include <stdint.h>

#define DROIDDECK_HEADPOSE_MAGIC 0x50484444u
#define DROIDDECK_HEADPOSE_VERSION 1u
#define DROIDDECK_HEADPOSE_FILE "droiddeck-headpose"

enum {
    DROIDDECK_HEADPOSE_LIVE = 1u << 0,
    DROIDDECK_HEADPOSE_ENABLED = 1u << 1,
    DROIDDECK_HEADPOSE_MONO = 1u << 2,
};

struct droiddeck_headpose {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t flags;
    float rot[9];
    float ipd_m;
    int64_t time_ns;
    float tan_half_fov;
    float eye_aspect;
    uint32_t eye_w, eye_h;
    float display_hz;
    // Uses a formerly reserved word; older publishers leave this zero.
    uint32_t render_height;
    uint32_t reserved[10];
};

#ifdef __cplusplus
static_assert(sizeof(struct droiddeck_headpose) == 128, "headpose layout");
#else
_Static_assert(sizeof(struct droiddeck_headpose) == 128, "headpose layout");
#endif

#endif
