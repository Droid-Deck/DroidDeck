#include <assert.h>
#include <stdint.h>
#include <string.h>

#include "vk_external_image_sync.h"

static void check_external_pair(const struct vkp_external_client_barrier_pair *pair,
                                VkImage image, uint32_t queue_family) {
    assert(pair->acquire.sType == VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
    assert(pair->acquire.oldLayout == VK_IMAGE_LAYOUT_GENERAL);
    assert(pair->acquire.newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    assert(pair->acquire.srcQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(pair->acquire.dstQueueFamilyIndex == queue_family);
    assert(pair->acquire.image == image);
    assert(pair->acquire.srcAccessMask == 0);
    assert(pair->acquire.dstAccessMask == VK_ACCESS_TRANSFER_READ_BIT);
    assert(pair->acquire.subresourceRange.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    assert(pair->acquire.subresourceRange.baseMipLevel == 0);
    assert(pair->acquire.subresourceRange.levelCount == 1);
    assert(pair->acquire.subresourceRange.baseArrayLayer == 0);
    assert(pair->acquire.subresourceRange.layerCount == 1);

    assert(pair->release.sType == VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
    assert(pair->release.oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    assert(pair->release.newLayout == VK_IMAGE_LAYOUT_GENERAL);
    assert(pair->release.srcQueueFamilyIndex == queue_family);
    assert(pair->release.dstQueueFamilyIndex == VK_QUEUE_FAMILY_FOREIGN_EXT);
    assert(pair->release.image == image);
    assert(pair->release.srcAccessMask == VK_ACCESS_MEMORY_READ_BIT);
    assert(pair->release.dstAccessMask == 0);
    assert(pair->release.subresourceRange.aspectMask == VK_IMAGE_ASPECT_COLOR_BIT);
    assert(pair->release.subresourceRange.levelCount == 1);
    assert(pair->release.subresourceRange.layerCount == 1);
}

int main(void) {
    const VkImage image = (VkImage)(uintptr_t)0x1234;
    const VkImage ahb_image = (VkImage)(uintptr_t)0x5678;
    const uint32_t queue_family = 7;
    struct vkp_external_client_barrier_pair generic, ahb;

    assert(vkp_external_client_barrier_pair(image, queue_family, 1, 0, &generic));
    check_external_pair(&generic, image, queue_family);

    /* AHardwareBuffer imports deliberately use the same client flags and lifecycle. */
    assert(vkp_external_client_barrier_pair(ahb_image, queue_family, 1, 0, &ahb));
    check_external_pair(&ahb, ahb_image, queue_family);

    unsigned char untouched[sizeof(generic)];
    memset(&generic, 0xa5, sizeof(generic));
    memcpy(untouched, &generic, sizeof(generic));
    assert(!vkp_external_client_barrier_pair(image, queue_family, 0, 0, &generic));
    assert(memcmp(&generic, untouched, sizeof(generic)) == 0);
    assert(!vkp_is_external_client_image(0, 0));

    memset(&generic, 0x5a, sizeof(generic));
    memcpy(untouched, &generic, sizeof(generic));
    assert(!vkp_external_client_barrier_pair(image, queue_family, 1, 1, &generic));
    assert(memcmp(&generic, untouched, sizeof(generic)) == 0);
    assert(!vkp_is_external_client_image(1, 1));
    assert(vkp_is_external_client_image(1, 0));
    return 0;
}
