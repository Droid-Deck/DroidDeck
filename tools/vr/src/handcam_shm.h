#ifndef HANDCAM_SHM_H
#define HANDCAM_SHM_H
#include <stddef.h>
#include <stdint.h>

#define DROIDDECK_HANDCAM_MAGIC 0x4D434844u
#define DROIDDECK_HANDCAM_VERSION 2u
#define DROIDDECK_HANDCAM_JOINT_COUNT 21
#define DROIDDECK_HANDCAM_FILE "droiddeck-handcam"

enum {
    DROIDDECK_HANDCAM_RUNNING = 1u << 0,
    DROIDDECK_HANDCAM_FACE = 1u << 1,
    DROIDDECK_HANDCAM_BARE_HANDS = 1u << 2,
};

enum {
    DROIDDECK_HANDCAM_SEEN = 1u << 0,
    DROIDDECK_HANDCAM_ESTIMATED = 1u << 1,
    DROIDDECK_HANDCAM_JOINTS = 1u << 2,
};

struct droiddeck_handcam_hand {
    uint32_t flags;
    float position[3];
    float joints[DROIDDECK_HANDCAM_JOINT_COUNT][3];
};

struct droiddeck_handcam {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t flags;
    int64_t time_ns;
    uint32_t frame;
    float face_yaw;
    struct droiddeck_handcam_hand hands[2];
};

#ifdef __cplusplus
#define DROIDDECK_HANDCAM_ASSERT static_assert
#else
#define DROIDDECK_HANDCAM_ASSERT _Static_assert
#endif
DROIDDECK_HANDCAM_ASSERT(offsetof(struct droiddeck_handcam_hand, joints) == 16, "handcam hand layout");
DROIDDECK_HANDCAM_ASSERT(sizeof(struct droiddeck_handcam_hand) == 268, "handcam hand layout");
DROIDDECK_HANDCAM_ASSERT(offsetof(struct droiddeck_handcam, time_ns) == 16, "handcam layout");
DROIDDECK_HANDCAM_ASSERT(offsetof(struct droiddeck_handcam, hands) == 32, "handcam layout");
DROIDDECK_HANDCAM_ASSERT(sizeof(struct droiddeck_handcam) == 568, "handcam layout");

#endif
