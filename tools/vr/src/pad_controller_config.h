#pragma once
#include <cstdio>
#include <cstring>

#define DROIDDECK_VR_CONTROLLERS_FILE "droiddeck-vr-controllers"

enum class PadProfile { Off, Index, Touch, Vive };

struct PadControllerConfig {
    PadProfile profile = PadProfile::Index;
    bool sticksMoveHands = false;
};

// One key=value per line, written by the app before SteamVR starts.
inline PadControllerConfig pad_controller_config(const char *text) {
    PadControllerConfig config;
    if (!text) return config;
    for (const char *line = text; *line;) {
        const char *end = std::strchr(line, '\n');
        const size_t length = end ? size_t(end - line) : std::strlen(line);
        const auto is = [&](const char *expected) {
            return std::strlen(expected) == length && !std::strncmp(line, expected, length);
        };
        if (is("emulate=off")) config.profile = PadProfile::Off;
        else if (is("emulate=index")) config.profile = PadProfile::Index;
        else if (is("emulate=touch")) config.profile = PadProfile::Touch;
        else if (is("emulate=vive")) config.profile = PadProfile::Vive;
        else if (is("sticks=hands")) config.sticksMoveHands = true;
        else if (is("sticks=game")) config.sticksMoveHands = false;
        if (!end) break;
        line = end + 1;
    }
    return config;
}

inline PadControllerConfig read_pad_controller_config(const char *path) {
    char text[256] = {};
    if (FILE *f = std::fopen(path, "r")) {
        const size_t n = std::fread(text, 1, sizeof(text) - 1, f);
        text[n] = 0;
        std::fclose(f);
    }
    return pad_controller_config(text);
}

inline const char *pad_profile_name(PadProfile profile) {
    switch (profile) {
    case PadProfile::Off: return "off";
    case PadProfile::Index: return "Index";
    case PadProfile::Touch: return "Quest Touch";
    case PadProfile::Vive: return "Vive";
    }
    return "?";
}
