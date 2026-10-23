#include "xr_input_v2.h"
#include <cstdio>
#include <cstring>

#define CHECK(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "Failed at line %d: %s\n", __LINE__, #condition); return 1; \
} } while (0)

int main() {
    auto input = droiddeck_xr_input_v2_neutral(UINT64_C(0x0807060504030201), 9);
    unsigned char bytes[sizeof(input)];
    std::memcpy(bytes, &input, sizeof(input));
    CHECK(std::memcmp(bytes, "DXI2", 4) == 0);
    CHECK(bytes[4] == 2 && bytes[5] == 0);
    CHECK(bytes[6] == 64 && bytes[7] == 0);
    CHECK(bytes[8] == 0 && bytes[9] == 1);
    for (int i = 0; i < 8; ++i) CHECK(bytes[16 + i] == i + 1);
    CHECK(bytes[24] == 9);
    for (const auto &hand : input.hands) {
        CHECK(hand.aim.orientation[3] == 1.0f && hand.grip.orientation[3] == 1.0f);
        CHECK(hand.aim.flags == 0 && hand.grip.flags == 0);
        CHECK(hand.select == 0 && hand.pinch == 0 && hand.grasp == 0);
        CHECK(hand.ready_bits == 0 && hand.status_bits == 0 && hand.capability_bits == 0);
    }
    input.hands[1].select = 0.75f;
    std::memcpy(bytes, &input, sizeof(input));
    float secondHandSelect = 0;
    std::memcpy(&secondHandSelect, bytes + 160 + 64, sizeof(float));
    CHECK(secondHandSelect == 0.75f);
    return 0;
}
