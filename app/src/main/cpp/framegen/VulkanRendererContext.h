#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <vulkan/vulkan_android.h>
struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateQueryPool CreateQueryPool;
    PFN_vkDestroyQueryPool DestroyQueryPool;
    PFN_vkCmdResetQueryPool CmdResetQueryPool;
    PFN_vkCmdWriteTimestamp CmdWriteTimestamp;
    PFN_vkGetQueryPoolResults GetQueryPoolResults;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdBlitImage CmdBlitImage;

    PFN_vkCmdDispatch CmdDispatch;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkUnmapMemory UnmapMemory;
    PFN_vkCmdClearColorImage CmdClearColorImage;
    PFN_vkResetDescriptorPool ResetDescriptorPool;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "DroidDeck_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"DroidDeck_Scanout",__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vector>
#include <unordered_map>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>
#include <chrono>

#include "../scanout/ScanoutContext.h"

#include "lsfg/lsfg_probe.h"
#include <memory>
#include <string>

namespace lsfg { class Engine; }
namespace ddfg { class Engine; }

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;




struct SgsrPushConstants {
    float ndc[4];
    float viewportInfo[4];
    float edgeSharpness;
};
struct NisPushConstants {
    float ndc[4];
    float viewportInfo[4];
    float sharpness;
};
struct EasuPushConstants {
    float    ndc[4];
    uint32_t con0[4], con1[4], con2[4], con3[4];
    float    outW, outH;
};
struct RcasPushConstants {
    float    ndc[4];
    uint32_t con[4];
    float    outW, outH;
};


struct CasPushConstants {
    float ndc[4];
    float resolution[2];
    float sharpness;
    float _pad;
};
struct HdrPushConstants {
    float ndc[4];
    float resolution[2];
};


struct FxaaPushConstants {
    float ndc[4];
    float resolution[2];
};
struct ToonPushConstants {
    float ndc[4];
    float resolution[2];
};
struct ColorPushConstants {
    float ndc[4];
    float brightness;
    float contrast;
    float gamma;
    float saturation;


};
struct NtscPushConstants {
    float ndc[4];
    float resolution[2];
    float frameCount;
};
struct CrtPushConstants {
    float ndc[4];
    float resolution[2];
};
struct DebandPushConstants {
    float ndc[4];
    float resolution[2];
    float strength;
};

class VulkanRendererContext {
public:
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight, void* adrenotoolsHandle = nullptr,
                          bool lsfgVk11Compat = false);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void setClipRegion(int x, int y, int w, int h);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int x, int y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    void updateCursorImage(void* pixels, short w, short h, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    void initScanout();
    void destroyScanout();
    void applyScanoutBuffer();
    void initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void scanoutSetDst(int x, int y, int w, int h);
    void scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd = -1);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY);
    ScanoutContext scanout;
    std::atomic<bool>& scanoutActive = scanout.scanoutActive;
    std::atomic<bool>& gameFrameDelivered = scanout.gameFrameDelivered;
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; scanout.setVerboseLog(v); }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    std::atomic<bool> scanoutBlackFrameDone{false};
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setUpscaler(int mode);
    void setHqDownscale(bool enabled);
    void setCas(bool enabled, int sharpness);
    void setHdr(bool enabled);
    void setDeband(bool enabled, int strength);
    void setUpscaleSharpness(int sharpness);
    void setFxaa(bool enabled);
    void setToon(bool enabled);
    void setCrt(bool enabled);
    void setNtsc(bool enabled);
    void setColorGrade(float brightness, float contrast, float gamma, float saturation);
    void setSwapRB(bool enabled);
    void setPresentMode(VkPresentModeKHR mode);
    std::vector<int> getSupportedPresentModes() const;

    const lsfg::Caps& lsfgCaps() const { return lsfgCaps_; }

    void setFrameGenArmed(bool armed, int multiplier);
    bool frameGenArmed() const { return fgArmed_.load(std::memory_order_relaxed); }
    void frameGenStats(float out[6]) const;
    int frameGenProblem() const;
    void setLsfgCachePath(const char* path);
    static constexpr int32_t kFgCaptureGame = -1;
    void setFrameGenTuning(float flowScale, float refreshHz, int32_t captureHeight = 0);
    void setFrameGenEngine(int kind);
    void setDdFgTuning(int model, int perfPreset);

private:
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
    };

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    std::atomic<int> clipRegionX{0}, clipRegionY{0}, clipRegionW{0}, clipRegionH{0};
    void* adrenotoolsHandle = nullptr;
    bool  lsfgVk11Compat_   = false;
    int filterMode = 0;
    bool swapRB = false;
    float maxAnisotropy           = 1.0f;
    bool  cubicSupported          = false;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;

    std::vector<WinTex>    deleteQueue;
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePreUpload;
    std::vector<VkImageMemoryBarrier>  framePostUpload;


    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    VkBuffer         cursorStg  = VK_NULL_HANDLE;
    VkDeviceMemory   cursorStgM = VK_NULL_HANDLE;
    void*            cursorStgP = nullptr;
    VkDeviceSize     cursorStgC = 0;
    VkDeviceSize     cursorUploadSize = 0;

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;
    VkExtent2D       swapchainExt;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline    = VK_NULL_HANDLE;

    int               upscalerMode      = 0;
    bool              hqDownscale       = false;

    bool              casEnabled        = false;
    int               casSharpness      = 60;
    bool              hdrEnabled        = false;
    float             upscaleSharpness01 = 0.75f;

    bool              debandEnabled     = false;
    int               debandStrength    = 100;

    bool              fxaaEnabled       = false;
    bool              toonEnabled       = false;
    bool              crtEnabled        = false;
    bool              ntscEnabled       = false;
    bool              colorEnabled      = false;
    float             colorBrightness   = 0.0f;
    float             colorContrast     = 0.0f;
    float             colorGamma        = 1.0f;
    float             colorSaturation   = 1.0f;
    uint32_t          ntscFrameCounter  = 0;

    VkSampler         upscaleSampler    = VK_NULL_HANDLE;

    struct CompositeTarget {
        VkImage         img         = VK_NULL_HANDLE;
        VkDeviceMemory  mem         = VK_NULL_HANDLE;
        VkImageView     view        = VK_NULL_HANDLE;
        VkImageView     storageView = VK_NULL_HANDLE;
        VkFramebuffer   fb          = VK_NULL_HANDLE;
        VkDescriptorSet ds          = VK_NULL_HANDLE;
    };
    static constexpr uint32_t kMaxCompositeTargets = 7;

    std::vector<CompositeTarget> compositeTargets;
    VkRenderPass compositeRenderPass = VK_NULL_HANDLE;
    uint32_t     compositeW = 0, compositeH = 0;
    uint32_t     compositeIndex = 0;
    bool         compositeArmed = false;
    bool         swapchainTransferDst = false;

    std::atomic<bool> fgArmed_{false};
    std::atomic<int>  fgMultiplier_{0};
    std::atomic<float> fgFlowScale_{1.0f};
    std::atomic<float> fgRefreshHz_{0.0f};
    std::atomic<bool>  fgConfigDirty_{true};
    std::atomic<int32_t> fgCaptureHeight_{0};
    void compositeExtentFor(uint32_t& w, uint32_t& h) const;
    VkExtent2D renderExtent() const {
        if (compositeActive() && (compositeW != swapchainExt.width || compositeH != swapchainExt.height))
            return VkExtent2D{compositeW, compositeH};
        return swapchainExt;
    }
    void recordCompositeToSwapchainTransfer(VkCommandBuffer cb, VkImage src, uint32_t imgIdx);

    bool  createCompositeRenderPass();
    bool  ensureCompositeTargets(uint32_t w, uint32_t h, uint32_t count);
    void  destroyCompositeTargets();
    bool  compositeActive() const;
    VkRenderPass  targetRenderPass() const;
    VkFramebuffer targetFramebuffer(uint32_t imgIdx) const;
    void  copyCompositeToSwapchain(VkCommandBuffer cb, uint32_t imgIdx);

    VkRenderPass cursorOverlayRenderPass = VK_NULL_HANDLE;
    struct CursorOverlay {
        bool  draw = false;
        float ox = 0, oy = 0, sx = 0, sy = 0, cw = 0, ch = 0;
        short ptrX = 0, ptrY = 0, hotX = 0, hotY = 0, w = 0, h = 0;
    };
    CursorOverlay cursorOverlay_{};

    bool createCursorOverlayRenderPass();
    void recordCursorOverlay(VkCommandBuffer cb, uint32_t imgIdx);
    bool cursorDrawnPerPresent() const;







    static constexpr uint32_t kMaxPresentsPerFrame = 4;
    struct FrameGenPlan {
        uint32_t generations = 0;
        uint32_t presents    = 1;
        uint32_t imgIdx[kMaxPresentsPerFrame] = {};
    };
    FrameGenPlan fgPlan_{};
    std::unique_ptr<lsfg::Engine> lsfgEngine_;
    uint64_t    fgSourceFrames_ = 0;
    std::string lsfgCachePath_;
    bool        lsfgEngineTried_ = false;
    std::unique_ptr<ddfg::Engine> ddfgEngine_;
    bool        ddfgEngineTried_ = false;
    std::atomic<int> fgEngineKind_{0};
    std::atomic<int> fgModel_{4};
    std::atomic<int> fgPerfPreset_{1};
    bool ensureDdFgEngine();
    bool fgCapsOk() const;

    uint32_t syncSlot(uint32_t k) const { return currentFrame * kMaxPresentsPerFrame + k; }
    void recreateSyncObjects();
    uint32_t fgAcquireFailLog_ = 0;

    float    fgPresentedRate_   = 0.0f;
    float    fgSourceRate_      = 0.0f;
    uint32_t fgSourceAccum_     = 0;
    uint32_t fgPresentAccum_    = 0;
    std::chrono::steady_clock::time_point fgRateWindowStart_{};
    bool     fgRateWindowOpen_  = false;
    void     trackPresentedRate(uint32_t presents);

    bool ensureLsfgEngine();
    uint32_t cmdSlot(uint32_t k) const { return currentFrame * kMaxPresentsPerFrame + k; }
    void recordFrameGenProcess(VkCommandBuffer cb);
    void recordFrameGenGeneration(VkCommandBuffer cb, uint32_t g);

    VkQueryPool fgQueryPool_        = VK_NULL_HANDLE;
    bool        fgTimestampsOk_     = false;
    float       fgTimestampPeriodNs_ = 0.0f;
    bool        fgQueryPending_[MAX_FRAMES_IN_FLIGHT] = {};
    uint32_t    fgQueryGens_[MAX_FRAMES_IN_FLIGHT]    = {};
    float       fgChainMsPerGen_    = -1.0f;
    uint32_t    fgChainLogCount_    = 0;
    void ensureFgQueryPool();
    void destroyFgQueryPool();
    void readFgQueryResult();

    VkFormat          offscreenFmt      = VK_FORMAT_R8G8B8A8_UNORM;
    VkRenderPass      offscreenRenderPass = VK_NULL_HANDLE;
    VkPipelineLayout  postPipeLayout    = VK_NULL_HANDLE;
    VkPipeline        sgsrPipeline      = VK_NULL_HANDLE;
    VkPipeline        nisPipeline       = VK_NULL_HANDLE;
    VkPipeline        sgsrQualityPipeline = VK_NULL_HANDLE;
    VkPipeline        easuPipeline      = VK_NULL_HANDLE;
    VkPipeline        rcasPipeline      = VK_NULL_HANDLE;
    VkPipeline        downscalePipeline = VK_NULL_HANDLE;



    VkPipeline        casPipelineOff    = VK_NULL_HANDLE;
    VkPipeline        casPipelineSwap   = VK_NULL_HANDLE;
    VkPipeline        hdrPipelineOff    = VK_NULL_HANDLE;
    VkPipeline        hdrPipelineSwap   = VK_NULL_HANDLE;
    VkPipeline        fxaaPipelineOff   = VK_NULL_HANDLE;
    VkPipeline        fxaaPipelineSwap  = VK_NULL_HANDLE;
    VkPipeline        toonPipelineOff   = VK_NULL_HANDLE;
    VkPipeline        toonPipelineSwap  = VK_NULL_HANDLE;
    VkPipeline        colorPipelineOff  = VK_NULL_HANDLE;
    VkPipeline        colorPipelineSwap = VK_NULL_HANDLE;
    VkPipeline        ntscPipelineOff   = VK_NULL_HANDLE;
    VkPipeline        ntscPipelineSwap  = VK_NULL_HANDLE;
    VkPipeline        crtPipelineOff    = VK_NULL_HANDLE;
    VkPipeline        crtPipelineSwap   = VK_NULL_HANDLE;
    VkPipeline        debandPipelineSwap = VK_NULL_HANDLE;

    VkImage           offscreenImg  = VK_NULL_HANDLE;
    VkDeviceMemory    offscreenMem  = VK_NULL_HANDLE;
    VkImageView       offscreenView = VK_NULL_HANDLE;
    VkFramebuffer     offscreenFB   = VK_NULL_HANDLE;
    VkDescriptorSet   offscreenDS   = VK_NULL_HANDLE;
    int               offscreenW = 0, offscreenH = 0;

    VkImage           midImg  = VK_NULL_HANDLE;
    VkDeviceMemory    midMem  = VK_NULL_HANDLE;
    VkImageView       midView = VK_NULL_HANDLE;
    VkFramebuffer     midFB   = VK_NULL_HANDLE;
    VkDescriptorSet   midDS   = VK_NULL_HANDLE;
    int               midW = 0, midH = 0;

    VkImage           fx1Img  = VK_NULL_HANDLE;
    VkDeviceMemory    fx1Mem  = VK_NULL_HANDLE;
    VkImageView       fx1View = VK_NULL_HANDLE;
    VkFramebuffer     fx1FB   = VK_NULL_HANDLE;
    VkDescriptorSet   fx1DS   = VK_NULL_HANDLE;
    int               fx1W = 0, fx1H = 0;

    VkImage           fx2Img  = VK_NULL_HANDLE;
    VkDeviceMemory    fx2Mem  = VK_NULL_HANDLE;
    VkImageView       fx2View = VK_NULL_HANDLE;
    VkFramebuffer     fx2FB   = VK_NULL_HANDLE;
    VkDescriptorSet   fx2DS   = VK_NULL_HANDLE;
    int               fx2W = 0, fx2H = 0;

    struct UpscaleFrame {
        bool active = false;
        int  mode = 0;
        int  outX = 0, outY = 0, outW = 0, outH = 0;
        bool cas = false;
        bool hdr = false;
        bool fxaa = false;
        bool toon = false;
        bool color = false;
        bool ntsc = false;
        bool crt = false;
        bool deband = false;
        SgsrPushConstants      sgsrPC{};
        NisPushConstants       nisPC{};
        EasuPushConstants      easuPC{};
        RcasPushConstants      rcasPC{};
        CasPushConstants       casPC{};
        HdrPushConstants       hdrPC{};
        FxaaPushConstants      fxaaPC{};
        ToonPushConstants      toonPC{};
        ColorPushConstants     colorPC{};
        NtscPushConstants      ntscPC{};
        CrtPushConstants       crtPC{};
        DebandPushConstants    debandPC{};
    } upFrame;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    std::shared_mutex frameMutex;

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    lsfg::Caps lsfgCaps_{};

    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createUpscaleSampler();
    void createOffscreenRenderPass();
    void createPostPipelines();
    VkPipeline createPostPipeline(const uint32_t* fragCode, size_t fragSz, VkRenderPass rp);
    bool createColorTarget(int w, int h, VkImage& img, VkDeviceMemory& mem,
                           VkImageView& view, VkFramebuffer& fb, VkDescriptorSet& ds);
    void destroyColorTarget(VkImage& img, VkDeviceMemory& mem, VkImageView& view,
                            VkFramebuffer& fb, VkDescriptorSet& ds);
    bool ensureOffscreen(int w, int h);
    bool ensureMid(int w, int h);
    bool ensureFx1(int w, int h);
    bool ensureFx2(int w, int h);
    void recordUpscalePasses(VkCommandBuffer cb, uint32_t imgIdx,
                             const std::vector<DrawEntry>& draws, bool cursorDrawn,
                             short ptrX, short ptrY, short curHotX, short curHotY,
                             short curW, short curH,
                             float ox, float oy, float sx, float sy,
                             float cw, float ch);
    void planUpscaleFrame();
    void createWinTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  createWinTexResources(WinTex& wt, int w, int h);
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  cleanupAllAHBCache();
    void  flushDeleteQueue();
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz);

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
