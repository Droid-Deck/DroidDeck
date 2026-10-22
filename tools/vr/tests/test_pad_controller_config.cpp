#include "../src/pad_controller_config.h"
#include <cassert>
#include <cstdio>

int main() {
    auto config = pad_controller_config(nullptr);
    assert(config.profile == PadProfile::Index && !config.sticksMoveHands);
    config = pad_controller_config("emulate=touch\nsticks=hands\n");
    assert(config.profile == PadProfile::Touch && config.sticksMoveHands);
    config = pad_controller_config("emulate=off");
    assert(config.profile == PadProfile::Off);
    config = pad_controller_config("emulate=vive\nsticks=game");
    assert(config.profile == PadProfile::Vive && !config.sticksMoveHands);
    config = pad_controller_config("emulate=offline\nsticks=handsome\n");
    assert(config.profile == PadProfile::Index && !config.sticksMoveHands);
    assert(read_pad_controller_config("/nonexistent/droiddeck").profile == PadProfile::Index);
    std::puts("pad controller config ok");
    return 0;
}
