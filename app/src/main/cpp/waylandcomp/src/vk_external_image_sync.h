#ifndef VK_EXTERNAL_IMAGE_SYNC_H
#define VK_EXTERNAL_IMAGE_SYNC_H

#include <vulkan/vulkan.h>

struct vkp_external_client_barrier_pair {
    VkImageMemoryBarrier acquire;
    VkImageMemoryBarrier release;
};

static inline int vkp_is_external_client_image(int dmabuf, int blit_dst) {
    return dmabuf && !blit_dst;
}

/* Client-owned dma-bufs, including AHardwareBuffers, live in GENERAL while
 * FOREIGN owns them. Layer pool dma-bufs are destinations with a separate
 * acquire/release lifecycle and must never take this source-image path. */
static inline int vkp_external_client_barrier_pair(
        VkImage image, uint32_t queue_family, int dmabuf, int blit_dst,
        struct vkp_external_client_barrier_pair *pair) {
    if (!vkp_is_external_client_image(dmabuf, blit_dst) || !pair) return 0;

    const VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    pair->acquire = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .dstQueueFamilyIndex = queue_family,
        .image = image,
        .subresourceRange = range,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
    };
    pair->release = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = queue_family,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .image = image,
        .subresourceRange = range,
        .srcAccessMask = VK_ACCESS_MEMORY_READ_BIT,
    };
    return 1;
}

#endif
