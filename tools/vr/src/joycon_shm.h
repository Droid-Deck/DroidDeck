#ifndef JOYCON_SHM_H
#define JOYCON_SHM_H
#include <stddef.h>
#include <stdint.h>

#define DROIDDECK_JOYCON_MAGIC 0x4E434A44u
#define DROIDDECK_JOYCON_VERSION 3u
#define DROIDDECK_JOYCON_FILE "droiddeck-joycon"
#define DROIDDECK_JOYCON_RUMBLE_SLOT 0x100u

enum {
    DROIDDECK_JOYCON_CONNECTED = 1u << 0,
    DROIDDECK_JOYCON_CHARGING = 1u << 1,
};

enum {
    DROIDDECK_JOYCON_TRIGGER = 1u << 0,
    DROIDDECK_JOYCON_GRIP = 1u << 1,
    DROIDDECK_JOYCON_A = 1u << 2,
    DROIDDECK_JOYCON_B = 1u << 3,
    DROIDDECK_JOYCON_STICK = 1u << 4,
    DROIDDECK_JOYCON_SYSTEM = 1u << 5,
};

struct droiddeck_joycon_hand {
    uint32_t flags;
    uint32_t buttons;
    float rotation[4];
    float angular_velocity[3];
    float stick[2];
    float battery;
    uint32_t recenter;
    uint32_t pad;
    int64_t time_ns;
    float displacement[3];
    float velocity[3];
    float inertial_velocity[3];
    uint32_t reserved;
};

struct droiddeck_joycon {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t reserved;
    struct droiddeck_joycon_hand hands[2];
};

#ifdef __cplusplus
#define DROIDDECK_JOYCON_ASSERT static_assert
#else
#define DROIDDECK_JOYCON_ASSERT _Static_assert
#endif
DROIDDECK_JOYCON_ASSERT(sizeof(struct droiddeck_joycon_hand) == 104, "joycon hand layout");
DROIDDECK_JOYCON_ASSERT(offsetof(struct droiddeck_joycon_hand, time_ns) == 56, "joycon hand layout");
DROIDDECK_JOYCON_ASSERT(offsetof(struct droiddeck_joycon_hand, displacement) == 64, "joycon hand layout");
DROIDDECK_JOYCON_ASSERT(offsetof(struct droiddeck_joycon_hand, inertial_velocity) == 88, "joycon hand layout");
DROIDDECK_JOYCON_ASSERT(sizeof(struct droiddeck_joycon) == 224, "joycon layout");
DROIDDECK_JOYCON_ASSERT(offsetof(struct droiddeck_joycon, hands) == 16, "joycon layout");

#endif
