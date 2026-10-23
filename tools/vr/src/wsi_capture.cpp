#define VK_NO_PROTOTYPES
#include "wsi_capture.h"
#include "xr_tracking_file.h"
#include "xr_frame_channel.h"
#include "xr_gpu_channel.h"
#include <algorithm>
#include <cstdio>
#include <memory>
#include <unordered_map>
#include <condition_variable>
#include <array>

namespace {
#define FUNCTIONS(X) \
    X(GetSwapchainImagesKHR) X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) \
    X(ResetCommandBuffer) X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) \
    X(CmdCopyImageToBuffer) X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements) \
    X(AllocateMemory) X(FreeMemory) X(BindBufferMemory) X(MapMemory) X(UnmapMemory) \
    X(InvalidateMappedMemoryRanges) X(CreateFence) X(DestroyFence) X(ResetFences) X(WaitForFences) X(QueueSubmit) \
    X(CreateSemaphore) X(DestroySemaphore) X(GetMemoryFdKHR) X(CreateImage) X(DestroyImage) \
    X(GetImageMemoryRequirements) X(BindImageMemory) X(CmdBlitImage)
struct Device {
    VkDevice handle{};
    VkPhysicalDeviceMemoryProperties memory{};
    bool foreign{};
    PFN_vkGetMemoryFdPropertiesKHR GetMemoryFdPropertiesKHR{};
    std::unordered_map<VkQueue, uint32_t> queues;
#define MEMBER(name) PFN_vk##name name{};
    FUNCTIONS(MEMBER)
#undef MEMBER
};
struct CaptureSlot {
    Device *device{};
    VkCommandPool pool{};
    VkCommandBuffer command{};
    VkBuffer buffer{};
    VkDeviceMemory memory{};
    VkImage rgbaImage{};
    VkDeviceMemory rgbaMemory{};
    int sharedFd = -1;
    bool rgbaInitialized{};
    void *mapping{};
    VkFence fence{};
    bool busy{}, queued{};
    DroidDeckXrFrameHeader header{};
    ~CaptureSlot() { Clear(); }
    void Clear() {
        if (!device) return;
        if (busy && fence) device->WaitForFences(device->handle, 1, &fence, VK_TRUE, UINT64_MAX);
        if (sharedFd >= 0) close(sharedFd);
        if (fence) device->DestroyFence(device->handle, fence, nullptr);
        if (pool) device->DestroyCommandPool(device->handle, pool, nullptr);
        if (buffer) device->DestroyBuffer(device->handle, buffer, nullptr);
        if (rgbaImage) device->DestroyImage(device->handle, rgbaImage, nullptr);
        if (rgbaMemory) device->FreeMemory(device->handle, rgbaMemory, nullptr);
        if (mapping) device->UnmapMemory(device->handle, memory);
        if (memory) device->FreeMemory(device->handle, memory, nullptr);
        device = nullptr; pool = VK_NULL_HANDLE; command = VK_NULL_HANDLE; buffer = VK_NULL_HANDLE;
        memory = VK_NULL_HANDLE; rgbaImage = VK_NULL_HANDLE; rgbaMemory = VK_NULL_HANDLE;
        sharedFd = -1; mapping = nullptr; fence = VK_NULL_HANDLE; busy = queued = rgbaInitialized = false;
    }
    bool Initialize(Device &d, VkExtent2D extent, VkFormat format, uint32_t family, int imported = -1, uint64_t size = 0) {
        device = &d;
        VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pc.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; pc.queueFamilyIndex = family;
        if (d.CreateCommandPool(d.handle, &pc, nullptr, &pool)) return false;
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = pool; ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ca.commandBufferCount = 1;
        if (d.AllocateCommandBuffers(d.handle, &ca, &command)) return false;
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        VkExternalMemoryBufferCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO};
        external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT; bc.pNext = &external;
        bc.size = uint64_t(extent.width) * extent.height * 4; bc.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (d.CreateBuffer(d.handle, &bc, nullptr, &buffer)) return false;
        VkMemoryRequirements requirements{}; d.GetBufferMemoryRequirements(d.handle, buffer, &requirements);
        VkImportMemoryFdInfoKHR import{VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR};
        VkMemoryFdPropertiesKHR fdProperties{VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (imported >= 0) {
            if (!d.GetMemoryFdPropertiesKHR || size < requirements.size
                || d.GetMemoryFdPropertiesKHR(d.handle, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, imported, &fdProperties)) return false;
            requirements.memoryTypeBits &= fdProperties.memoryTypeBits;
        }
        uint32_t type = d.memory.memoryTypeCount;
        for (uint32_t i = 0; i < d.memory.memoryTypeCount; i++) {
            auto flags = d.memory.memoryTypes[i].propertyFlags;
            if (!(requirements.memoryTypeBits & (1u << i)) || (imported < 0 && !(flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))) continue;
            type = i;
            if (flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) break;
        }
        if (type == d.memory.memoryTypeCount) return false;
        VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        VkExportMemoryAllocateInfo exported{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
        exported.handleTypes = external.handleTypes; alloc.pNext = &exported;
        alloc.allocationSize = requirements.size; alloc.memoryTypeIndex = type;
        if (imported >= 0) {
            import.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT; import.fd = dup(imported);
            if (import.fd < 0) return false;
            alloc.pNext = &import; alloc.allocationSize = size;
        }
        VkResult allocated = d.AllocateMemory(d.handle, &alloc, nullptr, &memory);
        if (allocated && import.fd >= 0 && imported >= 0) close(import.fd);
        if (allocated || d.BindBufferMemory(d.handle, buffer, memory, 0)) return false;
        if (imported < 0) {
            if (d.MapMemory(d.handle, memory, 0, VK_WHOLE_SIZE, 0, &mapping)) return false;
            VkMemoryGetFdInfoKHR getFd{VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
            getFd.memory = memory; getFd.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
            if (d.GetMemoryFdKHR(d.handle, &getFd, &sharedFd)) return false;
        }
        if (imported < 0) {
            VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            image.imageType = VK_IMAGE_TYPE_2D; image.extent = {extent.width, extent.height, 1};
            image.mipLevels = image.arrayLayers = 1; image.samples = VK_SAMPLE_COUNT_1_BIT;
            image.format = (format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB)
                ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
            image.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            if (d.CreateImage(d.handle, &image, nullptr, &rgbaImage)) return false;
            d.GetImageMemoryRequirements(d.handle, rgbaImage, &requirements);
            type = 0;
            while (type < d.memory.memoryTypeCount && !(requirements.memoryTypeBits & (1u << type))) type++;
            if (type == d.memory.memoryTypeCount) return false;
            alloc.pNext = nullptr; alloc.allocationSize = requirements.size; alloc.memoryTypeIndex = type;
            if (d.AllocateMemory(d.handle, &alloc, nullptr, &rgbaMemory)
                || d.BindImageMemory(d.handle, rgbaImage, rgbaMemory, 0)) return false;
        }
        VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (d.CreateFence(d.handle, &fc, nullptr, &fence)) return false;
        return true;
    }
};
struct Swapchain {
    explicit Swapchain(const char *trackingPath = "/run/droiddeck/" DROIDDECK_XR_FILE) : tracking(trackingPath) {}
    Device *device{};
    VkExtent2D extent{};
    VkFormat format{};
    std::vector<VkImage> images;
    std::vector<VkSemaphore> ready;
    std::array<CaptureSlot, 3> slots;
    uint64_t frame{};
    XrTrackingFile tracking;
    XrFrameSender sender;
    XrGpuSender gpuSender;
    bool gpu{};
    bool initialized{}, attempted{};
    uint32_t family{};
    std::mutex sendMutex;
    std::condition_variable sendReady;
    bool stopping{}, failed{};
    std::thread sendThread;
    ~Swapchain() {
        { std::lock_guard<std::mutex> lock(sendMutex); stopping = true; sendReady.notify_all(); }
        if (sendThread.joinable()) sendThread.join();
        for (auto semaphore : ready) if (semaphore) device->DestroySemaphore(device->handle, semaphore, nullptr);
    }
    void Run() noexcept {
        try { Loop(); }
        catch (...) {
            std::lock_guard<std::mutex> lock(sendMutex); failed = true;
            std::fprintf(stderr, "[droiddeck-vr-layer] WSI worker failed; restart SteamVR\n");
        }
    }
    void Loop() {
        for (;;) {
            CaptureSlot *slot;
            {
                std::unique_lock<std::mutex> lock(sendMutex);
                sendReady.wait(lock, [this] {
                    return stopping || std::any_of(slots.begin(), slots.end(), [](const auto &item) { return item.queued; });
                });
                slot = nullptr;
                for (auto &item : slots) if (item.queued && (!slot || item.header.frame_id < slot->header.frame_id)) slot = &item;
                if (!slot) return;
                slot->queued = false;
            }
            auto &d = *device;
            const auto header = slot->header;
            auto &fence = slot->fence;
            auto &memory = slot->memory;
            auto sharedFd = slot->sharedFd;
            int64_t begin = XrTrackingFile::Now();
            VkResult result = d.WaitForFences(d.handle, 1, &fence, VK_TRUE, UINT64_MAX);
            int64_t waited = XrTrackingFile::Now();
            VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE}; range.memory = memory; range.size = VK_WHOLE_SIZE;
            if (result == VK_SUCCESS && !gpu) result = d.InvalidateMappedMemoryRanges(d.handle, 1, &range);
            int64_t invalidated = XrTrackingFile::Now();
            if (result == VK_SUCCESS && XrTrackingFile::Now() - header.captured_ns < 200000000LL) {
                if (gpu) {
                    uint32_t flags = (format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB) ? XR_GPU_BGRA : 0;
                    if (!gpuSender.Send(header, static_cast<uint32_t>(slot - slots.data()), flags)) result = VK_ERROR_DEVICE_LOST;
                } else sender.SendShared("/run/droiddeck/" DROIDDECK_XR_FRAME_SOCKET, header, sharedFd);
            }
            if (header.frame_id % 120 == 0) std::fprintf(stderr, "[droiddeck-vr-layer] WSI ms wait=%.2f invalidate=%.2f shared=%.2f\n",
                (waited-begin)/1e6, (invalidated-waited)/1e6, (XrTrackingFile::Now()-invalidated)/1e6);
            std::lock_guard<std::mutex> lock(sendMutex);
            if (result != VK_SUCCESS || sender.SharedLeaseLost()) {
                std::fprintf(stderr, "[droiddeck-vr-layer] WSI buffer quarantined: result=%d leaseLost=%d; restart SteamVR\n", result, sender.SharedLeaseLost());
                failed = true; return;
            }
            slot->busy = false;
        }
    }
    bool Initialize(VkSwapchainKHR chain, VkQueue queue) {
        if (initialized) return true;
        if (attempted) return false;
        attempted = true;
        auto &d = *device;
        auto queueFamily = d.queues.find(queue);
        if (queueFamily == d.queues.end()) return false;
        family = queueFamily->second;
        uint32_t count = 0;
        if (d.GetSwapchainImagesKHR(d.handle, chain, &count, nullptr) || !count) return false;
        images.resize(count);
        if (d.GetSwapchainImagesKHR(d.handle, chain, &count, images.data())) return false;
        ready.resize(count);
        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        for (auto &semaphore : ready) if (d.CreateSemaphore(d.handle, &semaphoreInfo, nullptr, &semaphore)) return false;
        XrGpuPoolInfo info;
        std::array<int, XR_GPU_SLOTS> fds{{-1, -1, -1}};
        gpu = d.foreign && gpuSender.Connect("/run/droiddeck/" DROIDDECK_XR_GPU_SOCKET, extent.width, extent.height, info, fds);
        if (gpu) {
            for (unsigned index = 0; index < slots.size(); index++)
                if (!slots[index].Initialize(d, extent, format, family, fds[index], info.sizes[index])) { gpu = false; break; }
            for (int fd : fds) if (fd >= 0) close(fd);
        }
        if (!gpu) {
            gpuSender.Close();
            for (auto &slot : slots) { slot.Clear(); if (!slot.Initialize(d, extent, format, family)) return false; }
        }
        sendThread = std::thread([this] { Run(); });
        initialized = true;
        std::fprintf(stderr, "[droiddeck-vr-layer] capturing compositor swapchain %ux%u format %d channel=%s\n", extent.width, extent.height, format, gpu ? "gpu" : "cpu");
        return true;
    }
    VkResult Fail(VkResult result, CaptureSlot &slot) {
        std::lock_guard<std::mutex> lock(sendMutex);
        failed = true; slot.busy = false;
        return result;
    }
    bool Header(DroidDeckXrFrameHeader &header) {
        DroidDeckXrTracking packet{};
        if (!tracking.Read(&packet) || !XrTrackingFile::Fresh(packet)) return false;
        header = {};
        header.magic = DROIDDECK_XR_FRAME_MAGIC; header.version = DROIDDECK_XR_FRAME_VERSION;
        header.width = extent.width; header.height = extent.height;
        header.bytes = extent.width * extent.height * 4;
        header.color_space = DROIDDECK_XR_COLOR_SRGB_RGBA8;
        header.frame_id = ++frame; header.captured_ns = XrTrackingFile::Now();
        for (int eye = 0; eye < 2; eye++) {
            const auto &pose = packet.eyes[eye].pose;
            const float x = pose.orientation[0], y = pose.orientation[1], z = pose.orientation[2], w = pose.orientation[3];
            float matrix[3][4] = {{1-2*(y*y+z*z),2*(x*y-w*z),2*(x*z+w*y),pose.position[0]},
                                  {2*(x*y+w*z),1-2*(x*x+z*z),2*(y*z-w*x),pose.position[1]},
                                  {2*(x*z-w*y),2*(y*z+w*x),1-2*(x*x+y*y),pose.position[2]}};
            std::memcpy(header.eye_pose[eye], matrix, sizeof(matrix));
            const auto *fov = packet.eyes[eye].fov;
            float left = std::tan(fov[0]), right = std::tan(fov[1]), up = std::tan(fov[2]), down = std::tan(fov[3]);
            auto &p = header.projection[eye];
            p[0][0] = 2/(right-left); p[0][2] = (right+left)/(right-left);
            p[1][1] = 2/(up-down); p[1][2] = (up+down)/(up-down);
            p[2][2] = -1; p[2][3] = -0.2f; p[3][2] = -1;
        }
        return xr_frame_valid(header);
    }
    VkResult Capture(VkQueue queue, uint32_t index, const VkPresentInfoKHR &present, VkSemaphore *completion) {
        auto &d = *device;
        if (index >= images.size()) return VK_ERROR_OUT_OF_DATE_KHR;
        CaptureSlot *selected = nullptr;
        {
            std::lock_guard<std::mutex> lock(sendMutex);
            if (failed) return VK_ERROR_DEVICE_LOST;
            for (auto &slot : slots) if (!slot.busy) { selected = &slot; slot.busy = true; break; }
        }
        if (!selected) return VK_SUCCESS;
        auto &slot = *selected;
        DroidDeckXrFrameHeader header{};
        if (!Header(header)) { std::lock_guard<std::mutex> lock(sendMutex); slot.busy = false; return VK_SUCCESS; }
        auto &command = slot.command;
        auto &buffer = slot.buffer;
        auto &fence = slot.fence;
        auto &rgbaImage = slot.rgbaImage;
        auto &rgbaInitialized = slot.rgbaInitialized;
        VkResult result = d.ResetCommandBuffer(command, 0);
        if (result) return Fail(result, slot);
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if ((result = d.BeginCommandBuffer(command, &begin))) return Fail(result, slot);
        VkImageMemoryBarrier image{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        image.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; image.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        image.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR; image.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        image.srcQueueFamilyIndex = image.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        image.image = images[index]; image.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        d.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &image);
        VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {extent.width, extent.height, 1};
        if (!gpu) {
            VkImageMemoryBarrier rgba = image;
            rgba.image = rgbaImage; rgba.srcAccessMask = rgbaInitialized ? VK_ACCESS_TRANSFER_READ_BIT : 0;
            rgba.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            rgba.oldLayout = rgbaInitialized ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
            rgba.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            d.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &rgba);
            VkImageBlit blit{}; blit.srcSubresource = blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            blit.srcOffsets[1] = blit.dstOffsets[1] = {static_cast<int32_t>(extent.width), static_cast<int32_t>(extent.height), 1};
            d.CmdBlitImage(command, images[index], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rgbaImage,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
            rgba.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; rgba.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            rgba.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; rgba.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            d.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &rgba);
        }
        VkBufferMemoryBarrier ownership{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        ownership.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT; ownership.dstQueueFamilyIndex = family;
        ownership.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; ownership.buffer = buffer; ownership.size = VK_WHOLE_SIZE;
        if (gpu) d.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     0, 0, nullptr, 1, &ownership, 0, nullptr);
        d.CmdCopyImageToBuffer(command, gpu ? images[index] : rgbaImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, &copy);
        image.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT; image.dstAccessMask = 0;
        image.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL; image.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkBufferMemoryBarrier host{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        host.srcQueueFamilyIndex = host.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; host.buffer = buffer; host.size = VK_WHOLE_SIZE;
        if (gpu) {
            host.dstAccessMask = 0; host.srcQueueFamilyIndex = family; host.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        }
        d.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, (gpu ? 0 : VK_PIPELINE_STAGE_HOST_BIT) | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 1, &host, 1, &image);
        if ((result = d.EndCommandBuffer(command)) || (result = d.ResetFences(d.handle, 1, &fence))) return Fail(result, slot);
        std::vector<VkPipelineStageFlags> stages(present.waitSemaphoreCount, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = present.waitSemaphoreCount; submit.pWaitSemaphores = present.pWaitSemaphores;
        submit.pWaitDstStageMask = stages.data(); submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
        submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &ready[index];
        if ((result = d.QueueSubmit(queue, 1, &submit, fence))) return Fail(result, slot);
        rgbaInitialized = true;
        { std::lock_guard<std::mutex> lock(sendMutex); slot.header = header; slot.queued = true; sendReady.notify_all(); }
        *completion = ready[index];
        return VK_SUCCESS;
    }
};
std::mutex mutex;
std::unordered_map<VkDevice, std::unique_ptr<Device>> devices;
std::unordered_map<VkSwapchainKHR, std::unique_ptr<Swapchain>> swapchains;
}

extern "C" void droiddeck_wsi_device(VkDevice device, VkPhysicalDevice physical, VkInstance instance,
                                     PFN_vkGetInstanceProcAddr gipa, PFN_vkGetDeviceProcAddr gdpa, const VkDeviceCreateInfo *create) {
    const char *mode = getenv("DROIDDECK_VR_PRESENT");
    if (!mode || strcmp(mode, "questwsi") || strcmp(program_invocation_short_name, "vrcompositor")) return;
    auto d = std::make_unique<Device>(); d->handle = device;
    d->GetMemoryFdPropertiesKHR = reinterpret_cast<PFN_vkGetMemoryFdPropertiesKHR>(gdpa(device, "vkGetMemoryFdPropertiesKHR"));
    for (uint32_t index = 0; index < create->enabledExtensionCount; index++)
        if (!strcmp(create->ppEnabledExtensionNames[index], "VK_EXT_queue_family_foreign")) d->foreign = true;
#define LOAD(name) d->name = reinterpret_cast<PFN_vk##name>(gdpa(device, "vk" #name)); if (!d->name) return;
    FUNCTIONS(LOAD)
#undef LOAD
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(gipa(instance, "vkGetPhysicalDeviceMemoryProperties"));
    properties(physical, &d->memory);
    auto getQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(gdpa(device, "vkGetDeviceQueue"));
    for (uint32_t index = 0; index < create->queueCreateInfoCount; index++) {
        const auto &info = create->pQueueCreateInfos[index];
        if (info.flags) continue;
        for (uint32_t number = 0; number < info.queueCount; number++) {
            VkQueue queue{}; getQueue(device, info.queueFamilyIndex, number, &queue);
            if (queue) d->queues[queue] = info.queueFamilyIndex;
        }
    }
    std::lock_guard<std::mutex> lock(mutex); devices[device] = std::move(d);
}
extern "C" void droiddeck_wsi_destroy(VkDevice device) {
    std::lock_guard<std::mutex> lock(mutex);
    for (auto it = swapchains.begin(); it != swapchains.end();)
        if (it->second->device->handle == device) it = swapchains.erase(it); else ++it;
    devices.erase(device);
}
extern "C" void droiddeck_wsi_swapchain(VkDevice device, VkSwapchainKHR chain, const VkSwapchainCreateInfoKHR *create) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = devices.find(device); if (it == devices.end()) return;
    if (create->imageFormat != VK_FORMAT_R8G8B8A8_UNORM && create->imageFormat != VK_FORMAT_R8G8B8A8_SRGB &&
        create->imageFormat != VK_FORMAT_B8G8R8A8_UNORM && create->imageFormat != VK_FORMAT_B8G8R8A8_SRGB) return;
    auto chainState = std::make_unique<Swapchain>(); chainState->device = it->second.get();
    chainState->extent = create->imageExtent; chainState->format = create->imageFormat;
    swapchains[chain] = std::move(chainState);
}
extern "C" void droiddeck_wsi_forget(VkDevice device, VkSwapchainKHR chain) {
    (void)device; std::lock_guard<std::mutex> lock(mutex); swapchains.erase(chain);
}
extern "C" VkResult droiddeck_wsi_present(VkDevice device, VkQueue queue, const VkPresentInfoKHR *present, PFN_vkQueuePresentKHR next) try {
    (void)device; std::lock_guard<std::mutex> lock(mutex);
    if (present->swapchainCount != 1) return next(queue, present);
    auto it = swapchains.find(present->pSwapchains[0]);
    if (it == swapchains.end()) return next(queue, present);
    if (!it->second->Initialize(present->pSwapchains[0], queue)) return VK_ERROR_INITIALIZATION_FAILED;
    auto family = it->second->device->queues.find(queue);
    if (family == it->second->device->queues.end() || family->second != it->second->family)
        return VK_ERROR_INITIALIZATION_FAILED;
    VkSemaphore completion{};
    VkResult result = it->second->Capture(queue, present->pImageIndices[0], *present, &completion);
    if (result) return result;
    if (!completion) return next(queue, present);
    // Reacquiring this swapchain image also proves its previous present wait semaphore is reusable.
    VkPresentInfoKHR copy = *present; copy.waitSemaphoreCount = 1; copy.pWaitSemaphores = &completion;
    return next(queue, &copy);
} catch (...) {
    std::fprintf(stderr, "[droiddeck-vr-layer] WSI capture failed; restart SteamVR\n");
    return VK_ERROR_OUT_OF_HOST_MEMORY;
}
