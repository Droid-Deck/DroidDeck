#pragma once

#include <cmath>
#include <string_view>
#include "display_modes.h"

namespace droiddeck::viture {
constexpr bool supports_film(std::string_view market_name) {
    return market_name != "Lite" && market_name != "Pro 2";
}

constexpr bool supported_native_display(int value) {
    return value == 0x31 || value == 0x33 || value == 0x34 || value == 0x36 ||
        value == 0x37 || value == 0x3a || value == 0x3d || value == 0x40;
}

constexpr bool supported_bypass_display(int value) {
    return value == 0x31 || value == 0x41 || value == 0x35 || value == 0x45;
}

inline bool valid_device_control(int command, float value, int extra) {
    if (!std::isfinite(value)) return false;
    if (command == 1) return value >= 0 && value <= 1;
    if (command == 13) return value >= 0.01f && value <= 8.0f && extra >= 0 && extra <= 15;
    if (value < 0 || value > 255 || std::floor(value) != value) return false;
    const int v = static_cast<int>(value);
    switch (command) {
        case 2: return v <= 100;
        case 3: return (v == 0 && supported_bypass_display(extra)) ||
            (v == 1 && supported_native_display(extra));
        case 4: return v <= 2;
        case 5: case 12: case 14: case 16: return v == 0;
        case 6: return supported_native_display(v);
        case 7: return v >= 1 && v <= 10;
        case 8: return v <= 4;
        case 9: case 11: return v <= 1;
        case 10: return to_native_mode(v) >= 0;
        case 15: return v <= 5;
        default: return false;
    }
}
}
