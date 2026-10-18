#pragma once
#include <stdint.h>

#define DROIDDECK_XR_MAGIC 0x52584444u
#define DROIDDECK_XR_VERSION 1u
#define DROIDDECK_XR_FILE "droiddeck-xr-tracking"
#define DROIDDECK_XR_STICK_CLICK (1u << 0)
#define DROIDDECK_XR_PRIMARY_CLICK (1u << 1)
#define DROIDDECK_XR_SECONDARY_CLICK (1u << 2)
#define DROIDDECK_XR_MENU_CLICK (1u << 3)
#define DROIDDECK_XR_TRIGGER_TOUCH (1u << 4)
#define DROIDDECK_XR_STICK_TOUCH (1u << 5)
#define DROIDDECK_XR_PRIMARY_TOUCH (1u << 6)
#define DROIDDECK_XR_SECONDARY_TOUCH (1u << 7)
#define DROIDDECK_XR_THUMBREST_TOUCH (1u << 8)

struct DroidDeckXrPose {
    float orientation[4];
    float position[3];
    uint32_t flags;
};

struct DroidDeckXrEye {
    DroidDeckXrPose pose;
    float fov[4];
    uint32_t width, height;
};

struct DroidDeckXrHand {
    DroidDeckXrPose pose;
    float trigger, squeeze, stick[2];
    uint32_t buttons, active;
};

struct DroidDeckXrTracking {
    uint32_t magic, version, seq, active;
    int64_t sampled_ns, predicted_ns, period_ns;
    DroidDeckXrPose head;
    DroidDeckXrEye eyes[2];
    DroidDeckXrHand hands[2];
};

static_assert(sizeof(DroidDeckXrTracking) == 296, "XR tracking IPC layout");

#define DROIDDECK_XR_HAPTIC_MAGIC 0x48584444u
#define DROIDDECK_XR_HAPTIC_FILE "droiddeck-xr-haptics"

struct DroidDeckXrPulse {
    uint64_t id;
    int64_t duration_ns;
    float amplitude, frequency;
};

struct DroidDeckXrHaptics {
    uint32_t magic, version, seq, reserved;
    DroidDeckXrPulse hands[2];
};

static_assert(sizeof(DroidDeckXrHaptics) == 64, "XR haptics IPC layout");
