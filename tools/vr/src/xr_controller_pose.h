#pragma once
#include "arm_model.h"
#include "xr_tracking.h"
#include <string>

namespace droiddeck {

inline bool XrPicoController(uint32_t profile) {
    return profile == DROIDDECK_XR_CONTROLLER_PICO_NEO3 ||
           profile == DROIDDECK_XR_CONTROLLER_PICO4 || profile == DROIDDECK_XR_CONTROLLER_PICO4S;
}

inline Quat XrOrientation(const DroidDeckXrPose &pose) {
    return Normalize({pose.orientation[3], pose.orientation[0], pose.orientation[1], pose.orientation[2]});
}

inline Vec3 XrPosition(const DroidDeckXrPose &pose) {
    return {pose.position[0], pose.position[1], pose.position[2]};
}

inline std::string XrRuntimeModelName(const uint8_t key[16], bool right) {
    bool present = false;
    std::string result = "droiddeck_xr_runtime_";
    for (int i = 0; i < 16; ++i) {
        present |= key[i] != 0;
        result += "0123456789abcdef"[key[i] >> 4];
        result += "0123456789abcdef"[key[i] & 15];
    }
    return present ? result + (right ? "_right" : "_left") : std::string{};
}

inline DroidDeckXrPose XrRelativePose(const DroidDeckXrPose &parent, const DroidDeckXrPose &child) {
    const Quat inverse = Conjugate(XrOrientation(parent));
    const Quat rotation = Normalize(inverse * XrOrientation(child));
    const Vec3 position = Rotate(inverse, XrPosition(child) - XrPosition(parent));
    return {{float(rotation.x), float(rotation.y), float(rotation.z), float(rotation.w)},
            {float(position.x), float(position.y), float(position.z)}, child.flags};
}

struct XrControllerModel {
    const char *name, *manufacturer;
    std::string renderModel;
};

inline XrControllerModel XrModel(uint32_t profile, bool right) {
    const std::string side = right ? "right" : "left";
    switch (profile) {
    case DROIDDECK_XR_CONTROLLER_PICO_NEO3:
        return {"PICO Neo3 Controller", "PICO", "{droiddeck}droiddeck_pico_neo3_" + side};
    case DROIDDECK_XR_CONTROLLER_PICO4:
        return {"PICO 4 Controller", "PICO", "{droiddeck}droiddeck_pico_4_" + side};
    case DROIDDECK_XR_CONTROLLER_PICO4S:
        return {"PICO 4 Ultra Controller", "PICO", "{droiddeck}droiddeck_pico_4u_" + side};
    case DROIDDECK_XR_CONTROLLER_TOUCH:
        return {"DroidDeck Quest Touch", "Meta", "oculus_quest_plus_controller_" + side};
    default:
        return {"DroidDeck OpenXR Controller", "DroidDeck", "{droiddeck}droiddeck_hand_" + side};
    }
}

}
