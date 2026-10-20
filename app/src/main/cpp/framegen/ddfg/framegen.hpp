#pragma once
#include "vk_dispatch.hpp"
#include "config.hpp"
#include <vector>

namespace ddfg {

class FrameGen {
public:
    bool init(const DeviceDispatch* dd, const InstanceDispatch* id,
              VkPhysicalDevice phys, VkDevice dev, uint32_t queueFamily, VkQueue queue);
    void configure(const Config& c);
    bool onResize(VkExtent2D extent, VkFormat colorFormat, bool force = false);
    void record(VkCommandBuffer cmd, VkImageView prevView, VkImageView currView,
                VkImageView outView, float alpha, uint32_t gmSlot = 0,
                uint32_t synthSlot = 0, bool reuseFlow = false);
    void destroy();
    bool valid() const { return device_ != VK_NULL_HANDLE && ready_; }
    const Config& config() const { return cfg_; }

    struct Img { VkImage image = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE;
                 VkImageView view = VK_NULL_HANDLE; VkExtent2D extent{}; VkFormat fmt{}; };
    struct Pipe { VkPipeline pipeline = VK_NULL_HANDLE; VkPipelineLayout layout = VK_NULL_HANDLE;
                  VkDescriptorSetLayout setLayout = VK_NULL_HANDLE; VkShaderModule module = VK_NULL_HANDLE; };



    static constexpr int kGmSlots   = 3;
    static constexpr int kSynthSlots = 3;
    static constexpr int kGmLevel   = 3;
    static constexpr int kGmThreads = 512;
    static constexpr int kGmStride  = 32;







    static constexpr int kFlowFinestDefault = 2;



    static int flowFinestForPreset(int preset) {
        switch (preset) {
            case 0: return 1;
            case 2: return 3;
            case 1: default: return kFlowFinestDefault;
        }
    }
    int flowFinest() const { return flowFinest_; }

private:
    uint32_t findMemType(uint32_t bits, VkMemoryPropertyFlags props) const;
    bool makeImage(Img& out, VkExtent2D ext, VkFormat fmt, VkImageUsageFlags usage);
    void destroyImage(Img& i);
    bool makeGmBuffers();



    bool updateGlobalMotion(uint32_t gmSlot, VkExtent2D fullExtent);
    bool makePipe(Pipe& p, int shaderId,
                  const std::vector<VkDescriptorType>& bindings);
    void destroyPipe(Pipe& p);
    void barrier(VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to);

    const DeviceDispatch*   dd_ = nullptr;
    const InstanceDispatch*  id_ = nullptr;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice         device_ = VK_NULL_HANDLE;
    VkQueue          queue_ = VK_NULL_HANDLE;
    uint32_t         queueFamily_ = 0;
    VkPhysicalDeviceMemoryProperties memProps_{};
    VkSampler        linear_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;

    Config    cfg_;
    VkExtent2D extent_{};
    bool ready_ = false;
    int  flowFinest_ = kFlowFinestDefault;

    Pipe pLuma_, pDown_, pFlow_, pFlowM4_, pExpand_, pExpandM4_, pSynth_;
    Pipe pGmReduce_, pGmPrewarp_;
    Pipe pFlowReg_;

    static const int kLevels = 7;
    std::vector<Img> pyrA_, pyrB_;
    std::vector<Img> pyrAs_;
    std::vector<Img> flowLvl_;
    Img flowExpA_, flowExpB_;





    Img flowRegA_, flowRegB_;

    VkBuffer       gmSsbo_[kGmSlots]   {};
    VkDeviceMemory gmSsboMem_[kGmSlots]{};
    void*          gmSsboPtr_[kGmSlots]{};
    bool           gmBuffersReady_ = false;
    double gmRun_[6]              {};
    double gmLinAt_[kGmSlots][6] {};
    bool   gmSlotValid_[kGmSlots]{};
    bool   gmHaveEst_      = false;
    int    gmStableCount_  = 0;
    bool   gmEngaged_      = false;
    float  gmLastTransPx_  = 0.0f;
    unsigned long long gmFrames_ = 0;
};

}
