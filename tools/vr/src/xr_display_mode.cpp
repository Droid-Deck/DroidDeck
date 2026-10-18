#include "xr_display_mode.h"
#include <cstdio>

int main(int argc, char **argv) {
    XrTrackingFile reader(argc > 1 ? argv[1] : "/run/droiddeck/" DROIDDECK_XR_FILE);
    DroidDeckXrTracking tracking{};
    if (!reader.Read(&tracking)) return 1;
    auto mode = XrDisplayMode(tracking);
    if (mode.empty()) return 1;
    std::puts(mode.c_str());
}
