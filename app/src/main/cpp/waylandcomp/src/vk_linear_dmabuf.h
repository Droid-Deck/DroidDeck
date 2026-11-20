#ifndef VK_LINEAR_DMABUF_H
#define VK_LINEAR_DMABUF_H

#include <stdint.h>
#include <vulkan/vulkan.h>

static inline int vkp_linear_dmabuf_through_buffer(int drm_modifiers, uint64_t modifier, int blit_dst) {
    return !drm_modifiers && modifier == 0 && !blit_dst;
}

static inline int vkp_linear_dmabuf_foreign_image(int dmabuf, int staged) {
    return dmabuf && !staged;
}

static inline uint32_t vkp_linear_dmabuf_texel_bytes(VkFormat fmt) {
    return fmt == VK_FORMAT_R16G16B16A16_SFLOAT ? 8u : 4u;
}

static inline VkDeviceSize vkp_linear_dmabuf_copy(VkFormat fmt, int w, int h, uint32_t stride, uint32_t offset,
                                                  VkBufferImageCopy *copy) {
    const uint32_t texel = vkp_linear_dmabuf_texel_bytes(fmt);
    if (!copy || w <= 0 || h <= 0 || stride % texel || offset % texel || stride / texel < (uint32_t)w) return 0;
    *copy = (VkBufferImageCopy){
        .bufferOffset = offset,
        .bufferRowLength = stride / texel,
        .bufferImageHeight = (uint32_t)h,
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {(uint32_t)w, (uint32_t)h, 1},
    };
    return (VkDeviceSize)offset + (VkDeviceSize)stride * (VkDeviceSize)(h - 1) + (VkDeviceSize)w * texel;
}

static inline VkBufferMemoryBarrier vkp_linear_dmabuf_ownership(VkBuffer buffer, uint32_t queue_family,
                                                                int acquire) {
    return (VkBufferMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        .srcAccessMask = acquire ? 0 : VK_ACCESS_TRANSFER_READ_BIT,
        .dstAccessMask = acquire ? VK_ACCESS_TRANSFER_READ_BIT : 0,
        .srcQueueFamilyIndex = acquire ? VK_QUEUE_FAMILY_FOREIGN_EXT : queue_family,
        .dstQueueFamilyIndex = acquire ? queue_family : VK_QUEUE_FAMILY_FOREIGN_EXT,
        .buffer = buffer,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
}

#endif
