#ifndef DROIDDECK_SPATIALPOSE_SHM_H
#define DROIDDECK_SPATIALPOSE_SHM_H
#include <stdint.h>
#define DROIDDECK_SPATIALPOSE_MAGIC 0x53504444u
#define DROIDDECK_SPATIALPOSE_VERSION 4u
#define DROIDDECK_SPATIALPOSE_FILE "droiddeck-spatialpose"
enum { DD_SPATIAL_ACTIVE = 1u, DD_SPATIAL_HANDS = 2u, DD_SPATIAL_JOYCONS = 4u, DD_SPATIAL_STEADY_AIM = 8u, DD_SPATIAL_VALID = 1u };
enum { DD_SPATIAL_TRIGGER_VALID = 2u, DD_SPATIAL_GRIP_VALID = 4u };
struct dd_spatial_pose {
    int64_t time_ns;
    float position[3];
    float rotation[4];
    uint32_t flags;
};
struct dd_spatial_hand {
    struct dd_spatial_pose pose;
    float trigger;
    uint32_t grip;
};
struct droiddeck_spatialpose {
    uint32_t magic, version, size, seq;
    uint64_t connection, origin;
    uint32_t flags, input_epochs;
    struct dd_spatial_pose head;
    struct dd_spatial_hand hands[2];
};
#ifdef __cplusplus
static_assert(sizeof(dd_spatial_pose) == 40, "spatial pose ABI");
static_assert(sizeof(droiddeck_spatialpose) == 176, "spatial snapshot ABI");
#else
_Static_assert(sizeof(struct droiddeck_spatialpose) == 176, "spatial snapshot ABI");
#endif
#endif
