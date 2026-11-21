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

static inline VkPipelineStageFlags vkp_linear_dmabuf_readback_stage(int in_general) {
    return in_general ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
}

static inline VkAccessFlags vkp_linear_dmabuf_readback_access(int in_general) {
    return in_general ? VK_ACCESS_TRANSFER_WRITE_BIT : 0;
}

static inline uint32_t vkp_linear_dmabuf_texel_bytes(VkFormat fmt) {
    return fmt == VK_FORMAT_R16G16B16A16_SFLOAT ? 8u : 4u;
}

static inline int vkp_padded_linear_target_needs_layout(int drm_modifiers, uint64_t modifier, VkFormat fmt,
                                                        int w, uint32_t stride, uint32_t offset) {
    return !drm_modifiers && modifier == 0 &&
           (offset != 0 || (uint64_t)stride != (uint64_t)w * vkp_linear_dmabuf_texel_bytes(fmt));
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
