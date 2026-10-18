#include "render_target.h"
#include "../../../app/src/main/cpp/waylandcomp/src/headpose_shm.h"
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <limits>

static void check(float aspect, uint32_t selected, uint32_t panel, uint32_t w, uint32_t h) {
    const auto result = droiddeck::RenderTarget(aspect, selected, panel);
    if (result.width != w || result.height != h) {
        std::fprintf(stderr, "FAIL: aspect %g, height %u, panel %u gave %ux%u, expected %ux%u\n",
                     aspect, selected, panel, result.width, result.height, w, h);
        std::abort();
    }
}

int main() {
    static_assert(sizeof(droiddeck_headpose) == 128);
    static_assert(offsetof(droiddeck_headpose, render_height) == 84);
    check(1920.0f / 1080, 720, 1080, 1280, 720);
    check(1920.0f / 1080, 900, 1080, 1600, 900);
    check(1920.0f / 1080, 1080, 1080, 1920, 1080);
    check(1920.0f / 1080, 1440, 1080, 1920, 1080);
    check(1920.0f / 1200, 1200, 1200, 1920, 1200);
    check(960.0f / 1080, 720, 1080, 640, 720);
    check(1200.0f / 1080, 720, 1080, 800, 720);
    check(16.0f / 9, 720, 0, 1280, 720);
    check(16.0f / 9, 0, 1080, 1280, 720);
    check(16.0f / 9, 720, 480, 853, 480);
    check(0, 0, 0, 720, 720);
    check(std::numeric_limits<float>::quiet_NaN(), 720, 1080, 720, 720);
    check(std::numeric_limits<float>::infinity(), 720, 1080, 720, 720);
    check(-1, 720, 1080, 720, 720);
    check(1, UINT32_MAX, 0, 8192, 8192);
    check(100, 720, 1080, 8192, 720);
    std::puts("PASS: VR render targets follow the selected height and fit the panel");
}
