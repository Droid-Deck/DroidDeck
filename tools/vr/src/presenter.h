#pragma once
#include <cstdint>

namespace droiddeck {

struct EyeFrame {
    uint64_t texture[2];
    float bounds[2][4];
};

class Presenter {
public:
    static void AddTextures(const uint64_t handles[3], const int fds[3], uint32_t vkFormat, uint32_t width, uint32_t height);
    static void RemoveTexture(uint64_t handle);
    static void Submit(const EyeFrame &frame);
    static void Shutdown();
};

}
