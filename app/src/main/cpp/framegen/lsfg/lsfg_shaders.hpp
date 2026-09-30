// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// LSFG from the work of Camille LaVey / the Eden Emulator Project, following
// upstream lsfg-vk. Ported to WinNative and DroidDeck by @maxjivi05; only the
// Vulkan dispatch differs here (see lsfg_vkd.h).

#pragma once

#include <cstdint>
#include <map>
#include <string>
#include "lsfg_dll.h"
#include "lsfg_vkd.h"

namespace lsfg {

class Device;

class LsfgShaders {
public:
    LsfgShaders() = default;
    // `spirv_target` is the highest SPIR-V version the device accepts (see
    // lsfg_probe.h); cached modules above it are lowered before creation.
    LsfgShaders(const Device& device, const std::string& cache_path,
                uint32_t spirv_target = kSpirv16);
    ~LsfgShaders();

    LsfgShaders(const LsfgShaders&) = delete;
    LsfgShaders& operator=(const LsfgShaders&) = delete;

    [[nodiscard]] bool IsValid() const {
        return valid;
    }

    [[nodiscard]] VkShaderModule Get(uint32_t shader_id) const;

private:
    void Release();

    VkDevice device{VK_NULL_HANDLE};
    std::map<uint32_t, VkShaderModule> modules;
    bool valid{};
};

}
