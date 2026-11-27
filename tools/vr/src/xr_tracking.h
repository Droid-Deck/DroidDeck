#pragma once
#include <stdint.h>

#define DROIDDECK_XR_MAGIC 0x52584444u
#define DROIDDECK_XR_VERSION 3u
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
#define DROIDDECK_XR_TRIGGER_CLICK (1u << 9)
#define DROIDDECK_XR_SQUEEZE_CLICK (1u << 10)
#define DROIDDECK_XR_TRIGGER_CLICK_VALID (1u << 11)
#define DROIDDECK_XR_SQUEEZE_CLICK_VALID (1u << 12)

enum DroidDeckXrControllerProfile : uint32_t {
    DROIDDECK_XR_CONTROLLER_UNKNOWN = 0,
    DROIDDECK_XR_CONTROLLER_TOUCH = 1,
    DROIDDECK_XR_CONTROLLER_PICO_NEO3 = 2,
    DROIDDECK_XR_CONTROLLER_PICO4 = 3,
    DROIDDECK_XR_CONTROLLER_PICO4S = 4,
    DROIDDECK_XR_CONTROLLER_SIMPLE = 5,
};

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
    DroidDeckXrPose aim;
    uint32_t profile;
    DroidDeckXrPose model;
    uint8_t model_key[16];
};

struct DroidDeckXrTracking {
    uint32_t magic, version, seq, active;
    int64_t sampled_ns, predicted_ns, period_ns;
    DroidDeckXrPose head;
    DroidDeckXrEye eyes[2];
    DroidDeckXrHand hands[2];
};

static_assert(sizeof(DroidDeckXrHand) == 140, "XR hand IPC layout");
static_assert(sizeof(DroidDeckXrTracking) == 464, "XR tracking IPC layout");

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
