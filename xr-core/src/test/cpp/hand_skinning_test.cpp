#include "HandSkinning.h"
#include <cassert>
#include <cmath>

using namespace droiddeck::xr;
static void near(float a, float b) { assert(std::abs(a-b) < 1e-5f); }
int main() {
    DroidDeckXrPoseV2 joints[26];
    for (int j = 0; j < 26; ++j) joints[j] = {{.01f*j, .002f*j, -.02f*j}, {0,0,0,1}, 15};
    SkinMatrix inverse[31], before[31], after[31];
    for (auto &matrix : inverse) matrix = skinIdentity();
    for (bool right : {false, true}) {
        handSkinPalette(joints, right, inverse, before);
        for (int bone = 1; bone < 26; ++bone) for (int axis = 0; axis < 3; ++axis)
            near(before[bone][12+axis], joints[bone].position[axis]);
        // Wrist and finger bones use different Valve coordinate bases.
        near(before[1][10], -1); near(before[1][1], right ? -1 : 1);
        near(before[1][4], right ? -1 : 1);
        joints[8].orientation[0] = .70710678f; joints[8].orientation[3] = .70710678f;
        handSkinPalette(joints, right, inverse, after);
        assert(before[8] != after[8]);
        for (int bone : {1, 2, 7, 9, 11, 12, 16, 21})
            for (int i = 0; i < 16; ++i) near(before[bone][i], after[bone][i]);
        joints[8].orientation[0] = 0; joints[8].orientation[3] = 1;
        // Global movement follows tracking without modifying the reference mesh.
        for (auto &joint : joints) joint.position[0] += 2;
        handSkinPalette(joints, right, inverse, after);
        for (int bone = 1; bone < 26; ++bone) near(after[bone][12], before[bone][12]+2);
        for (auto &joint : joints) joint.position[0] -= 2;
    }
    auto translation = skinIdentity(); translation[12] = 2; translation[13] = -3;
    near(skinMultiply(translation, translation)[12], 4);
    near(skinMultiply(translation, translation)[13], -6);
}
