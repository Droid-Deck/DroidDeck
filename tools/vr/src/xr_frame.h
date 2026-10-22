#pragma once
#include <stdint.h>
#include <cmath>

#define DROIDDECK_XR_FRAME_MAGIC 0x46584444u
#define DROIDDECK_XR_FRAME_VERSION 1u
#define DROIDDECK_XR_FRAME_SHARED_VERSION 2u
#define DROIDDECK_XR_FRAME_SOCKET "droiddeck-xr-frames"
#define DROIDDECK_XR_FRAME_MAX_BYTES (64u * 1024u * 1024u)
#define DROIDDECK_XR_COLOR_LINEAR_RGBA8 1u
#define DROIDDECK_XR_COLOR_SRGB_RGBA8 2u

struct DroidDeckXrFrameHeader {
    uint32_t magic, version, width, height;
    uint64_t frame_id;
    int64_t captured_ns;
    uint32_t bytes, color_space;
    float eye_pose[2][3][4];
    float projection[2][4][4];
};

static_assert(sizeof(DroidDeckXrFrameHeader) == 264, "XR frame protocol layout");

inline bool xr_frame_valid(const DroidDeckXrFrameHeader &header) {
    uint64_t bytes = (uint64_t)header.width * header.height * 4;
    if (header.magic != DROIDDECK_XR_FRAME_MAGIC || (header.version != DROIDDECK_XR_FRAME_VERSION
        && header.version != DROIDDECK_XR_FRAME_SHARED_VERSION)
        || !header.frame_id || header.width < 2 || (header.width & 1u) || !header.height
        || header.width > 8192 || header.height > 4096 || bytes > DROIDDECK_XR_FRAME_MAX_BYTES
        || header.bytes != bytes || (header.color_space != DROIDDECK_XR_COLOR_LINEAR_RGBA8
                                    && header.color_space != DROIDDECK_XR_COLOR_SRGB_RGBA8) || header.captured_ns <= 0) return false;
    for (int eye = 0; eye < 2; eye++) {
        for (const auto &row : header.eye_pose[eye]) for (float value : row) if (!std::isfinite(value)) return false;
        for (const auto &row : header.projection[eye]) for (float value : row) if (!std::isfinite(value)) return false;
        const auto &projection = header.projection[eye];
        if (projection[0][0] < 0.0001f || projection[1][1] < 0.0001f
            || std::abs(projection[3][2] + 1.f) > 0.0001f || std::abs(projection[3][3]) > 0.0001f
            || std::abs(projection[3][0]) > 0.0001f || std::abs(projection[3][1]) > 0.0001f
            || std::abs(projection[0][1]) > 0.0001f || std::abs(projection[0][3]) > 0.0001f
            || std::abs(projection[1][0]) > 0.0001f || std::abs(projection[1][3]) > 0.0001f) return false;
        for (int row = 0; row < 3; row++) for (int other = 0; other < 3; other++) {
            float product = 0;
            for (int column = 0; column < 3; column++) product += header.eye_pose[eye][row][column] * header.eye_pose[eye][other][column];
            if (std::abs(product - (row == other ? 1.f : 0.f)) > 0.01f) return false;
        }
        const auto &rotation = header.eye_pose[eye];
        float determinant = rotation[0][0] * (rotation[1][1] * rotation[2][2] - rotation[1][2] * rotation[2][1])
            - rotation[0][1] * (rotation[1][0] * rotation[2][2] - rotation[1][2] * rotation[2][0])
            + rotation[0][2] * (rotation[1][0] * rotation[2][1] - rotation[1][1] * rotation[2][0]);
        if (std::abs(determinant - 1.f) > 0.01f) return false;
    }
    return true;
}
