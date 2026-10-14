#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include <vulkan/vulkan.h>

#include "lsfg_common.hpp"
#include "lsfg_dll.h"
#include "lsfg_governor.h"
#include "lsfg_pacer.hpp"

namespace lsfg {

class LsfgShaders;
class LsfgChain;

constexpr uint32_t kMaxGenerations = 3;

class Engine {
public:
    Engine();
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& cachePath,
              uint32_t spirvTarget = kSpirv16);

    bool valid() const { return shaders_ != nullptr && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    void configure(uint32_t multiplier, uint32_t targetRate, float flowScale, float refreshRate);
    void setRefreshRate(float refreshRate);
    void setGuestExtent(uint32_t width, uint32_t height);

    bool needsRebuild(uint32_t width, uint32_t height, VkFormat format) const;
    bool prepare(uint32_t width, uint32_t height, VkFormat format);

    uint32_t plan(uint32_t capacity, uint64_t sourceFrames);

    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);

    void generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t targetIndex,
                      VkImage targetImage, VkImageView targetView,
                      uint32_t width, uint32_t height);

    uint32_t acceptedGenerations() const { return governor_.accepted(); }
    int      thermalStatus() const { return governor_.thermalStatus(); }
    float    sourceRate() const;
    float    loopRate() const { return presentedRate_; }
    void     setPresentedRate(float fps) { presentedRate_ = fps; }

    void forgetTargets();
    void reset();

private:
    float effectiveFlowScale(uint32_t width) const;

    bool historyFresh() const { return haveCopied_ && lastCopiedCount_ + 1 == frameCount_; }

    bool followEveryFrame() const { return warm_ && pacer_.Config().target_rate != 0; }

    Device      device_{};
    std::string cachePath_;
    std::unique_ptr<LsfgShaders> shaders_;
    std::unique_ptr<LsfgChain>   chain_;
    LsfgPacer      pacer_;
    ProbeGovernor  governor_;
    bool           governorEnabled_ = false;
    LsfgPlan    plan_{};

    VkExtent2D builtExtent_{};
    VkExtent2D peakGuestExtent_{};
    VkFormat   builtFormat_{VK_FORMAT_UNDEFINED};
    float      builtFlowScale_{};
    float      flowScale_{1.0f};

    float    presentedRate_{};

    uint64_t frameCount_{};
    uint64_t lastCopiedCount_{};
    bool     haveCopied_{};
    bool     primeHistory_{};
    uint64_t primeLogCount_{};
    uint64_t lastCount_{};
    size_t   lastGenerations_{};
    uint64_t planCalls_{};
    uint32_t warmStreak_{};
    bool     warm_{};
    bool     generating_{};
    bool     unavailable_{};
};

}
