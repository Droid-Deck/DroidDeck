#ifndef DROIDDECK_RENDER_TARGET_H
#define DROIDDECK_RENDER_TARGET_H

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace droiddeck {

struct RenderTargetSize { uint32_t width, height; };

inline RenderTargetSize RenderTarget(float aspect, uint32_t selectedHeight, uint32_t panelHeight) {
    uint32_t height = selectedHeight > 0 ? std::min(selectedHeight, 8192u) : 720u;
    if (panelHeight > 0) height = std::min(height, panelHeight);
    if (!std::isfinite(aspect) || aspect <= 0.0f) aspect = 1.0f;
    const float width = std::clamp(height * aspect, 1.0f, 8192.0f);
    return {static_cast<uint32_t>(std::lround(width)), height};
}

}
#endif
