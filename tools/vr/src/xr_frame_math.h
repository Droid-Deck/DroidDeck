#pragma once
#include "xr_frame.h"

struct DroidDeckXrRenderView {
    float orientation[4];
    float position[3];
    float fov[4];
};

inline void xr_frame_projection_raw(const float fov[4], float *left, float *right, float *top, float *bottom) {
    *left = std::tan(fov[0]); *right = std::tan(fov[1]);
    // OpenVR's top/bottom parameters are the lower/upper Y bounds in its projection formula.
    *top = std::tan(fov[3]); *bottom = std::tan(fov[2]);
}

inline void xr_frame_eye_pose(float result[3][4], const float head[3][4], const float eyeToHead[3][4]) {
    for (int row = 0; row < 3; row++) {
        for (int column = 0; column < 3; column++) {
            result[row][column] = 0;
            for (int inner = 0; inner < 3; inner++) result[row][column] += head[row][inner] * eyeToHead[inner][column];
        }
        result[row][3] = head[row][3];
        for (int inner = 0; inner < 3; inner++) result[row][3] += head[row][inner] * eyeToHead[inner][3];
    }
}

inline DroidDeckXrRenderView xr_frame_view(const DroidDeckXrFrameHeader &header, int eye) {
    const auto &matrix = header.eye_pose[eye];
    DroidDeckXrRenderView view{};
    float trace = matrix[0][0] + matrix[1][1] + matrix[2][2];
    if (trace > 0) {
        float scale = std::sqrt(trace + 1.f) * 2.f;
        view.orientation[3] = scale * 0.25f;
        view.orientation[0] = (matrix[2][1] - matrix[1][2]) / scale;
        view.orientation[1] = (matrix[0][2] - matrix[2][0]) / scale;
        view.orientation[2] = (matrix[1][0] - matrix[0][1]) / scale;
    } else {
        int axis = matrix[1][1] > matrix[0][0] ? 1 : 0;
        if (matrix[2][2] > matrix[axis][axis]) axis = 2;
        int next = (axis + 1) % 3, last = (axis + 2) % 3;
        float scale = std::sqrt(1.f + matrix[axis][axis] - matrix[next][next] - matrix[last][last]) * 2.f;
        view.orientation[axis] = scale * 0.25f;
        view.orientation[next] = (matrix[axis][next] + matrix[next][axis]) / scale;
        view.orientation[last] = (matrix[axis][last] + matrix[last][axis]) / scale;
        view.orientation[3] = (matrix[last][next] - matrix[next][last]) / scale;
    }
    float lengthSquared = 0;
    for (float value : view.orientation) lengthSquared += value * value;
    for (float &value : view.orientation) value /= std::sqrt(lengthSquared);
    for (int axis = 0; axis < 3; axis++) view.position[axis] = matrix[axis][3];
    const auto &projection = header.projection[eye];
    view.fov[0] = std::atan((projection[0][2] - 1.f) / projection[0][0]);
    view.fov[1] = std::atan((projection[0][2] + 1.f) / projection[0][0]);
    view.fov[2] = std::atan((projection[1][2] + 1.f) / projection[1][1]);
    view.fov[3] = std::atan((projection[1][2] - 1.f) / projection[1][1]);
    return view;
}
