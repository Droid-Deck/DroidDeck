#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include "xr_model_state.h"

struct XrModelNodeTransform {
    std::string name;
    std::array<float, 4> orientation{0, 0, 0, 1};
    std::array<float, 3> position{};
    bool visible = true;
};

class XrModelAnimation {
public:
    XrModelAnimation();
    ~XrModelAnimation();
    bool initialize(const std::vector<uint8_t> &glb, const std::vector<XrModelNodeTransform> &states,
                    std::string &error);
    uint32_t update(const std::vector<XrModelNodeTransform> &states, DroidDeckXrModelNode *output);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

bool xr_export_model_asset(const std::vector<uint8_t> &glb, const std::string &directory,
                           const std::string &name, std::string &error,
                           const std::vector<XrModelNodeTransform> &nodes = {});
