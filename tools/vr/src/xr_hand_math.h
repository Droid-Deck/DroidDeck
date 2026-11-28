#pragma once
#include "xr_hand_input.h"

namespace droiddeck::xr {
struct HandTransform { float m[3][4]{}; };

inline HandTransform handTransform(const DroidDeckXrPoseV2 &pose) {
    const float x = pose.orientation[0], y = pose.orientation[1], z = pose.orientation[2], w = pose.orientation[3];
    return {{{1-2*(y*y+z*z),2*(x*y-w*z),2*(x*z+w*y),pose.position[0]},
             {2*(x*y+w*z),1-2*(x*x+z*z),2*(y*z-w*x),pose.position[1]},
             {2*(x*z-w*y),2*(y*z+w*x),1-2*(x*x+y*y),pose.position[2]}}};
}

inline HandTransform handInverse(const HandTransform &matrix) {
    HandTransform inverse;
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) inverse.m[row][col] = matrix.m[col][row];
        for (int col = 0; col < 3; ++col) inverse.m[row][3] -= inverse.m[row][col] * matrix.m[col][3];
    }
    return inverse;
}

inline HandTransform handCompose(const HandTransform &a, const HandTransform &b) {
    HandTransform result;
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 4; ++col) {
        for (int i = 0; i < 3; ++i) result.m[row][col] += a.m[row][i] * b.m[i][col];
        if (col == 3) result.m[row][col] += a.m[row][3];
    }
    return result;
}

}
