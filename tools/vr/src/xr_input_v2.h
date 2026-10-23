#ifndef DROIDDECK_XR_INPUT_V2_H
#define DROIDDECK_XR_INPUT_V2_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define DROIDDECK_XR_INPUT_V2_MAGIC UINT32_C(0x32495844)
#define DROIDDECK_XR_INPUT_V2_VERSION 2
#define DROIDDECK_XR_INPUT_V2_FILE "droiddeck-xr-input-v2"

enum DroidDeckXrInputSourceV2 {
    DROIDDECK_XR_SOURCE_UNKNOWN = 0,
    DROIDDECK_XR_SOURCE_RUNTIME_ACTION = 1,
    DROIDDECK_XR_SOURCE_CONTROLLER = 2,
    DROIDDECK_XR_SOURCE_SYNTHETIC = 3
};

enum DroidDeckXrInputFlagsV2 {
    DROIDDECK_XR_INPUT_FOCUSED = 1u << 0,
    DROIDDECK_XR_INPUT_PREDICTED_TIME_CONVERTIBLE = 1u << 1,
    DROIDDECK_XR_INPUT_HAND_MODE = 1u << 2,
    DROIDDECK_XR_INPUT_GAMEPAD_MENU = 1u << 3,
    DROIDDECK_XR_INPUT_LOCAL_HAND_MESH = 1u << 4
};

enum DroidDeckXrPoseFlagsV2 {
    DROIDDECK_XR_ORIENTATION_VALID = 1u << 0,
    DROIDDECK_XR_POSITION_VALID = 1u << 1,
    DROIDDECK_XR_ORIENTATION_TRACKED = 1u << 2,
    DROIDDECK_XR_POSITION_TRACKED = 1u << 3
};

enum DroidDeckXrActionBitsV2 {
    DROIDDECK_XR_SELECT = 1u << 0,
    DROIDDECK_XR_PINCH = 1u << 1,
    DROIDDECK_XR_GRASP = 1u << 2
};

enum DroidDeckXrStatusBitsV2 {
    DROIDDECK_XR_AIM_ACTIVE = 1u << 0,
    DROIDDECK_XR_GRIP_ACTIVE = 1u << 1,
    DROIDDECK_XR_SELECT_ACTIVE = 1u << 2,
    DROIDDECK_XR_PINCH_ACTIVE = 1u << 3,
    DROIDDECK_XR_GRASP_ACTIVE = 1u << 4
};

typedef struct DroidDeckXrPoseV2 {
    float position[3];
    float orientation[4];
    uint32_t flags;
} DroidDeckXrPoseV2;

typedef struct DroidDeckXrHandInputV2 {
    DroidDeckXrPoseV2 aim;
    DroidDeckXrPoseV2 grip;
    float select; /* Runtime aim_activate_ext; capability bit gates older producers. */
    float pinch;
    float grasp;
    uint32_t ready_bits;
    uint32_t status_bits;
    uint32_t source;
    uint32_t capability_bits;
    uint32_t reserved;
} DroidDeckXrHandInputV2;

typedef struct DroidDeckXrInputV2 {
    uint32_t magic;
    uint16_t version;
    uint16_t header_bytes;
    uint32_t byte_size;
    uint32_t sequence;
    uint64_t session_generation;
    uint64_t origin_epoch;
    int64_t sample_monotonic_ns;
    int64_t predicted_monotonic_ns;
    uint32_t flags;
    uint32_t gamepad_menu_sequence;
    uint32_t reserved[2];
    DroidDeckXrHandInputV2 hands[2];
} DroidDeckXrInputV2;

/* The file is little-endian; shared access still requires the versioned atomic reader/writer. */
static inline DroidDeckXrInputV2 droiddeck_xr_input_v2_neutral(uint64_t generation, uint64_t epoch) {
    DroidDeckXrInputV2 input;
    memset(&input, 0, sizeof(input));
    input.magic = DROIDDECK_XR_INPUT_V2_MAGIC;
    input.version = DROIDDECK_XR_INPUT_V2_VERSION;
    input.header_bytes = 64;
    input.byte_size = sizeof(input);
    input.session_generation = generation;
    input.origin_epoch = epoch;
    for (int hand = 0; hand < 2; ++hand) {
        input.hands[hand].aim.orientation[3] = 1.0f;
        input.hands[hand].grip.orientation[3] = 1.0f;
    }
    return input;
}

#ifdef __cplusplus
#define DD_XR_STATIC_ASSERT static_assert
#else
#define DD_XR_STATIC_ASSERT _Static_assert
#endif

DD_XR_STATIC_ASSERT(sizeof(float) == 4, "XR input requires float32");
DD_XR_STATIC_ASSERT(sizeof(DroidDeckXrPoseV2) == 32, "pose ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrPoseV2, flags) == 28, "pose flags ABI");
DD_XR_STATIC_ASSERT(sizeof(DroidDeckXrHandInputV2) == 96, "hand ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, grip) == 32, "grip ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, select) == 64, "select ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, pinch) == 68, "pinch ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, grasp) == 72, "grasp ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, ready_bits) == 76, "ready ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, status_bits) == 80, "status ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, source) == 84, "source ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, capability_bits) == 88, "capabilities ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrHandInputV2, reserved) == 92, "reserved ABI");
DD_XR_STATIC_ASSERT(sizeof(DroidDeckXrInputV2) == 256, "input ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, version) == 4, "version ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, header_bytes) == 6, "header size ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, byte_size) == 8, "size ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, sequence) == 12, "sequence ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, session_generation) == 16, "generation ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, origin_epoch) == 24, "epoch ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, sample_monotonic_ns) == 32, "sample time ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, predicted_monotonic_ns) == 40, "predicted time ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, flags) == 48, "flags ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, gamepad_menu_sequence) == 52, "gamepad menu ABI");
DD_XR_STATIC_ASSERT(offsetof(DroidDeckXrInputV2, hands) == 64, "hands ABI");

#undef DD_XR_STATIC_ASSERT
#endif
