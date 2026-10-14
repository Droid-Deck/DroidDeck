#pragma once
#include <cstdint>
#include <vulkan/vulkan.h>
#include "framegen.hpp"
#include "vk_dispatch.hpp"

struct VkTable;

namespace winfg {

constexpr uint32_t kMaxGenerations = 3;

class Engine {
public:
    Engine();
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    bool init(const VkTable& table, VkPhysicalDevice phys, VkDevice dev,
              uint32_t queueFamily, VkQueue queue);
    bool valid() const { return fg_.valid() && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    void configure(uint32_t multiplier, int model, int perfPreset, float flowScale);

    bool prepare(uint32_t width, uint32_t height, VkFormat format);

    uint32_t plan(uint32_t capacity) const;

    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);

    void generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t count,
                      VkImage targetImage, VkImageView targetView,
                      uint32_t width, uint32_t height);

    void reset();

private:
    struct Slot { VkImage img = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE;
                  VkImageView view = VK_NULL_HANDLE; bool valid = false; };
    bool  makeSlot(Slot& s, uint32_t w, uint32_t h, VkFormat fmt);
    void  destroySlot(Slot& s);
    void  destroyRing();
    uint32_t memType(uint32_t bits, VkMemoryPropertyFlags props) const;

    DeviceDispatch   dd_{};
    InstanceDispatch id_{};
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice         dev_  = VK_NULL_HANDLE;
    uint32_t         qf_   = 0;
    VkQueue          queue_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memProps_{};

    FrameGen fg_;
    Config   cfg_;
    bool     unavailable_ = false;

    Slot     ring_[2];
    int      curr_ = 0;
    uint32_t history_ = 0;
    uint32_t gmSlot_ = 0;
    uint32_t builtW_ = 0, builtH_ = 0;
    VkFormat builtFmt_ = VK_FORMAT_UNDEFINED;
    uint64_t frames_ = 0;
};

}
