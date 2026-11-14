// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <optional>

namespace KWin::Wayland
{
struct IndependentOutputPosition {
    int x;
    int y;
};

inline std::optional<IndependentOutputPosition> independentOutputPosition(int slot, int width, int height)
{
    if (slot < 0 || slot >= 64 || width < 1 || height < 1 || width > 8192 || height > 8192) {
        return std::nullopt;
    }
    // Legacy Qt XCB windows clamp both position coordinates to 16383.
    constexpr int extent = 16384;
    constexpr int origin = 8192;
    constexpr int gap = 64;
    const int columns = (extent - origin + gap) / (width + gap);
    if (columns < 1) {
        return std::nullopt;
    }
    const int x = origin + (slot % columns) * (width + gap);
    const int y = (slot / columns) * (height + gap);
    if (x + width > extent || y + height > extent) {
        return std::nullopt;
    }
    return IndependentOutputPosition{x, y};
}
}
