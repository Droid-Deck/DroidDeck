// Puts SteamVR's eye textures on the screen for the Cardboard driver (driver_droiddeck.cpp).
//
// The driver-direct component hands each frame over as two eye textures: shared Vulkan images,
// dmabufs on Linux. The presenter imports them into a Vulkan device of its own and draws them side by
// side into a fullscreen X11 window on gamescope's Xwayland, marked as an external overlay so gamescope
// shows it above whatever app has focus. DroidDeck's compositor, in Cardboard mode with a
// side-by-side source, then puts the lens distortion on it for the viewer.
//
//   SteamVR compositor -> eye textures -> [presenter: blit L|R] -> X11 overlay -> gamescope
//     -> DroidDeck compositor -> Cardboard lens pass -> panel
//
// Everything is loaded at runtime (libvulkan.so.1, libX11.so.6): the driver links against nothing
// the cross build image lacks.
#pragma once
#include <cstdint>

namespace droiddeck {

struct EyeFrame {
    uint64_t texture[2]; // SharedTextureHandle_t per eye
    float bounds[2][4];  // uMin, vMin, uMax, vMax per eye
};

class Presenter {
public:
    // Called from CreateSwapTextureSet: the three images of one set, as SteamVR made them.
    static void AddTextures(const uint64_t handles[3], const int fds[3], uint32_t vkFormat, uint32_t width, uint32_t height);
    static void RemoveTexture(uint64_t handle);
    // Called from Present: the frame's eye textures; returns at once (a thread of its own presents).
    static void Submit(const EyeFrame &frame);
    static void Shutdown();
};

} // namespace droiddeck
