#include <assert.h>
#include <stdint.h>
#include <string.h>

#include "vk_linear_dmabuf.h"

static void check_padded_rows(void) {
    VkBufferImageCopy copy;
    memset(&copy, 0xa5, sizeof(copy));
    const VkDeviceSize need = vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 1560, 720, 6400, 0, &copy);
    assert(copy.bufferRowLength == 1600);
    assert(copy.bufferRowLength != 1560);
    assert(copy.bufferOffset == 0);
    assert(copy.bufferImageHeight == 720);
    assert(copy.imageSubresource.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    assert(copy.imageSubresource.mipLevel == 0);
    assert(copy.imageSubresource.baseArrayLayer == 0);
    assert(copy.imageSubresource.layerCount == 1);
    assert(copy.imageOffset.x == 0 && copy.imageOffset.y == 0 && copy.imageOffset.z == 0);
    assert(copy.imageExtent.width == 1560);
    assert(copy.imageExtent.height == 720);
    assert(copy.imageExtent.depth == 1);
    assert(need == (VkDeviceSize)6400 * 719 + 1560 * 4);
}

static void check_offset_and_wide_texels(void) {
    VkBufferImageCopy copy;
    const VkDeviceSize need = vkp_linear_dmabuf_copy(VK_FORMAT_R16G16B16A16_SFLOAT, 1920, 1080, 15360, 4096, &copy);
    assert(copy.bufferRowLength == 1920);
    assert(copy.bufferOffset == 4096);
    assert(need == 4096 + (VkDeviceSize)15360 * 1079 + 1920 * 8);

    assert(vkp_linear_dmabuf_copy(VK_FORMAT_B8G8R8A8_UNORM, 1, 1, 64, 0, &copy) == 4);
    assert(copy.bufferRowLength == 16);
}

static void check_rejected_layouts(void) {
    VkBufferImageCopy copy;
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 1560, 720, 6236, 0, &copy) == 0);
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 1560, 720, 6402, 0, &copy) == 0);
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 1560, 720, 6400, 2, &copy) == 0);
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R16G16B16A16_SFLOAT, 1560, 720, 6240, 0, &copy) == 0);
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 0, 720, 6400, 0, &copy) == 0);
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 1560, 0, 6400, 0, &copy) == 0);
    assert(vkp_linear_dmabuf_copy(VK_FORMAT_R8G8B8A8_UNORM, 1560, 720, 6400, 0, NULL) == 0);
}

static void check_path_choice(void) {
    const uint64_t linear = 0;
    const uint64_t qcom_compressed = 0x0500000000000001ULL;
    assert(vkp_linear_dmabuf_through_buffer(0, linear, 0));
    assert(!vkp_linear_dmabuf_through_buffer(1, linear, 0));
    assert(!vkp_linear_dmabuf_through_buffer(1, qcom_compressed, 0));
    assert(!vkp_linear_dmabuf_through_buffer(0, qcom_compressed, 0));
    assert(!vkp_linear_dmabuf_through_buffer(0, linear, 1));
    assert(!vkp_linear_dmabuf_through_buffer(1, linear, 1));
}

static void check_mapped_choice(void) {
    assert(vkp_linear_dmabuf_mapped(0, 0));
    assert(!vkp_linear_dmabuf_mapped(1, 0));
    assert(!vkp_linear_dmabuf_mapped(0, 1));
    assert(!vkp_linear_dmabuf_mapped(1, 1));
}

static void check_host_copy(void) {
    const VkBuffer buffer = (VkBuffer)(uintptr_t)0x1234;
    const VkBufferMemoryBarrier b = vkp_linear_dmabuf_host_copy(buffer);
    assert(b.sType == VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER);
    assert(b.pNext == NULL);
    assert(b.srcQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
    assert(b.dstQueueFamilyIndex == VK_QUEUE_FAMILY_IGNORED);
    assert(b.srcAccessMask == 0);
    assert(b.dstAccessMask == VK_ACCESS_TRANSFER_READ_BIT);
    assert(b.buffer == buffer);
    assert(b.offset == 0);
    assert(b.size == VK_WHOLE_SIZE);
}

static void check_foreign_image(void) {
    assert(vkp_linear_dmabuf_foreign_image(1, 0));
    assert(!vkp_linear_dmabuf_foreign_image(1, 1));
    assert(!vkp_linear_dmabuf_foreign_image(0, 0));
    assert(!vkp_linear_dmabuf_foreign_image(0, 1));
}

static void check_padded_layer_targets(void) {
    const VkFormat rgba = VK_FORMAT_R8G8B8A8_UNORM;
    const uint64_t qcom_compressed = 0x0500000000000001ULL;
    assert(vkp_padded_linear_target_needs_layout(0, 0, rgba, 720, 3072, 0));
    assert(vkp_padded_linear_target_needs_layout(0, 0, rgba, 720, 2880, 64));
    assert(!vkp_padded_linear_target_needs_layout(0, 0, rgba, 1280, 5120, 0));
    assert(!vkp_padded_linear_target_needs_layout(1, 0, rgba, 720, 3072, 0));
    assert(!vkp_padded_linear_target_needs_layout(0, qcom_compressed, rgba, 720, 3072, 0));
}

static void check_readback_order(void) {
    assert(vkp_linear_dmabuf_readback_stage(1) & VK_PIPELINE_STAGE_TRANSFER_BIT);
    assert(vkp_linear_dmabuf_readback_access(1) & VK_ACCESS_TRANSFER_WRITE_BIT);
    assert(vkp_linear_dmabuf_readback_stage(0) == VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    assert(vkp_linear_dmabuf_readback_access(0) == 0);
}

static void check_ownership(void) {
    const VkBuffer buffer = (VkBuffer)(uintptr_t)0x4321;
    const uint32_t queue_family = 3;
    const VkBufferMemoryBarrier acquire = vkp_linear_dmabuf_ownership(buffer, queue_family, VK_ACCESS_TRANSFER_READ_BIT, 1);
    assert(acquire.sType == VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER);
    assert(acquire.pNext == NULL);
    assert(acquire.srcQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(acquire.dstQueueFamilyIndex == queue_family);
    assert(acquire.srcAccessMask == 0);
    assert(acquire.dstAccessMask == VK_ACCESS_TRANSFER_READ_BIT);
    assert(acquire.buffer == buffer);
    assert(acquire.offset == 0);
    assert(acquire.size == VK_WHOLE_SIZE);

    const VkBufferMemoryBarrier release = vkp_linear_dmabuf_ownership(buffer, queue_family, VK_ACCESS_TRANSFER_READ_BIT, 0);
    assert(release.sType == VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER);
    assert(release.srcQueueFamilyIndex == queue_family);
    assert(release.dstQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(release.srcAccessMask == VK_ACCESS_TRANSFER_READ_BIT);
    assert(release.dstAccessMask == 0);
    assert(release.buffer == buffer);
    assert(release.size == VK_WHOLE_SIZE);

    const VkBufferMemoryBarrier write = vkp_linear_dmabuf_ownership(buffer, queue_family, VK_ACCESS_TRANSFER_WRITE_BIT, 1);
    const VkBufferMemoryBarrier written = vkp_linear_dmabuf_ownership(buffer, queue_family, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
    assert(write.srcQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(write.dstAccessMask == VK_ACCESS_TRANSFER_WRITE_BIT);
    assert(written.dstQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(written.srcAccessMask == VK_ACCESS_TRANSFER_WRITE_BIT);
}

int main(void) {
    check_padded_rows();
    check_offset_and_wide_texels();
    check_rejected_layouts();
    check_path_choice();
    check_padded_layer_targets();
    check_foreign_image();
    check_mapped_choice();
    check_host_copy();
    check_readback_order();
    check_ownership();
    return 0;
}
