#pragma once

// Our controller uses the standard display-mode vocabulary, even for Beast.
// Native mode reuses some numbers with different meanings; never pass them through.
namespace droiddeck::viture {
constexpr int to_native_mode(int standard) {
    switch (standard) {
        case 0x31: return 0x31; // 1080p 60
        case 0x33: return 0x32; // 1080p 90
        case 0x34: return 0x33; // 1080p 120
        case 0x32: return 0x37; // 1080p SBS 60
        case 0x35: return 0x38; // 1080p SBS 90
        case 0x41: return 0x34; // 1200p 60
        case 0x43: return 0x35; // 1200p 90
        case 0x44: return 0x36; // 1200p 120
        case 0x42: return 0x3a; // 1200p SBS 60
        case 0x45: return 0x3b; // 1200p SBS 90
        default: return -4;
    }
}
constexpr int from_native_mode(int native) {
    switch (native) {
        case 0x31: return 0x31;
        case 0x32: return 0x33;
        case 0x33: return 0x34;
        case 0x37: return 0x32;
        case 0x38: return 0x35;
        case 0x34: return 0x41;
        case 0x35: return 0x43;
        case 0x36: return 0x44;
        case 0x3a: return 0x42;
        case 0x3b: return 0x45;
        default: return -4; // Ultrawide is not stereo.
    }
}
}
