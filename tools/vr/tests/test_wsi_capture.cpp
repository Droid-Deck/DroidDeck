#include "../src/wsi_capture.cpp"
#include <cassert>

static VkResult resetResult = VK_SUCCESS;
static unsigned submitted, presented, copied, blitted;
static bool gpuTest;
static unsigned acquired, released;
static VkSemaphore originalWaits[2]{(VkSemaphore)10, (VkSemaphore)11};
static VkSemaphore expectedPresent;
static VkResult VKAPI_CALL Present(VkQueue, const VkPresentInfoKHR *info) {
    presented++;
    if (expectedPresent) assert(info->waitSemaphoreCount == 1 && info->pWaitSemaphores[0] == expectedPresent);
    else assert(info->waitSemaphoreCount == 2 && info->pWaitSemaphores[0] == originalWaits[0]);
    return VK_SUCCESS;
}

int main() {
    char path[] = "/tmp/droiddeck-wsi-XXXXXX"; int fd = mkstemp(path); assert(fd >= 0);
    DroidDeckXrTracking tracking{};
    tracking.magic = DROIDDECK_XR_MAGIC; tracking.version = DROIDDECK_XR_VERSION; tracking.active = 1;
    tracking.sampled_ns = xr_frame_now(); tracking.period_ns = 11111111;
    for (auto &eye : tracking.eyes) {
        eye.width = eye.height = 4; eye.pose.orientation[3] = 1; eye.pose.flags = 15;
        eye.fov[0] = eye.fov[3] = -0.7f; eye.fov[1] = eye.fov[2] = 0.7f;
    }
    assert(write(fd, &tracking, sizeof(tracking)) == sizeof(tracking)); close(fd);
    auto device = std::make_unique<Device>();
    device->ResetCommandBuffer = [](VkCommandBuffer, VkCommandBufferResetFlags) { return resetResult; };
    device->BeginCommandBuffer = [](VkCommandBuffer, const VkCommandBufferBeginInfo *) { return VK_SUCCESS; };
    device->EndCommandBuffer = [](VkCommandBuffer) { return VK_SUCCESS; };
    device->ResetFences = [](VkDevice, uint32_t, const VkFence *) { return VK_SUCCESS; };
    device->CmdPipelineBarrier = [](VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
        VkDependencyFlags, uint32_t, const VkMemoryBarrier *, uint32_t buffers, const VkBufferMemoryBarrier *host,
        uint32_t images, const VkImageMemoryBarrier *image) {
        if (gpuTest && !images) {
            assert(buffers == 1 && host->srcQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT
                && host->dstQueueFamilyIndex == 7 && host->dstAccessMask == VK_ACCESS_TRANSFER_WRITE_BIT);
            acquired++; return;
        }
        assert(images == 1 && image->srcQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
        if (buffers) {
            if (gpuTest) {
                assert(host->srcQueueFamilyIndex == 7 && host->dstQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT
                    && !host->dstAccessMask); released++;
            } else assert(host->dstAccessMask == VK_ACCESS_HOST_READ_BIT);
        }
    };
    device->CmdBlitImage = [](VkCommandBuffer, VkImage, VkImageLayout source, VkImage, VkImageLayout destination,
        uint32_t count, const VkImageBlit *blit, VkFilter filter) {
        assert(source == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && destination == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        assert(count == 1 && filter == VK_FILTER_NEAREST && blit->srcOffsets[1].x == 8); blitted++;
    };
    device->CmdCopyImageToBuffer = [](VkCommandBuffer, VkImage, VkImageLayout layout, VkBuffer,
        uint32_t count, const VkBufferImageCopy *copy) {
        assert(layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL && count == 1 && copy->imageExtent.width == 8); copied++;
    };
    device->QueueSubmit = [](VkQueue, uint32_t count, const VkSubmitInfo *info, VkFence) {
        assert(count == 1 && info->waitSemaphoreCount == 2 && info->signalSemaphoreCount == 1);
        for (int i = 0; i < 2; i++) assert(info->pWaitSemaphores[i] == originalWaits[i]
            && info->pWaitDstStageMask[i] == VK_PIPELINE_STAGE_TRANSFER_BIT);
        submitted++; return VK_SUCCESS;
    };
    device->DestroySemaphore = [](VkDevice, VkSemaphore, const VkAllocationCallbacks *) {};
    VkDevice handle = (VkDevice)1; VkQueue queue = (VkQueue)2;
    device->handle = handle; device->queues[queue] = 7;
    auto chain = std::make_unique<Swapchain>(path);
    chain->device = device.get(); chain->family = 7; chain->initialized = true; chain->extent = {8, 4};
    chain->images = {(VkImage)20, (VkImage)21, (VkImage)22};
    chain->ready = {(VkSemaphore)30, (VkSemaphore)31, (VkSemaphore)32};
    devices[handle] = std::move(device);
    VkSwapchainKHR swapchain = (VkSwapchainKHR)3; swapchains[swapchain] = std::move(chain);
    VkPresentInfoKHR info{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    uint32_t index = 0; info.pImageIndices = &index; info.swapchainCount = 1; info.pSwapchains = &swapchain;
    info.waitSemaphoreCount = 2; info.pWaitSemaphores = originalWaits;
    for (index = 0; index < 3; index++) {
        expectedPresent = (VkSemaphore)(uintptr_t)(30 + index);
        assert(droiddeck_wsi_present(handle, queue, &info, Present) == VK_SUCCESS);
    }
    index = 0; expectedPresent = VK_NULL_HANDLE;
    assert(droiddeck_wsi_present(handle, queue, &info, Present) == VK_SUCCESS);
    assert(submitted == 3 && presented == 4 && copied == 3 && blitted == 3);
    for (const auto &slot : swapchains[swapchain]->slots) assert(slot.busy && slot.queued && xr_frame_valid(slot.header));
    gpuTest = swapchains[swapchain]->gpu = true;
    swapchains[swapchain]->slots[0].busy = false;
    expectedPresent = (VkSemaphore)30;
    assert(droiddeck_wsi_present(handle, queue, &info, Present) == VK_SUCCESS);
    assert(acquired == 1 && released == 1 && copied == 4 && blitted == 3);
    swapchains[swapchain]->slots[0].busy = false;
    resetResult = VK_ERROR_DEVICE_LOST;
    assert(droiddeck_wsi_present(handle, queue, &info, Present) == VK_ERROR_DEVICE_LOST);
    assert(droiddeck_wsi_present(handle, queue, &info, Present) == VK_ERROR_DEVICE_LOST);
    assert(submitted == 4 && presented == 5);
    droiddeck_wsi_destroy(handle); unlink(path);
    std::puts("WSI synchronization passed: original waits retained, per-image completion, bounded in-flight buffers and fatal error propagation");
}
